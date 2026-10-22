// 拖动手感回归：抓起来立刻换动作、倍速跟手速、松手/飞完才回待机、拖动期间不漫游。
// 用法：node scripts/test-drag.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { p, fileUrl, launch, ensureBuilt } from './_env.mjs';

ensureBuilt();

const b = await launch();
try {
  const page = await b.newPage({ viewport: { width: 96, height: 113 }, deviceScaleFactor: 3 });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(() => {
    window.__roamAsked = 0;
    window.Android = {
      getConfig: () => JSON.stringify({ w: 96, roam: true, variant: 'normal', idle: 'breathing' }),
      saveConfig() {}, ready() {}, assetFail() {}, closeMenu() {},
      roamNow() { window.__roamAsked++; }
    };
  });
  await page.goto(fileUrl('android/assets/pet.html'));
  await page.waitForTimeout(1300);

  // 抓起：立刻切到"悬空"动作，并且是循环播（拖多久都在动，不会定格）
  await page.evaluate(() => petDragStart());
  await page.waitForTimeout(220);
  const grabbed = await page.evaluate(() => ({
    id: petPlaying().id,
    once: petPlaying().once,
    loop: document.querySelector('video.on').loop,
    rate: document.querySelector('video.on').playbackRate
  }));
  assert.deepEqual(grabbed, { id: 'dragged_in_midair', once: false, loop: true, rate: 1 });

  // 倍速跟手速：慢慢挪 0.8x，甩出去 1.7x
  await page.evaluate(() => petDragRate(1.7));
  assert.equal(await page.$eval('video.on', (v) => v.playbackRate), 1.7);
  await page.evaluate(() => petDragRate(0.8));
  assert.equal(await page.$eval('video.on', (v) => v.playbackRate), 0.8);
  // 越界值要夹住，不能把倍速塞成 5x 或负数
  await page.evaluate(() => petDragRate(9));
  assert.equal(await page.$eval('video.on', (v) => v.playbackRate), 1.8);

  // 拎在手上时不许自己走开
  await page.evaluate(() => petRoamReady());
  await page.waitForTimeout(60);
  assert.equal(await page.evaluate(() => window.__roamAsked), 0, '拖动中不应发起漫游');

  // 放下：回到常驻动作，倍速复原
  await page.evaluate(() => petDragEnd());
  await page.waitForTimeout(260);
  const dropped = await page.evaluate(() => ({
    id: petPlaying().id,
    once: petPlaying().once,
    rate: document.querySelector('video.on').playbackRate
  }));
  assert.deepEqual(dropped, { id: 'breathing', once: false, rate: 1 });

  // 松手之后漫游恢复正常
  await page.evaluate(() => petRoamReady());
  await page.waitForTimeout(60);
  assert.equal(await page.evaluate(() => window.__roamAsked), 1);
  assert.deepEqual(errors, []);
  console.log('PASS 拖动：抓起换动作/循环播、倍速跟手速并夹在 0.6~1.8、松手回待机、拖动中不漫游');

  // 原生侧：起步不跳、松手带速度、按阻尼滑行
  const box = fs.readFileSync(p('android', 'src', 'com', 'whale', 'pet', 'PetContainer.java'), 'utf8');
  assert.doesNotMatch(box, /host\.dragBy\(dx, dy\);\s*\/\/ 补上这一次位移/,
    '不能再把越过阈值的整段位移一次性补上（那会让窗口跳一个 slop）');
  assert.match(box, /downRawX \+= clampf\(dx, -slop, slop\)/, '起步应把起点前移到阈值处，避免跳变');
  assert.match(box, /host\.dragEnd\(/, '松手要带上速度');
  assert.match(box, /private float\[\] velocity\(\)/, '需要按最近采样算释放速度');
  assert.match(box, /void touchDown\(\)/, '按下时要能打断惯性滑行');

  const svc = fs.readFileSync(p('android', 'src', 'com', 'whale', 'pet', 'PetService.java'), 'utf8');
  assert.match(svc, /private void fling\(float vx, float vy\)/, '缺少惯性滑行');
  assert.match(svc, /new android\.view\.animation\.DecelerateInterpolator\(3f\)/, '滑行要用阻尼曲线');
  assert.match(svc, /float rate = clampf\(0\.8f \+ dp \/ dt \/ 900f, 0\.8f, 1\.7f\)/, '倍速应随速度变化');
  assert.match(svc, /js\("petDragStart\(\)"\)/);
  assert.match(svc, /js\("petDragEnd\(\)"\)/);
  assert.match(svc, /if \(!attached \|\| expanded \|\| !petVisible \|\| dragging \|\| glide != null\) return;/,
    '拖动/滑行期间不应发起漫游');
  console.log('PASS 原生侧：起步不跳、速度采样、阻尼滑行、拖动期间不漫游（源码级检查）');
} finally {
  await b.close();
}
