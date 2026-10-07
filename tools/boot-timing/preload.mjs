/**
 * 引擎启动分段计时 —— 预加载入口。
 *
 * ⚠ 上一版用 `module.register()`（**跨线程**钩子）测出来被严重污染：
 *   主线程 profile 里 `makeSyncRequest` 自耗 11.2 s、`waitForWorker` 1.6 s —— 那是钩子线程
 *   同步通信的开销，不是引擎的成本。Node 26 提供了**同线程同步**的 `module.registerHooks()`，
 *   改用它：量到的就是主线程真实时间线，几乎没有额外同步成本。
 *
 * 用法：node --import <本文件> <引擎入口> …（或用 boot-run.sh 的 hooks 模式）
 */
import { register, registerHooks } from 'node:module';
import { writeFileSync, appendFileSync } from 'node:fs';

const t0 = Date.now();
process.env.DSH_TIMER_T0 = String(t0);

const LOG = process.env.DSH_TIMER_LOG || '/data/local/tmp/boot-hooks.log';
const SUMMARY = process.env.DSH_TIMER_SUMMARY || '/data/local/tmp/boot-summary.json';
const marks = [{ name: 'preload-imported', t: 0 }];
let seq = 0;

function logLine(ms, size, url) {
  try { appendFileSync(LOG, `${Date.now()}\t${ms.toFixed(2)}\t${size}\t${++seq}\t${url}\n`); } catch { /* 忽略 */ }
}

// 优先用同线程同步钩子（Node ≥22.15 / 26 稳定）
let mode = 'none';
try {
  if (typeof registerHooks === 'function') {
    registerHooks({
      load(url, context, nextLoad) {
        const t = process.hrtime.bigint();
        const r = nextLoad(url, context);           // 同步
        const ms = Number(process.hrtime.bigint() - t) / 1e6;
        let size = 0;
        try {
          if (r && r.source) size = typeof r.source === 'string' ? Buffer.byteLength(r.source) : r.source.byteLength;
        } catch { /* 忽略 */ }
        logLine(ms, size, url);
        return r;
      }
    });
    mode = 'registerHooks(sync, 同线程)';
  } else {
    register(new URL('./hooks.mjs', import.meta.url));
    mode = 'register(跨线程，旧版回退)';
  }
} catch (e) {
  mode = 'hooks 注册失败: ' + (e && e.message);
}
marks.push({ name: 'hooks-mode:' + mode, t: Date.now() - t0 });

function writeSummary(tag) {
  try {
    writeFileSync(SUMMARY, JSON.stringify({
      t0, pid: process.pid, node: process.version, argv: process.argv, execArgv: process.execArgv,
      hooksMode: mode, endTag: tag,
      marks: marks.concat([{ name: 'end:' + tag, t: Date.now() - t0 }])
    }, null, 1));
  } catch { /* 忽略 */ }
}

process.on('beforeExit', () => writeSummary('beforeExit'));
for (const sig of ['SIGTERM', 'SIGINT']) {
  try {
    process.on(sig, () => {
      marks.push({ name: 'signal:' + sig, t: Date.now() - t0 });
      writeSummary(sig);          // ⚠ 必须在 exit 前写，之前那版只在 beforeExit 写 → 被 kill 时丢文件
      process.exit(0);
    });
  } catch { /* 忽略 */ }
}
