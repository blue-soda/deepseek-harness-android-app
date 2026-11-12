/**
 * 引擎启动分段计时 —— ESM 加载钩子（跑在 module.register 的独立线程里）。
 *
 * 用途：不修改 dshroot 一根毫毛，把**每一次模块加载**记下来：
 *   <毫秒时间戳> \t <load 钩子耗时ms> \t <源码字节数> \t <url>
 * 于是可以：
 *   · 按插件包聚合 → 每个插件加载了多少文件 / 多少字节 / 时间跨度多长；
 *   · 看相邻两次加载之间的"空档" → 那是主线程在做解析/编译/求值（钩子量不到，但差值能推出来）。
 *
 * 注意：钩子耗时≈读文件+转换（I/O）；V8 的解析/编译/求值发生在主线程，不在钩子里。
 */
import { appendFileSync } from 'node:fs';

const LOG = process.env.DSH_TIMER_LOG || '/data/local/tmp/dsh-boot-hooks.log';
const T0 = Number(process.env.DSH_TIMER_T0 || 0);
let seq = 0;

function stamp() {
  return Date.now();
}

export async function load(url, context, nextLoad) {
  const t0 = process.hrtime.bigint();
  const result = await nextLoad(url, context);
  const ms = Number(process.hrtime.bigint() - t0) / 1e6;
  let size = 0;
  try {
    if (result && result.source) {
      size = typeof result.source === 'string' ? Buffer.byteLength(result.source) : result.source.byteLength;
    }
  } catch { /* 忽略 */ }
  try {
    appendFileSync(LOG, `${stamp()}\t${ms.toFixed(2)}\t${size}\t${++seq}\t${url}\n`);
  } catch { /* 日志失败不影响启动 */ }
  return result;
}

export async function resolve(specifier, context, nextResolve) {
  // 只记录解析失败/慢解析没有意义 —— 这里不记录，load 已经覆盖真实文件
  return nextResolve(specifier, context);
}
