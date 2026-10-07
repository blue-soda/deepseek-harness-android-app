// 真正的 ESM 语法检查（只解析、不执行）。
// 为什么不用 `node --check`：它**不解析 ESM** —— 对 .mjs / type:module 的 .js 会按 CJS 解析，
// 于是给出假阴性（漏掉真错误）或假阳性（把 export 当非法）。2026-10-07 的事故就栽在这上面。
// 用法: node --experimental-vm-modules tools/esm-parse-check.mjs <file...>
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

let bad = 0;
for (const file of process.argv.slice(2)) {
  try {
    // 构造 SourceTextModule 会做完整的语法解析，但不会执行模块体
    new vm.SourceTextModule(readFileSync(file, 'utf8'), { identifier: file });
    console.log('PARSE-OK  ', file);
  } catch (e) {
    bad++;
    console.log('PARSE-FAIL', file, '→', (e && e.message) || e);
  }
}
process.exit(bad ? 1 : 0);
