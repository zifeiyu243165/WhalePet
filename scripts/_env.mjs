// 公共环境：所有脚本都从这里取路径和浏览器，不再硬编码本机绝对路径。
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import fs from 'node:fs';
import path from 'node:path';

/** 仓库根目录（scripts/ 的上一级） */
export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** 拼绝对路径：p('android', 'assets') */
export const p = (...seg) => path.join(ROOT, ...seg);

/** 转成 file:// URL，供浏览器直接打开本地页面 */
export const fileUrl = (rel) => pathToFileURL(path.resolve(ROOT, rel)).href;

/**
 * 定位 playwright-core：优先 $PET_NODE_MODULES，其次项目自身 node_modules，
 * 最后交给 Node 常规解析（node_modules 在任意父级也能找到）。
 * 惰性加载——只跑生成/构建脚本时不需要装 playwright。
 */
let _playwright = null;
function playwright() {
  if (_playwright) return _playwright;
  const bases = [process.env.PET_NODE_MODULES, ROOT].filter(Boolean);
  for (const base of bases) {
    try {
      _playwright = createRequire(path.join(base, 'noop.js'))('playwright-core');
      return _playwright;
    } catch { /* 试下一个 */ }
  }
  _playwright = createRequire(import.meta.url)('playwright-core');
  return _playwright;
}

/** 依次尝试的浏览器可执行文件；都没有时让 playwright 用自带的 chromium */
const CANDIDATES = [
  process.env.PET_BROWSER,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
].filter(Boolean);

export function browserPath() {
  for (const exe of CANDIDATES) {
    try { if (fs.statSync(exe).isFile()) return exe; } catch { /* 下一个 */ }
  }
  return null;
}

/** 起浏览器，参数是本地页面测透明/自动播放必需的 */
export async function launch(extra = {}) {
  const { chromium } = playwright();
  const exe = browserPath();
  return chromium.launch({
    ...(exe ? { executablePath: exe } : {}),
    args: ['--allow-file-access-from-files', '--autoplay-policy=no-user-gesture-required'],
    ...extra
  });
}

// ---- 页面生成：模板 + 动作目录 → 可直接打开的 html ----

const PLACEHOLDER = '/*__CATALOG__*/';

function inline(tplPath, outPath) {
  const tpl = fs.readFileSync(tplPath, 'utf8');
  if (!tpl.includes(PLACEHOLDER)) throw new Error(`${path.basename(tplPath)} 里没有 ${PLACEHOLDER} 占位`);
  const catalog = fs.readFileSync(p('web', 'catalog.json'), 'utf8')
    .replace(/<\/script/gi, '<\\/script');
  const out = tpl.replace(PLACEHOLDER, catalog);
  fs.writeFileSync(outPath, out);
  return out.length;
}

/** android/assets/pet.html = android/pet.template.html + web/catalog.json */
export const buildPetHtml = () => inline(
  p('android', 'pet.template.html'),
  p('android', 'assets', 'pet.html')
);

/** web/index.html = index.template.html + web/catalog.json */
export const buildWebIndex = () => inline(
  p('web', 'index.template.html'),
  p('web', 'index.html')
);

/** 测试前调用：生成的页面缺了就现场补一份 */
export function ensureBuilt() {
  if (!fs.existsSync(p('android', 'assets', 'pet.html'))) buildPetHtml();
  if (!fs.existsSync(p('web', 'index.html'))) buildWebIndex();
}
