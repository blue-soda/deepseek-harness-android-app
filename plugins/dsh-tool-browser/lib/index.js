/**
 * AI 浏览器插件（v1.19.0）—— 给 AI 一套"能真正操作网页"的工具。
 *
 * 设计要点（我们自己的实现，见 tmp-diag/v1180/browser-design.md）：
 *   · 主通道 = **结构化 DOM 快照 + 稳定 ref 寻址**；**不是**截图 OCR。
 *   · ref 是元素身份指纹（跨快照保持稳定），不是位置编号 → 上一轮拿到的 ref 这一轮还能用。
 *   · `browser_snapshot{since}` 支持**真差分**（added/removed/changed/unchangedCount），没变就"零差异"。
 *   · `browser_find` 按文本/角色检索，命中的元素**自动打 ref** → 找到即可点，不必先拍全量。
 *   · 动作后回 `changed` + 最小差异 → AI 当场知道"这一下点没点动"。
 *   · 截图（本插件暂不暴露）只是给人看的证据，**任何定位都不依赖它**。
 *
 * 与 App 侧的关系：POST http://127.0.0.1:<APP_NOTIFY_PORT>/browser，带 X-DSH-Token
 * （令牌由 App 启动引擎时经 env APP_LOCAL_TOKEN 注入；本地服务在 v1.18.0 起统一鉴权）。
 *
 * 注意（本项目血泪）：DSH 工具输出校验是 additionalProperties:false ——
 * execute 返回的每个字段都必须在 output.schema 里显式声明，否则整次调用被判非法输出。
 * 因此这里一律用 pick() 把 App 的返回**收敛成声明的字段集**。
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { request as httpRequest } from "node:http";

const name = "tool-browser";
const inject = ["tools"];

const port = () => parseInt(process.env.APP_NOTIFY_PORT || "3081", 10);
const token = () => process.env.APP_LOCAL_TOKEN || "";

/** 调 App 的 /browser 路由（本地服务已鉴权，必须带令牌）。 */
function browserOp(op, args, timeoutMs) {
  const payload = JSON.stringify({ op, args: args || {}, timeout_ms: timeoutMs || 30000 });
  return new Promise((resolve) => {
    const req = httpRequest({
      host: "127.0.0.1",
      port: port(),
      path: "/browser",
      method: "POST",
      timeout: (timeoutMs || 30000) + 5000,
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(payload),
        // v1.18.0：本地服务统一鉴权
        "X-DSH-Token": token()
      }
    }, (res) => {
      let d = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { d += c; if (d.length > 4 * 1024 * 1024) req.destroy(); });
      res.on("end", () => {
        try {
          resolve(JSON.parse(d || "{}"));
        } catch (e) {
          resolve({ ok: false, reason: "parse-failed", error: "App 响应解析失败: " + String(d).slice(0, 160) });
        }
      });
    });
    req.on("error", () => resolve({ ok: false, reason: "app-unreachable", error: "App 本地服务不可用（请先启动 DeepSeek Harness）" }));
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, reason: "timeout", error: "App 本地服务超时" }); });
    req.write(payload);
    req.end();
  });
}

/** 只保留声明过的字段（additionalProperties:false 的硬要求）。 */
function pick(src, keys) {
  const out = {};
  for (const k of keys) if (src && src[k] !== undefined) out[k] = src[k];
  if (out.ok === undefined) out.ok = false;
  return out;
}

// ── v1.19.6 · A2：ref 属于哪个页签（原生 stale-tab 闸门的插件侧一半） ──────────
// 原生只在"动作带的 tabId ≠ 激活页签"时拒绝；插件是唯一知道"这个 ref 是 AI 在哪一页签拿到的"的一方，
// 所以由插件自动带上 —— 否则 AI 忘了带就会**静默点到另一个页签**（ref 是内容指纹，同名元素会撞 ref）。
const refTab = new Map();
let curTab = "";
function noteTab(t) { if (t) curTab = String(t); }
function rememberRefs(nodes, tab) {
  if (!tab || !nodes) return;
  for (const n of nodes) if (n && n.ref) refTab.set(String(n.ref), String(tab));
}
/** 动作该带的 tabId：显式参数优先；否则用"这个 ref 最近一次出现在哪个页签"。 */
function tabFor(ref, explicit) {
  if (explicit) return String(explicit);
  return refTab.get(String(ref || "")) || "";
}

const NODE_KEYS = ["ref", "role", "name", "bounds", "inView", "disabled", "dup"];
const COMMON_ERR = { ok: { type: "boolean" }, reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" } };

function errText(v) {
  return "❌ " + (v.error || v.reason || "失败") + (v.hint ? "\n提示：" + v.hint : "");
}

/** 节点渲染成一行：`r7f3a2 button "登录" [in-view]`。 */
function renderNodes(nodes, limit) {
  const lines = [];
  for (const n of (nodes || []).slice(0, limit)) {
    const flags = (n.inView ? " [in-view]" : "") + (n.disabled ? " [disabled]" : "") + (n.dup ? " [同名多个]" : "");
    lines.push(`${n.ref} ${n.role || "?"} "${String(n.name || "").slice(0, 40)}"${flags}`);
  }
  return lines;
}

export function apply(ctx) {
  // 1) 能力自述
  ctx.tools.register(defineTool({
    name: "browser_caps",
    description: "查看 AI 浏览器的能力与限制（引擎类型、预算、ref 方案、已知降级）。第一次用浏览器前调一次。",
    // v1.19.0 坑：parameters 是**属性映射表**（属性名 → 值 schema），不是 JSON Schema
    parameters: {},
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, surface: { type: "string" }, engine: { type: "string" },
          webviewUa: { type: "string" }, maxNodes: { type: "number" }, maxName: { type: "number" },
          maxText: { type: "number" }, refScheme: { type: "string" },
          viewport: { type: "object", additionalProperties: true },
          notes: { type: "array", items: { type: "string" } },
          reason: { type: "string" }, error: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok
          ? `AI 浏览器：${value.engine || "?"}｜ref 方案：${value.refScheme || "?"}｜预算：${value.maxNodes} 节点 / ${value.maxText} 字符\n` +
            (value.notes || []).map((n) => "· " + n).join("\n")
          : errText(value)
      }],
    },
    async execute() {
      return pick(await browserOp("caps", {}, 8000), ["ok", "surface", "engine", "webviewUa", "maxNodes", "maxName", "maxText", "refScheme", "viewport", "notes", "reason", "error"]);
    }
  }));

  // 2) 打开网页
  ctx.tools.register(defineTool({
    name: "browser_open",
    description:
      "打开一个网页并等它加载完成（只允许 http/https；禁止本机回环地址）。" +
      "打开后请用 browser_snapshot 看结构、或 browser_find 直接找元素 —— 不要靠截图猜。",
    parameters: {
      url: { type: "string", required: true, description: "要打开的 http(s) 地址" },
      newTab: { type: "boolean", description: "true = 在新页签里打开（默认 false：在当前页签导航）" }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, url: { type: "string" }, title: { type: "string" },
          pageGeneration: { type: "number" }, loadState: { type: "string" }, ready: { type: "boolean" },
          reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" }, tabId: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok
          ? `已打开（页签 ${value.tabId || "?"}）：${value.title || "(无标题)"}\n${value.url}\n状态：${value.loadState}${value.ready ? "" : "（可能还在加载）"}\n→ 下一步：browser_find("要点的东西") 或 browser_snapshot`
          : errText(value)
      }],
    },
    async execute(args) {
      const v = pick(await browserOp("open",
        { url: String(args.url || ""), newTab: !!(args && args.newTab) }, 40000),
        ["ok", "tabId", "url", "title", "pageGeneration", "loadState", "ready", "reason", "error", "hint"]);
      noteTab(v.tabId);
      return v;
    }
  }));

  // 2b) 页签管理（v1.19.6 · A2）
  ctx.tools.register(defineTool({
    name: "browser_tabs",
    description:
      "管理浏览器页签：list 列出现有页签 / new 开新页签 / switch 切换 / close 关闭。" +
      "每个页签有**自己独立的一套 ref、快照与页面代次**；带 tabId 的动作必须落在当前激活页签上，" +
      "否则会回 stale-tab（防止在 A 页签拿的 ref 点到 B 页签上）。",
    parameters: {
      op: { type: "string", required: true, description: "list | new | switch | close" },
      tabId: { type: "string", description: "switch/close 用；close 省略=关当前页签" },
      url: { type: "string", description: "new 时可带：新页签要打开的 http(s) 地址" },
      front: { type: "boolean", description: "new 时是否切到新页签（默认 true）" }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" },
          count: { type: "number" }, activeTabId: { type: "string" }, tabId: { type: "string" },
          newTabId: { type: "string" }, closed: { type: "string" },
          url: { type: "string" }, title: { type: "string" }, loadState: { type: "string" },
          pageGeneration: { type: "number" }, hasSnapshot: { type: "boolean" },
          tabs: { type: "array", items: { type: "object", additionalProperties: true } }
        }
      },
      render: (_args, value) => {
        if (!value.ok) return [{ type: "text", text: errText(value) }];
        if (value.tabs) {
          const lines = value.tabs.map((t) =>
            `${t.active ? "▶" : " "} ${t.tabId}  ${t.loadState || "?"}  ${String(t.title || "(无标题)").slice(0, 40)}\n     ${t.url || ""}`);
          return [{ type: "text", text: `页签 ${value.count} 个（当前 ${value.activeTabId}）：\n` + lines.join("\n") }];
        }
        const what = value.closed ? `已关闭 ${value.closed}` : `当前页签 ${value.activeTabId}`;
        return [{ type: "text", text: `${what}（共 ${value.count} 个）\n${value.url || ""}` }];
      }
    },
    async execute(args) {
      const op = String((args && args.op) || "").toLowerCase();
      const a = {};
      if (args && args.tabId) a.tabId = String(args.tabId);
      if (args && args.url) a.url = String(args.url);
      if (args && args.front !== undefined) a.front = !!args.front;
      if (op === "list") {
        const v = pick(await browserOp("tabs.list", {}, 15000),
          ["ok", "tabs", "count", "activeTabId", "reason", "error", "hint"]);
        noteTab(v.activeTabId);
        return v;
      }
      if (op === "new") {
        const v = pick(await browserOp("tabs.new", a, 20000),
          ["ok", "tabId", "activeTabId", "count", "url", "loadState", "reason", "error", "hint"]);
        noteTab(v.activeTabId || v.tabId);
        return v;
      }
      if (op === "switch" || op === "close") {
        const v = pick(await browserOp("tabs." + op, a, 20000),
          ["ok", "closed", "count", "activeTabId", "newTabId", "url", "title", "loadState",
            "pageGeneration", "hasSnapshot", "reason", "error", "hint"]);
        noteTab(v.activeTabId);
        return v;
      }
      return { ok: false, reason: "bad-op", error: "op 只能是 list / new / switch / close" };
    }
  }));

  // 3) 快照（全量或差分）
  ctx.tools.register(defineTool({
    name: "browser_snapshot",
    description:
      "读取页面结构（**结构化 DOM 快照**，不是截图）：返回语义元素节点表 ref/role/name/bounds/inView/disabled。" +
      "首次调用返回全量；之后传 since=<上次的 pageGeneration> 只返回变化（added/removed/changed），页面没变时极其省 token。" +
      "拿到 ref 后用 browser_click / browser_type 操作。",
    parameters: {
      since: { type: "number", description: "上次快照返回的 pageGeneration；省略=全量" },
      limit: { type: "number", description: "最多渲染多少行（默认 80，仅影响展示）" }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, full: { type: "boolean" }, unchanged: { type: "boolean" },
          tabId: { type: "string" }, url: { type: "string" }, title: { type: "string" },
          pageGeneration: { type: "number" }, nodeCount: { type: "number" },
          nodes: { type: "array", items: { type: "object", additionalProperties: true } },
          added: { type: "array", items: { type: "object", additionalProperties: true } },
          removed: { type: "array", items: { type: "string" } },
          changed: { type: "array", items: { type: "object", additionalProperties: true } },
          unchangedCount: { type: "number" }, truncated: { type: "boolean" },
          viewport: { type: "object", additionalProperties: true }, refScheme: { type: "string" },
          reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" }
        }
      },
      render: (args, value) => {
        if (!value.ok) return [{ type: "text", text: errText(value) }];
        if (value.unchanged) {
          return [{ type: "text", text: `页面没变化（页签 ${value.tabId || "?"}，generation=${value.pageGeneration}，共 ${value.nodeCount} 个可交互元素）` }];
        }
        const limit = Math.max(10, Math.min(200, Number(args && args.limit) || 80));
        if (value.full) {
          const lines = renderNodes(value.nodes, limit);
          const more = (value.nodes || []).length - lines.length;
          return [{
            type: "text",
            text: `快照（全量·页签 ${value.tabId || "?"}）${value.title || ""} ${value.url || ""}\n` +
              `generation=${value.pageGeneration} 节点=${value.nodeCount}\n` + lines.join("\n") +
              (more > 0 ? `\n…（还有 ${more} 个未列出；用 browser_find 缩小范围）` : "")
          }];
        }
        const added = renderNodes(value.added, limit);
        const changed = (value.changed || []).map((c) => `${c.ref} 变化：${(c.fields || []).join("/")}`);
        return [{
          type: "text",
          text: `快照（差分·页签 ${value.tabId || "?"}）generation=${value.pageGeneration} 未变=${value.unchangedCount}\n` +
            (added.length ? "新增：\n" + added.join("\n") + "\n" : "") +
            (changed.length ? "变化：\n" + changed.join("\n") + "\n" : "") +
            ((value.removed || []).length ? "消失：" + value.removed.join(",") : "")
        }];
      },
    },
    async execute(args) {
      const a = {};
      if (args && args.since !== undefined && args.since !== null && args.since >= 0) a.since = Number(args.since);
      const v = pick(await browserOp("snapshot", a, 20000),
        ["ok", "full", "unchanged", "tabId", "url", "title", "pageGeneration", "nodeCount", "nodes",
         "added", "removed", "changed", "unchangedCount", "truncated", "viewport", "refScheme", "reason", "error", "hint"]);
      noteTab(v.tabId);
      rememberRefs(v.nodes, v.tabId);      // 这一批 ref 属于这个页签
      rememberRefs(v.added, v.tabId);
      return v;
    }
  }));

  // 4) 检索式定位
  ctx.tools.register(defineTool({
    name: "browser_find",
    description:
      "在当前页面里按**文本或角色**查找可交互元素，返回少量候选（含 ref）——大页面首选，比拉全量快照省得多。" +
      "命中的元素会自动打 ref，因此 find 之后可以直接 browser_click。",
    parameters: {
      query: { type: "string", required: true, description: "要查找的文字（如“登录”“搜索”）或角色（button/link/输入框）" },
      role: { type: "string", description: "可选：限定角色，如 button / link / textbox" },
      limit: { type: "number", description: "最多返回几个候选（默认 5，上限 20）" }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, query: { type: "string" }, matched: { type: "number" },
          candidates: { type: "array", items: { type: "object", additionalProperties: true } },
          pageGeneration: { type: "number" }, url: { type: "string" }, tabId: { type: "string" },
          reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" }
        }
      },
      render: (_args, value) => {
        if (!value.ok) return [{ type: "text", text: errText(value) }];
        if (!value.candidates || value.candidates.length === 0) {
          return [{ type: "text", text: `没找到「${value.query}」。${value.hint || ""}` }];
        }
        const lines = value.candidates.map((c) => {
          const flags = (c.inView ? " [in-view]" : "") + (c.disabled ? " [disabled]" : "");
          return `${c.ref} ${c.role} "${String(c.name || "").slice(0, 40)}"${flags}`;
        });
        return [{
          type: "text",
          text: `找到 ${value.matched} 个（页签 ${value.tabId || "?"}，显示 ${value.candidates.length} 个）：\n` + lines.join("\n") +
            `\n→ 直接用 browser_click ref=${value.candidates[0].ref}`
        }];
      },
    },
    async execute(args) {
      const a = { query: String(args.query || "") };
      if (args.role) a.role = String(args.role);
      if (args.limit) a.limit = Number(args.limit);
      const v = pick(await browserOp("find", a, 20000),
        ["ok", "query", "matched", "candidates", "pageGeneration", "url", "tabId", "reason", "error", "hint"]);
      noteTab(v.tabId);
      rememberRefs(v.candidates, v.tabId || curTab);
      return v;
    }
  }));

  // 5) 点击
  ctx.tools.register(defineTool({
    name: "browser_click",
    description:
      "按 ref 点击页面元素（看得见时走真原生触摸；承载窗不可见时**退回 JS 点击兜底** —— 返回值里的 `via` 会说明走的哪条：touch / js / touch+js）。必须先用 browser_snapshot 或 browser_find 拿到 ref；" +
      "ref 属于拿到它的那个页签：如果那个页签不是当前激活页签，会回 **stale-tab**（防点错页面）——" +
      "先 browser_tabs{op:'switch',tabId} 切过去再点。返回里 changed=true 表示页面确实变了，false 表示没点动（别重复点，先重新快照）。",
    parameters: {
      ref: { type: "string", required: true, description: "元素的 ref（形如 r7f3a2 或 r7f3a2-2）" },
      tabId: { type: "string", description: "可选：这个 ref 属于哪个页签（省略=插件按快照来源自动带；带了非激活页签会被 stale-tab 拒绝，先 browser_tabs{op:'switch'}）" }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, ref: { type: "string" }, changed: { type: "boolean" },
          url: { type: "string" }, title: { type: "string" }, pageGeneration: { type: "number" },
          loadState: { type: "string" }, clickedAt: { type: "array", items: { type: "number" } },
          delta: { type: "object", additionalProperties: true },
          reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok
          ? (value.changed
              ? `已点击 ${value.ref}，页面有变化 → 现在在：${value.title || ""} ${value.url || ""}`
              : `已点击 ${value.ref}，但**页面没有变化**${value.hint ? "\n" + value.hint : ""}`)
          : errText(value)
      }],
    },
    async execute(args) {
      const ca = { ref: String(args.ref || "") };
      const ct = tabFor(ca.ref, args && args.tabId);
      if (ct) ca.tabId = ct;                       // ← 闸门靠它生效（见文件头说明）
      return pick(await browserOp("click", ca, 20000),
        ["ok", "ref", "changed", "url", "title", "pageGeneration", "loadState", "clickedAt", "delta", "reason", "error", "hint"]);
    }
  }));

  // 6) 输入
  ctx.tools.register(defineTool({
    name: "browser_type",
    description:
      "往指定 ref 的输入框写文字（默认先清空再写；replace=false 则追加）。" +
      "写完后通常配合 browser_press 或 browser_click 提交。",
    parameters: {
      ref: { type: "string", required: true, description: "输入框的 ref" },
      text: { type: "string", required: true, description: "要写入的文字（空串=清空）" },
      tabId: { type: "string", description: "可选：这个 ref 属于哪个页签（省略=插件自动带；同 browser_click 的 stale-tab 规则）" },
      replace: { type: "boolean", description: "是否先清空（默认 true）" }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, ref: { type: "string" }, value: { type: "string" },
          changed: { type: "boolean" }, pageGeneration: { type: "number" },
          reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok ? `已写入 ${value.ref}，当前值："${String(value.value || "").slice(0, 60)}"` : errText(value)
      }],
    },
    async execute(args) {
      const a = { ref: String(args.ref || ""), text: String(args.text == null ? "" : args.text) };
      if (args.replace !== undefined) a.replace = !!args.replace;
      const tt = tabFor(a.ref, args && args.tabId);
      if (tt) a.tabId = tt;
      return pick(await browserOp("type", a, 20000),
        ["ok", "ref", "value", "changed", "pageGeneration", "reason", "error", "hint"]);
    }
  }));

  // 7) 取正文
  ctx.tools.register(defineTool({
    name: "browser_read",
    description: "读取页面正文文本（不是 OCR：直接读 DOM 文本）。给定 ref 则只读该元素的文本。超长会自动截断。",
    parameters: {
      ref: { type: "string", description: "可选：只读这个元素的文本" },
      tabId: { type: "string", description: "可选：这个 ref 属于哪个页签（省略=插件自动带；同 browser_click 的 stale-tab 规则）" }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, ref: { type: "string" }, text: { type: "string" },
          chars: { type: "number" }, truncated: { type: "boolean" },
          reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok ? (value.text || "(空)") + (value.truncated ? "\n…（已截断）" : "") : errText(value)
      }],
    },
    async execute(args) {
      const a = {};
      if (args && args.ref) a.ref = String(args.ref);
      const rt = tabFor(a.ref, args && args.tabId);
      if (rt) a.tabId = rt;
      return pick(await browserOp("read", a, 20000),
        ["ok", "ref", "text", "chars", "truncated", "reason", "error", "hint"]);
    }
  }));

  // 8) 历史/重载/关闭
  ctx.tools.register(defineTool({
    name: "browser_nav",
    description: "浏览器的导航动作：back（后退）/ forward（前进）/ reload（重载）/ close（关掉浏览器释放内存）。",
    parameters: {
      op: { type: "string", required: true, enum: ["back", "forward", "reload", "close"], description: "导航动作" }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, op: { type: "string" }, url: { type: "string" }, title: { type: "string" },
          pageGeneration: { type: "number" }, loadState: { type: "string" }, closed: { type: "boolean" },
          reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok
          ? (value.closed ? "浏览器已关闭" : `${value.op} 完成 → ${value.title || ""} ${value.url || ""}`)
          : errText(value)
      }],
    },
    async execute(args) {
      return pick(await browserOp("nav", { op: String(args.op || "reload") }, 25000),
        ["ok", "op", "url", "title", "pageGeneration", "loadState", "closed", "reason", "error", "hint"]);
    }
  }));

  // 9) 滚动
  ctx.tools.register(defineTool({
    name: "browser_scroll",
    description: "滚动页面（down/up/top/bottom）。滚动后如需操作新出现的元素，先 browser_snapshot{since} 或 browser_find。",
    parameters: {
      direction: { type: "string", required: true, enum: ["down", "up", "top", "bottom"], description: "滚动方向" },
      amount: { type: "number", description: "像素（默认 700）" }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, scrollY: { type: "number" }, innerHeight: { type: "number" },
          docHeight: { type: "number" }, reason: { type: "string" }, error: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok ? `已滚动：位置 ${value.scrollY} / 页高 ${value.docHeight}` : errText(value)
      }],
    },
    async execute(args) {
      const a = { direction: String(args.direction || "down") };
      if (args.amount) a.amount = Number(args.amount);
      return pick(await browserOp("scroll", a, 10000),
        ["ok", "scrollY", "innerHeight", "docHeight", "reason", "error"]);
    }
  }));
}

// ⚠ 这一行必须有（cordis 的插件契约）：`apply` 已由上面的 `export function apply` 导出，
// 这里补 `inject` 与 `name`；漏了会在激活时抛 "cannot get property \"tools\" without inject"
// → browser_* 工具全部注册不上（v1.19.6 起是 10 个），而且只在引擎日志里留一行 warning（v1.19.0 真机踩过一次）。
export { inject, name };
