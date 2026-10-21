// 生成两个可直接打开的页面：
//   android/assets/pet.html   （悬浮窗里跑的页面，构建 APK 前必须先有）
//   web/index.html            （手机浏览器版单文件页面）
import { buildPetHtml, buildWebIndex, p } from './_env.mjs';
import fs from 'node:fs';

const petBytes = buildPetHtml();
const webBytes = buildWebIndex();
const kb = (n) => (n / 1024).toFixed(1);

console.log(`android/assets/pet.html  ${kb(petBytes)} KB`);
console.log(`web/index.html           ${kb(webBytes)} KB`);

const total = JSON.parse(fs.readFileSync(p('web', 'catalog.json'), 'utf8')).total;
console.log(`已内联 ${total} 条动作目录`);
