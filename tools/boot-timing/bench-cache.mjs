// 直接回答"慢在 node 没缓存，还是慢在 DSH 的插件挂载方式"：
// 1) 裸 node 启动耗时（node 本身）
// 2) 冷 import 一个重包（cuo 解析+链接+求值）
// 3) 同一 import 在 NODE_COMPILE_CACHE 热/冷下的差异（字节码缓存到底有没有用）
// 4) import cordis + 一个真实插件的入口（挂载方式的成本）
const t = (label, fn) => { const t0 = Date.now(); return Promise.resolve(fn()).then(v => ({ label, ms: Date.now() - t0, v })); };

const rows = [];
rows.push(await t("node:fs（内置模块）", () => import("node:fs")));
rows.push(await t("zod", () => import("zod")));
rows.push(await t("js-yaml", () => import("js-yaml")));
rows.push(await t("@deepseek-ai/cordis", () => import("@deepseek-ai/cordis")));
rows.push(await t("@deepseek-ai/dsh-app-boot", () => import("@deepseek-ai/dsh-app-boot")));
const cc = process.env.NODE_COMPILE_CACHE || "(off)";
for (const r of rows) console.log(`${String(r.ms).padStart(6)} ms  ${r.label}   [compile-cache=${cc}]`);
