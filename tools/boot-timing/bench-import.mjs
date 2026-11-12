// 微基准：单独量"动态 import 某包"的代价 —— 也就是把它从静态 import 改成懒加载能省下的启动时间。
// 用法（放在内核树里跑，保证裸名解析与插件一致）：
//   adb push bench-import.mjs <payload>/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-web/
//   adb shell "cd <…>/dsh-tool-web && <payload>/runtime/bin/node bench.mjs turndown fontkit"
//
// 2026-10-07 实测（模拟器 community，翻译执行）：
//   import turndown  ≈ 984-1,088 ms   （turndown → @mixmark-io/domino 52 文件 / 533 KB）
//   import fontkit   ≈ 1,330-1,577 ms （fontkit → brotli 804 KB）
//   NODE_COMPILE_CACHE 热缓存后几乎无变化（turndown 984→990、fontkit 1458→1330）→ 字节码缓存对这类
//   "解析+链接+顶层求值"型开销帮助有限，别指望它。
//
// 为什么值得量：这两个包分别被 dsh-tool-web（静态 import turndown）和 libreoffice-kit
// （`import "fontkit"` 副作用式导入）在**插件入口**就拉进来，而它们只在实际抓网页/生成文档时才需要。
const targets = process.argv.slice(2);
for (const t of targets) {
  const t0 = Date.now();
  try {
    await import(t);
    console.log(`${String(Date.now() - t0).padStart(6)} ms  ${t}`);
  } catch (e) {
    console.log(`   ERR   ${t}: ${e && e.message}`);
  }
}
