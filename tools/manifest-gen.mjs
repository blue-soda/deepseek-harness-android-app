// 生成内核树「清单式一致性证明」用的 manifest（自修复功能 ① 的随包数据）。
//
// 产物：assets/kernel-manifest.tsv
//   前两行以 # 开头（版本与列说明），其后每行： 相对路径 \t 字节数 \t sha256(小写hex)
//   相对路径以 dshroot/ 开头，与 payload 内条目名、以及运行时内核树根（…/payload/dshroot）
//   完全对齐 —— App 侧只需 new File(kernelRoot, rel 去掉前缀) 就能定位。
//
// 为什么用 Node 而不是 shell：Git Bash(MSYS) 会把传给原生 sha256sum/stat 的 /d/... 路径做
// 转换，实测生成了 `*dshroot/...` 这种被毁掉的名字（本轮踩到）。Node 在 Windows/Android 上
// 行为一致，且真机自带 node —— 同一个脚本既能在构建期跑，也能在设备上跑。
//
// 用法: node gen-kernel-manifest.mjs <内核树>

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const tree = process.argv[2];
const out = process.argv[3];
if (!tree || !out) { console.error('用法: node gen-kernel-manifest.mjs <内核树目录> <输出tsv>'); process.exit(2); }

const root = path.resolve(tree);
const st = fs.statSync(root, { throwIfNoEntry: false });
if (!st || !st.isDirectory()) { console.error('!! 不是目录: ' + root); process.exit(1); }
if (!fs.existsSync(path.join(root, 'lib'))) { console.error('!! 看起来不是内核树（缺 lib/）: ' + root); process.exit(1); }

// 前缀固定为 dshroot：runtime 侧树根就是 <payload>/dshroot，清单里的相对路径必须按它来写
const PREFIX = 'dshroot';

const t0 = Date.now();
const lines = ['# dshroot kernel manifest v1', '# 每行: 相对路径<TAB>字节数<TAB>sha256（小写 hex）'];
let n = 0, total = 0;

function walk(dir) {
  const ents = fs.readdirSync(dir, { withFileTypes: true });
  ents.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)); // LC_ALL=C 等价的稳定顺序
  for (const e of ents) {
    const abs = path.join(dir, e.name);
    if (e.isSymbolicLink()) continue;      // 软链不进清单（与 payload 打包行为一致：不跟链）
    if (e.isDirectory()) { walk(abs); continue; }
    if (!e.isFile()) continue;
    const rel = PREFIX + '/' + path.relative(root, abs).split(path.sep).join('/');
    const size = fs.statSync(abs).size;
    const hash = crypto.createHash('sha256').update(fs.readFileSync(abs)).digest('hex');
    lines.push(rel + '\t' + size + '\t' + hash);
    n++; total += size;
  }
}
walk(root);

fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
fs.writeFileSync(out, lines.join('\n') + '\n', 'utf8');
const bytes = fs.statSync(out).size;
console.log(`  kernel-manifest.tsv: ${n} 个文件, ${(bytes / 1024).toFixed(0)} KB（树合计 ${(total / 1024 / 1024).toFixed(1)} MB, 用时 ${((Date.now() - t0) / 1000).toFixed(1)}s）`);
