// 浏览器版（web/index.html）交互巡检：手机视口 390×844 下走一遍
// 加载 → 轻点 → 拖拽 → 点空白走过去 → 长按菜单 → 分类选动作 → 设置页
// 用法：node scripts/test-web.mjs    （素材走 CDN，需要联网）
import fs from 'node:fs';
import { p, fileUrl, launch, ensureBuilt } from './_env.mjs';

ensureBuilt();
const SHOT = p('.tmp');
fs.mkdirSync(SHOT, { recursive: true });

const browser = await launch();
const ctx = await browser.newContext({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  isMobile: true,
  hasTouch: true,
  userAgent:
    'Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36'
});
const page = await ctx.newPage();
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); });
page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));

await page.goto(fileUrl('web/index.html'), { waitUntil: 'load' });

const t0 = Date.now();
await page.waitForFunction(
  () => {
    const v = document.querySelector('#pet video.on');
    return v && v.readyState >= 2 && v.videoWidth > 0;
  },
  { timeout: 90000 }
).catch(() => {});
console.log('首帧就绪', Date.now() - t0, 'ms');

const state = () => page.evaluate(() => {
  const v = document.querySelector('#pet video.on');
  return {
    now: document.getElementById('now').textContent,
    src: (v.currentSrc || '').split('/').slice(-2).join('/'),
    paused: v.paused,
    x: new DOMMatrixReadOnly(getComputedStyle(document.getElementById('pet')).transform).m41,
    size: getComputedStyle(document.documentElement).getPropertyValue('--w').trim(),
    items: document.querySelectorAll('#grid .item').length,
    cats: document.querySelectorAll('#cats .cat').length
  };
});
console.log('初始', JSON.stringify(await state()));

const box = await page.locator('#hit').boundingBox();
const cx = box.x + box.width / 2;
const cy = box.y + box.height / 2;

// 1) 轻点
await page.touchscreen.tap(cx, cy);
await page.waitForTimeout(6000);
console.log('轻点后', JSON.stringify(await state()));

// 2) 拖拽
await page.mouse.move(cx, cy);
await page.mouse.down();
for (let i = 1; i <= 6; i++) {
  await page.mouse.move(cx - i * 12, cy - i * 2);
  await page.waitForTimeout(30);
}
await page.waitForTimeout(600);
const mid = await state();
await page.mouse.up();
await page.waitForTimeout(600);
console.log('拖拽中', JSON.stringify({ now: mid.now, x: Math.round(mid.x) }));

// 3) 点空白 → 走过去
const before = await state();
await page.touchscreen.tap(330, 300);
await page.waitForTimeout(1500);
const after = await state();
console.log('点空白 x:', Math.round(before.x), '→', Math.round(after.x), '动作', after.now);

// 4) 长按出菜单
await page.mouse.move(cx, cy);
await page.mouse.down();
await page.waitForTimeout(700);
await page.mouse.up();
await page.waitForTimeout(400);
console.log('长按弹层', await page.evaluate(() => document.getElementById('sheet').classList.contains('on')));
await page.screenshot({ path: p('.tmp', 'web-sheet.png') });

// 5) 分类切换 + 选动作
await page.locator('#cats .cat').nth(10).click();
await page.waitForTimeout(250);
const catItems = await page.locator('#grid .item').count();
const pick = await page.locator('#grid .item').first().textContent();
await page.locator('#grid .item').first().click();
await page.waitForTimeout(2600);
console.log('分类条目', catItems, '选中', pick.trim(), '→ 当前', (await state()).now);

// 6) 设置页
await page.locator('#btnSet').click();
await page.waitForTimeout(400);
await page.screenshot({ path: p('.tmp', 'web-settings.png') });
await page.locator('#segBg button[data-bg="ocean"]').click();
await page.waitForTimeout(300);
const bgOk = await page.evaluate(() => document.body.className);
await page.locator('#rSize').evaluate((el) => {
  el.value = 560;
  el.dispatchEvent(new Event('input', { bubbles: true }));
});
await page.waitForTimeout(300);
console.log('背景切换 ->', bgOk || '(default)', '尺寸', (await state()).size);

await page.locator('#mask').click({ position: { x: 195, y: 70 } });
await page.waitForTimeout(800);
await page.screenshot({ path: p('.tmp', 'web-main.png') });

console.log('控制台错误', errs.length ? errs.slice(0, 5) : '无');
await browser.close();
