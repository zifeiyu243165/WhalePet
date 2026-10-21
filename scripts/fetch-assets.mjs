// 从 npm CDN 把 95 个透明 WebM 动作下载到 android/assets/media/thumb
// 仓库里已经带了这些素材，这个脚本用于重新拉取或校验完整性。
// 用法：node scripts/fetch-assets.mjs
import fs from 'node:fs';
import path from 'node:path';
import { p } from './_env.mjs';

const OUT = p('android', 'assets', 'media', 'thumb');
const CATALOG = JSON.parse(fs.readFileSync(p('web', 'catalog.json'), 'utf8'));

const VERSION = CATALOG.source.split('@').pop();
const BASES = [
  `https://cdn.jsdelivr.net/npm/@luweiyabo/dsh-whale-pet@${VERSION}`,
  `https://fastly.jsdelivr.net/npm/@luweiyabo/dsh-whale-pet@${VERSION}`,
  `https://unpkg.com/@luweiyabo/dsh-whale-pet@${VERSION}`
];

const items = CATALOG.items;
console.log('待下载动作数:', items.length);

const rel = (it) => `assets/thumb/${it.category}/${it.id}.webm`;

async function grab(it, baseIdx = 0) {
  const dest = path.join(OUT, it.category, it.id + '.webm');
  if (fs.existsSync(dest) && fs.statSync(dest).size > 4000) return { it, skip: true };
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  for (let b = baseIdx; b < BASES.length; b++) {
    const url = `${BASES[b]}/${rel(it)}`;
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(60000) });
      if (!r.ok) continue;
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length < 4000) continue;
      fs.writeFileSync(dest, buf);
      return { it, size: buf.length, base: b };
    } catch { /* 换下一个源 */ }
  }
  return { it, fail: true };
}

const t0 = Date.now();
let done = 0, bytes = 0;
const fails = [];
const CONC = 4;
let idx = 0;

async function worker() {
  while (idx < items.length) {
    const it = items[idx++];
    const r = await grab(it);
    if (r.fail) fails.push(it.id);
    else if (!r.skip) bytes += r.size;
    done++;
    if (done % 10 === 0 || done === items.length) {
      const el = ((Date.now() - t0) / 1000).toFixed(0);
      console.log(`  ${done}/${items.length}  ${(bytes / 1048576).toFixed(1)}MB  ${el}s  失败${fails.length}`);
    }
  }
}

await Promise.all(Array.from({ length: CONC }, worker));

if (fails.length) {
  console.log('重试失败的...');
  for (const id of fails) {
    const r = await grab(items.find((x) => x.id === id), 1);
    if (r.fail) console.log('  ✗', id);
  }
}

let total = 0, n = 0;
(function walk(d) {
  for (const f of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, f.name);
    if (f.isDirectory()) walk(full);
    else { total += fs.statSync(full).size; n++; }
  }
})(OUT);
console.log(`\n完成：${n} 个文件，共 ${(total / 1048576).toFixed(1)} MB，耗时 ${((Date.now() - t0) / 1000).toFixed(0)}s`);
if (n !== items.length) process.exitCode = 1;
