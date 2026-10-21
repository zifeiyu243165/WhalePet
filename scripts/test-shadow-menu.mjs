// 回归：动作面板收起后不得参与绘制（否则阴影会漏进 96dp 的小窗口，表现为"角色之外有底色"），
// 以及长按菜单开合时角色位置不跳、素材本身带 alpha 通道。
// 用法：node scripts/test-shadow-menu.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { p, fileUrl, launch, ensureBuilt } from './_env.mjs';

ensureBuilt();
const SHOT = p('.tmp');
fs.mkdirSync(SHOT, { recursive: true });

const b = await launch();
try {
  for (const width of [96, 150, 190]) {
    const page = await b.newPage({
      viewport: { width, height: Math.round(width * 1.1783) },
      deviceScaleFactor: 3
    });
    await page.goto(fileUrl('android/assets/pet.html'));
    await page.waitForTimeout(1000);
    await page.addStyleTag({ content: '#pet,#dec{visibility:hidden!important}' });
    assert.equal(await page.$eval('#sheet', (e) => getComputedStyle(e).visibility), 'hidden');
    await page.screenshot({ path: `${SHOT}/shadow-fixed-${width}.png`, omitBackground: true });
    await page.close();
  }
  console.log('PASS 三种窗口尺寸下收起的面板均不参与绘制');

  const page = await b.newPage({ viewport: { width: 96, height: 113 }, deviceScaleFactor: 3 });
  await page.addInitScript(() => {
    window.Android = {
      getConfig: () => JSON.stringify({ w: 96, roam: false }),
      ready() {}, assetFail() {}, closeMenu() {}
    };
  });
  await page.goto(fileUrl('android/assets/pet.html'));
  await page.waitForTimeout(1200);

  // 解码后的帧确实带透明通道（VP9 有损，透明处会残留 1~30 的 alpha，所以判 alpha<60）
  const alpha = await page.evaluate(() => {
    const v = document.querySelector('video.on');
    const c = document.createElement('canvas');
    c.width = 64; c.height = 36;
    const g = c.getContext('2d');
    g.drawImage(v, 0, 0, 64, 36);
    const d = g.getImageData(0, 0, 64, 36).data;
    let clear = 0, min = 255;
    for (let i = 3; i < d.length; i += 4) {
      if (d[i] < 60) clear++;
      if (d[i] < min) min = d[i];
    }
    return { clear, total: 64 * 36, min };
  });
  console.log('解码源 alpha:', alpha);
  assert.ok(alpha.clear > alpha.total / 2);

  await page.evaluate(() => petPrepareMenu(210, 320));
  await page.setViewportSize({ width: 360, height: 780 });
  await page.evaluate(() => petOpenMenu());
  await page.waitForTimeout(400);
  const pos = await page.$eval('#pet', (e) => {
    const r = e.getBoundingClientRect();
    return { x: r.x, y: r.y, visibility: getComputedStyle(e).visibility };
  });
  assert.deepEqual(pos, { x: 210, y: 320, visibility: 'visible' });

  await page.evaluate(() => petCloseMenu());
  await page.waitForTimeout(350);
  assert.equal(await page.$eval('#sheet', (e) => getComputedStyle(e).visibility), 'hidden');
  await page.setViewportSize({ width: 96, height: 113 });
  await page.evaluate(() => petMenuClosed());
  assert.equal(await page.$eval('#pet', (e) => getComputedStyle(e).visibility), 'visible');
  console.log('PASS 素材带 alpha、扩窗后角色锚点不变、收起后角色恢复可见');
} finally {
  await b.close();
}
