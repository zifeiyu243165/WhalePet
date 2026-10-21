// 透明底排查小工具：把 stage 刷成红/黑/白三种纯色，截图看角色之外有没有被"底"污染。
// 用法：node scripts/check-alpha.mjs   输出到 .tmp/alpha-*.png
import fs from 'node:fs';
import { p, fileUrl, launch, ensureBuilt } from './_env.mjs';

ensureBuilt();
const OUT = p('.tmp');
fs.mkdirSync(OUT, { recursive: true });

const browser = await launch();
try {
  // 悬浮窗真实尺寸 96dp @3x
  const ctx = await browser.newContext({ viewport: { width: 96, height: 113 }, deviceScaleFactor: 3 });
  const page = await ctx.newPage();
  await page.goto(fileUrl('android/assets/pet.html'), { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1800);

  for (const [bg, name] of [['#ff0000', 'red'], ['#000000', 'black'], ['#ffffff', 'white']]) {
    await page.evaluate((c) => { document.getElementById('stage').style.background = c; }, bg);
    await page.waitForTimeout(150);
    await page.screenshot({ path: `${OUT}/alpha-${name}.png` });
  }
  console.log('已输出 .tmp/alpha-red.png / alpha-black.png / alpha-white.png');
  console.log('角色之外若出现整块底色，说明上屏的那一层被当成了不透明纹理');
} finally {
  await browser.close();
}
