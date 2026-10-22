// 回归：收起动作面板、窗口缩回 96dp 的整个过程里，角色不允许出现位移帧。
//
// 曾经的"从左上角闪回原位"是这样来的：窗口从全屏缩到 96dp 的那几帧，页面还在用
// 大窗口的屏幕坐标排版，角色被挤出小窗；当时用一段 @media 把她的 left/top 临时
// 按到 0/0 顶上，于是先出现在窗口左上角、下一帧再跳回原位。
// 现在的顺序是 petPreShrink()（藏起来）→ 等两帧上屏 → 原生改窗口几何 → petMenuClosed()。
// 这里逐帧采样，断言：只要视口已经变窄，角色要么不可见，要么就在正确位置。
//
// 用法：node scripts/test-menu-shrink.mjs
import assert from 'node:assert/strict';
import { fileUrl, launch, ensureBuilt } from './_env.mjs';

ensureBuilt();

const CHAR_W = 96;      // 与 mock 里 getConfig 返回的 w 一致
const WIDE = { width: 360, height: 780 };

const b = await launch();
try {
  const page = await b.newPage({ viewport: { width: CHAR_W, height: 113 }, deviceScaleFactor: 3 });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(() => {
    window.Android = {
      getConfig: () => JSON.stringify({ w: 96, roam: false, variant: 'normal' }),
      saveConfig() {}, ready() {}, assetFail() {},
      closeMenu() {},              // 收起：测试自己驱动后续步骤
      shrinkNow() { window.__shrinkAsked = (window.__shrinkAsked || 0) + 1; }
    };
  });
  await page.goto(fileUrl('android/assets/pet.html'));
  await page.waitForTimeout(1200);

  // 逐帧采样：视口宽 / 可见性 / 角色左上角
  await page.evaluate(() => {
    window.__rec = [];
    (function tick() {
      const p = document.getElementById('pet');
      const r = p.getBoundingClientRect();
      window.__rec.push({
        w: Math.round(window.innerWidth),
        vis: getComputedStyle(p).visibility,
        x: Math.round(r.x * 10) / 10,
        y: Math.round(r.y * 10) / 10
      });
      if (window.__rec.length < 300) requestAnimationFrame(tick);
    })();
  });

  // 长按展开 → 关掉面板 → 原生那套缩窗时序
  await page.evaluate(() => petPrepareMenu(210, 320));
  await page.setViewportSize(WIDE);
  await page.evaluate(() => petOpenMenu());
  await page.waitForTimeout(400);

  await page.evaluate(() => petCloseMenu());
  await page.waitForTimeout(360);                       // 面板滑下去
  await page.evaluate(() => petPreShrink());            // 原生：先藏角色
  assert.equal(await page.$eval('#pet', (e) => getComputedStyle(e).visibility), 'hidden');
  await page.waitForTimeout(50);                        // 藏完等两帧上屏
  assert.ok(await page.evaluate(() => (window.__shrinkAsked || 0) > 0), '页面应回报可以缩窗了');
  await page.setViewportSize({ width: CHAR_W, height: 113 });   // 原生：改窗口几何
  await page.waitForTimeout(50);
  await page.evaluate(() => petMenuClosed());           // 原生：缩到位后解锁
  await page.waitForTimeout(200);

  const rec = await page.evaluate(() => window.__rec);
  const narrow = rec.filter((f) => f.w <= CHAR_W + 2);
  const shown = narrow.filter((f) => f.vis === 'visible');
  assert.ok(narrow.length > 3, `窄视口帧太少，采样可能没覆盖到：${narrow.length}`);
  assert.ok(narrow.some((f) => f.vis === 'hidden'), '缩窗过渡期角色应处于隐藏状态');

  // 核心断言：视口变窄之后，任何可见帧都必须贴在窗口原位，不允许有中间位置
  const stray = shown.filter((f) => Math.abs(f.x) > 0.6 || Math.abs(f.y) > 0.6);
  assert.deepEqual(stray, [], `缩窗过程中出现了位移帧：${JSON.stringify(stray.slice(0, 5))}`);

  // 收尾：角色回来且可见，动作没被重播
  const last = narrow.at(-1);
  assert.equal(last.vis, 'visible');
  assert.ok(Math.abs(last.x) < 0.6 && Math.abs(last.y) < 0.6, `收尾位置不对：${JSON.stringify(last)}`);
  assert.equal(await page.evaluate(() => document.body.classList.contains('menu')), false);
  assert.equal(await page.evaluate(() => document.body.classList.contains('menu-shrink')), false);
  assert.deepEqual(errors, []);

  console.log(`PASS 缩窗过渡 ${narrow.length} 帧内角色无位移（隐藏 ${narrow.filter((f) => f.vis === 'hidden').length} 帧）`);
} finally {
  await b.close();
}
