// v1.8 回归：点击行为选项、长按菜单开合不打断动作、渲染档位与诊断接口已移除
// 用法：node scripts/test-v18.mjs   （需要 playwright-core，见 package.json）
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { p, fileUrl, launch, ensureBuilt } from './_env.mjs';

ensureBuilt();

const b = await launch();
try {
  const page = await b.newPage({ viewport: { width: 96, height: 113 }, deviceScaleFactor: 3 });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(() => {
    window.saved = [];
    window.Android = {
      getConfig: () => JSON.stringify({ w: 96, roam: false, variant: 'normal' }),
      saveConfig: (s) => saved.push(JSON.parse(s)),
      ready() {}, assetFail() {}, closeMenu() {}
    };
  });
  await page.goto(fileUrl('android/assets/pet.html'));
  await page.waitForTimeout(1500);

  // 手动渲染档位与诊断接口在 v1.8 全部删除
  assert.equal(await page.locator('#btnRender').count(), 0);
  assert.equal(await page.evaluate(() => typeof petSnapshot), 'undefined');
  assert.equal(await page.evaluate(() => typeof petDiagInfo), 'undefined');
  assert.equal(await page.$eval('#swTap', (e) => e.textContent), '全部动作随机');
  assert.equal(await page.$eval('#cv', (e) => getComputedStyle(e).display), 'block');

  // 默认「全部动作随机」：从完整动作目录里抽
  await page.evaluate(() => {
    Math.random = () => 0.999999;
    const hit = document.getElementById('hit');
    hit.dispatchEvent(new PointerEvent('pointerdown'));
    hit.dispatchEvent(new PointerEvent('pointerup'));
  });
  await page.waitForTimeout(800);
  const picked = await page.evaluate(() => petPlaying());
  const cat = JSON.parse(readFileSync(p('web', 'catalog.json'), 'utf8'));
  assert.equal(picked.id, cat.items.at(-1).id);
  console.log('PASS 默认点击从完整目录抽取:', picked.id);

  // 长按菜单：开合不重置动作与进度；普通位置与部分移出屏幕的位置都保持
  for (const [x, y] of [[210, 320], [-10, 100]]) {
    await page.evaluate(([px, py]) => petPrepareMenu(px, py), [x, y]);
    await page.setViewportSize({ width: 360, height: 780 });
    await page.evaluate(() => petOpenMenu());
    await page.waitForTimeout(380);
    const before = await page.evaluate(() => ({
      id: petPlaying().id,
      time: document.querySelector('video.on').currentTime
    }));
    await page.evaluate(() => petCloseMenu());
    await page.waitForTimeout(350);
    assert.deepEqual(
      await page.$eval('#pet', (e) => { const r = e.getBoundingClientRect(); return [r.x, r.y]; }),
      [x, y]
    );
    // 缩窗前原生会先调 petPreShrink()：这几帧角色必须是隐藏的。
    // 只要它还在可见状态，就会按大窗口的屏幕坐标被画出来 —— 用户看到的就是
    // "从左上角闪回原位"，像渲染崩了。
    await page.evaluate(() => petPreShrink());
    await page.setViewportSize({ width: 96, height: 113 });
    assert.equal(await page.$eval('#pet', (e) => getComputedStyle(e).visibility), 'hidden');
    await page.evaluate(() => petMenuClosed());
    await page.waitForTimeout(60);
    // 缩到位后角色回到窗口里，且正好贴在 (0,0)：中间不允许出现位移帧
    // （角色高度 96×1.1783=113.1，窗口取整到 113，底边对齐会让 top 差 0.1px，故给容差）
    const box = await page.$eval('#pet', (e) => {
      const r = e.getBoundingClientRect();
      return [Math.round(r.x * 10) / 10, Math.round(r.y * 10) / 10];
    });
    assert.ok(Math.abs(box[0]) < 0.6 && Math.abs(box[1]) < 0.6, `角色应贴在窗口左上角，实际 ${box}`);
    assert.equal(await page.$eval('#pet', (e) => getComputedStyle(e).visibility), 'visible');
    assert.equal(await page.$eval('#pet', (e) => getComputedStyle(e).transitionDuration), '0s');
    assert.equal(await page.evaluate(() => petPlaying().id), before.id);
    assert.ok(await page.evaluate((t) => document.querySelector('video.on').currentTime >= t, before.time));
  }
  console.log('PASS 菜单开合保留动作与进度，普通位置与部分移出屏幕位置均正确');

  // 点击行为三档
  await page.evaluate(() => document.getElementById('swTap').click());
  assert.equal(await page.$eval('#swTap', (e) => e.textContent), '互动反应');
  await page.evaluate(() => {
    const h = document.getElementById('hit');
    h.dispatchEvent(new PointerEvent('pointerdown'));
    h.dispatchEvent(new PointerEvent('pointerup'));
  });
  await page.waitForTimeout(500);
  const reacted = await page.evaluate(() => petPlaying().id);
  assert.ok(cat.items.some((i) => i.id === reacted && i.category === 'clicks'));
  await page.evaluate(() => document.getElementById('swTap').click());
  assert.equal(await page.$eval('#swTap', (e) => e.textContent), '不响应');
  const frozen = await page.evaluate(() => petPlaying().id);
  await page.evaluate(() => {
    const h = document.getElementById('hit');
    h.dispatchEvent(new PointerEvent('pointerdown'));
    h.dispatchEvent(new PointerEvent('pointerup'));
  });
  await page.waitForTimeout(100);
  assert.equal(await page.evaluate(() => petPlaying().id), frozen);
  assert.equal(await page.evaluate(() => saved.at(-1).tapMode), 'none');
  assert.deepEqual(errors, []);
  console.log('PASS 互动/不响应档位与偏好持久化，页面零报错');

  // 原生侧：启动恢复常驻、诊断与手动档位彻底移除（源码级检查）
  const main = readFileSync(p('android', 'src', 'com', 'whale', 'pet', 'MainActivity.java'), 'utf8');
  assert.match(main, /Prefs\.enabled\(this\) && Settings\.canDrawOverlays\(this\)\) PetService\.start\(this\)/);
  const service = readFileSync(p('android', 'src', 'com', 'whale', 'pet', 'PetService.java'), 'utf8');
  assert.doesNotMatch(service, /runDiag|saveDiag|renderDiag|Prefs\.render|K_RENDER/);
  assert.doesNotMatch(readFileSync(p('android', 'res', 'layout', 'activity_main.xml'), 'utf8'), /btnRender|tvRender|btnDiag|ivDiag/);
  console.log('PASS 原生启动恢复与诊断移除源码检查');
} finally {
  await b.close();
}
