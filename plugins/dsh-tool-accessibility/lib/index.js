/**
 * 无障碍屏幕助手插件（v1.7）：给 AI 提供「读屏 + 操作屏幕 + 屏幕截图理解」能力。
 * 与 App 侧 AccessibilityService 通过本地 HTTP（APP_A11Y_PORT，默认 3181）通信。
 * 用户需在系统设置 → 无障碍开启「DeepSeek Harness 屏幕助手」，未开启时返回引导文案。
 *
 * 注意：DSH 工具输出校验为 additionalProperties:false——execute 返回的每个字段
 * 都必须在 output.schema 中显式声明，否则结果会被判定为非法输出（历史踩坑）。
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { get as httpGet, request as httpRequest } from "node:http";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";

const name = "tool-accessibility";
const inject = ["tools"];

const a11yPort = () => parseInt(process.env.APP_A11Y_PORT || "3181", 10);

const GUIDE_TEXT =
  "无障碍服务未开启或不可用：请在手机系统设置 → 无障碍 →（已下载的服务/服务）→ 开启「DeepSeek Harness 屏幕助手」，然后打开 App 后重试。";

// ============================================================================
// v1.18 工具体验改进（A2/A4/A5/A7/A8）
//   A5 手势防呆、A7 截图去重与时间戳、A8 前台判定提示、A4 中文输入标准动作、A2 能力总览
// ============================================================================

/** 宿主包名（壳注入；旧壳回退正式包名）。 */
const hostAppId = () => process.env.DSH_APP_ID || "com.deepseek.harness";

/** 输入事件计数：任何会改变屏幕的操作 +1，用于判断"两次截图之间是否操作过"（A7）。 */
let inputEventSeq = 0;
const noteInputEvent = () => { inputEventSeq += 1; };
/** 上一次 android_see 的记录：内容哈希 / 当时的输入计数 / 时间戳（A7）。 */
let lastShot = { hash: "", seq: 0, at: 0 };

/**
 * 上一次区域截图（A6）的几何 —— 用来把"裁剪图里的像素"换算回屏幕坐标。
 * 契约（原生 /screenshot 返回，这里原样保存）：
 *   screenX = cropX + ix * scaleX ； screenY = cropY + iy * scaleY
 * 整屏截图时 cropX/Y = 0、cropW/H = 屏幕尺寸，所以同一套公式对整屏也成立。
 */
let lastCrop = null;   // { cropX, cropY, cropW, cropH, scaleX, scaleY, imageW, imageH, at }

// ============================================================================
// v1.22 内存前置守卫（真机 Agent 复盘的事故对策）
//   事故链：可用内存低 → 高频全屏截图（每张 ~10MB 位图缓冲）→ 系统回收/重启无障碍服务（"闪断"）
//          → 之后 tap/scroll/type 全部失败 → 模型把失败当成功、甚至误报"已杀进程"。
//   策略：① 截图前读 /proc/meminfo；
//        ② 内存紧张时**拒绝全屏截图**（必须给 select/region 才放行，区域图只有几十 KB）；
//        ③ 限制每分钟全屏截图张数，超了也拒绝并指向更省的替代路径。
//   注意：/proc/meminfo 是**全机**视图（不受 App cgroup 隔离），这正是我们要看的量。
// ============================================================================

const FULL_SHOT_MIN_AVAIL_KB = 400 * 1024;   // 可用内存 < 400MB：拒绝全屏截图
const FULL_SHOT_WARN_AVAIL_KB = 700 * 1024;  // 可用内存 < 700MB：放行但明确告警
const FULL_SHOT_BUDGET_PER_MIN = 12;         // 每分钟全屏截图上限
let fullShotTimes = [];

/** 读 /proc/meminfo，返回 { totalKb, availKb, freeKb }；读不到返回 null。 */
async function readMem() {
  try {
    const txt = await readFile("/proc/meminfo", "utf8");
    const pick = (k) => {
      const m = txt.match(new RegExp("^" + k + ":\\s+(\\d+)", "m"));
      return m ? parseInt(m[1], 10) : 0;
    };
    const total = pick("MemTotal");
    if (!total) return null;
    let avail = pick("MemAvailable");
    if (!avail) avail = pick("MemFree") + pick("Cached");   // 老内核没有 MemAvailable
    return { totalKb: total, availKb: avail, freeKb: pick("MemFree") };
  } catch (e) {
    return null;
  }
}

const mbOf = (kb) => Math.round((kb || 0) / 1024);

/** 全屏截图预算是否已超（顺带清掉一分钟前的记录）。 */
function fullShotBudgetExceeded() {
  const now = Date.now();
  fullShotTimes = fullShotTimes.filter((t) => now - t < 60000);
  return fullShotTimes.length >= FULL_SHOT_BUDGET_PER_MIN;
}

/** 把 /screenshot 的返回值记成 lastCrop（整屏也记，公式统一）。 */
function rememberCrop(v) {
  const num = (x, d) => (typeof x === "number" && isFinite(x) ? x : d);
  const imageW = num(v.imageW, 0), imageH = num(v.imageH, 0);
  if (imageW <= 0 || imageH <= 0) return;
  lastCrop = {
    cropX: num(v.cropX, 0),
    cropY: num(v.cropY, 0),
    cropW: num(v.cropW, num(v.screenW, imageW)),
    cropH: num(v.cropH, num(v.screenH, imageH)),
    scaleX: num(v.scaleX, 1),
    scaleY: num(v.scaleY, 1),
    imageW, imageH,
    at: Date.now()
  };
}

/** 裁剪图内像素 → 屏幕像素；没有可用几何时返回 null（让调用方报错而不是猜）。 */
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function cropToScreen(ix, iy) {
  if (lastCrop === null) return null;
  if (typeof ix !== "number" || typeof iy !== "number" || !isFinite(ix) || !isFinite(iy)) return null;
  return {
    x: Math.round(lastCrop.cropX + ix * lastCrop.scaleX),
    y: Math.round(lastCrop.cropY + iy * lastCrop.scaleY)
  };
}

/**
 * A6：把 ix/iy（上一张 android_see 截图里的像素）换算成屏幕绝对坐标并写进 params。
 * 这样"看图 → 点击"全程由工具做算术，模型只抄图上看到的数字，避免三点换算出错。
 * @returns "" = 正常（或本次没用 ix/iy）；非空 = 需要返回给模型的错误说明。
 */
function applyCropPoint(args, ixKey, iyKey, params, xKey, yKey) {
  const ix = args[ixKey], iy = args[iyKey];
  if (ix === undefined && iy === undefined) return "";
  if (ix === undefined || iy === undefined) return "需要同时提供 " + ixKey + " 与 " + iyKey + "（分别是图内 x/y 像素）";
  const p = cropToScreen(Number(ix), Number(iy));
  if (p === null) {
    return "没有可用的截图几何：先用 android_see（可带 select 或 region 做区域截图）截一次图，再用 " + ixKey + "/" + iyKey + " 传图内像素";
  }
  params[xKey] = String(p.x);
  params[yKey] = String(p.y);
  return "";
}

/**
 * 边缘手势防呆（A5）：分数起点落在系统手势区时给警告。
 * 系统导航/返回手势在屏幕底部与左右边缘，起点落在那里的滑动会被系统吃掉
 * （表现为回桌面/返回/切应用），而工具本身无法分辨"用户就是想从边缘划"。
 */
function edgeGestureWarning(fx, fy) {
  const x = typeof fx === "number" && isFinite(fx) ? fx : null;
  const y = typeof fy === "number" && isFinite(fy) ? fy : null;
  if (y !== null && y > 0.95) {
    return "起点 fy=" + y + " 落在屏幕底部（系统导航/返回手势区），该手势可能被系统拦截（回桌面/返回上一级）。建议起点上移到 fy ≤ 0.9。";
  }
  if (x !== null && x < 0.05) {
    return "起点 fx=" + x + " 落在屏幕左边缘（系统返回手势区），该手势可能被系统拦截。建议起点右移到 fx ≥ 0.1。";
  }
  if (x !== null && x > 0.95) {
    return "起点 fx=" + x + " 落在屏幕右边缘（系统返回手势区），该手势可能被系统拦截。建议起点左移到 fx ≤ 0.9。";
  }
  return "";
}

/** 特权通道可用性（壳注入 env；两个都没有时特权工具不会注册）。 */
const privilegedChannel = () => {
  if (process.env.ROOT_AVAILABLE === "1") return "root(su)";
  if (process.env.SHIZUKU_AVAILABLE === "1") return "shizuku";
  return "";
};

function a11yRequest(path, params, timeoutMs) {
  return new Promise((resolve) => {
    const qs = params
      ? "?" + Object.entries(params).map(([k, v]) =>
          encodeURIComponent(k) + "=" + encodeURIComponent(v)).join("&")
      : "";
    const req = httpGet({
      host: "127.0.0.1",
      port: a11yPort(),
      path: path + qs,
      timeout: timeoutMs || 8000
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { data += c; if (data.length > 262144) req.destroy(); });
      res.on("end", () => resolve(data || "{\"ok\":false,\"error\":\"empty response\"}"));
    });
    req.on("error", () => resolve("{\"ok\":false,\"error\":\"App 本地服务不可用\"}"));
    req.on("timeout", () => { req.destroy(); resolve("{\"ok\":false,\"error\":\"App 本地服务超时\"}"); });
    req.end();
  });
}

/** POST JSON（/gesture 用）。 */
function a11yPost(path, body, timeoutMs) {
  return new Promise((resolve) => {
    const payload = JSON.stringify(body);
    const req = httpRequest({
      host: "127.0.0.1",
      port: a11yPort(),
      path,
      method: "POST",
      headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload) },
      timeout: timeoutMs || 8000
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { data += c; if (data.length > 262144) req.destroy(); });
      res.on("end", () => resolve(data || "{\"ok\":false,\"error\":\"empty response\"}"));
    });
    req.on("error", () => resolve("{\"ok\":false,\"error\":\"App 本地服务不可用\"}"));
    req.on("timeout", () => { req.destroy(); resolve("{\"ok\":false,\"error\":\"App 本地服务超时\"}"); });
    req.end(payload);
  });
}

function parseResult(raw, hint) {
  try {
    const v = JSON.parse(raw);
    if (!v.ok && v.error && /服务不可用|超时|empty response/.test(v.error)) {
      return { ok: false, error: (hint || GUIDE_TEXT) };
    }
    return v;
  } catch (e) {
    return { ok: false, error: "无障碍服务响应解析失败: " + String(raw).slice(0, 120) };
  }
}

/** DSH 个别路径可能以缺失 value 调用 render（历史回放/旧参数）；兜底避免整次工具调用失败，并把入参暴露出来便于定位。 */
const renderValue = (value, args) => (value && typeof value === "object" ? value : {
  ok: false,
  error: "工具未返回结果（render 收到空值；入参 " + JSON.stringify(args === void 0 ? null : args) + "）"
});

function renderResult(value) {
  value = renderValue(value);
  if (!value.ok) {
    return [{
      type: "text",
      text: "执行失败：" + (value.error || "未知错误") + (value.hint ? "\n提示：" + value.hint : "")
    }];
  }
  let text = "操作成功。";
  if (value.warning) text += "\n⚠ " + value.warning;
  if (value.hint) text += "\n提示：" + value.hint;
  return [{ type: "text", text }];
}

/**
 * android_touch_status 专用渲染：把 held[] 的坐标与按住时长如实回显。
 * 通用 renderResult 只输出「操作成功。」——execute 返回了这些字段、schema 也声明了，
 * 但 AI 看不到，而 touch_status 的全部意义就是「问一下现在按着什么」。
 */
function renderTouchStatus(value) {
  value = renderValue(value);
  if (!value.ok) return renderResult(value);
  const held = Array.isArray(value.held) ? value.held : [];
  const lines = [];
  if (held.length === 0) {
    lines.push("当前没有按住的虚拟手指。");
  } else {
    lines.push("当前按住 " + held.length + " 根手指：");
    held.forEach((h) => {
      const parts = [];
      if (typeof h.finger === "number") parts.push("手指 " + h.finger);
      if (typeof h.x === "number" && typeof h.y === "number") parts.push("像素(" + h.x + "," + h.y + ")");
      if (typeof h.fx === "number" && typeof h.fy === "number") parts.push("分数(" + h.fx + "," + h.fy + ")");
      if (typeof h.elapsedMs === "number") parts.push("已按住 " + h.elapsedMs + "ms");
      lines.push("  " + (parts.length > 0 ? parts.join(" ") : "（字段缺失）"));
    });
  }
  const meta = [];
  if (typeof value.holdTimeoutMs === "number" && value.holdTimeoutMs > 0) meta.push("按住超时上限 " + value.holdTimeoutMs + "ms");
  if (typeof value.maxFingers === "number" && value.maxFingers > 0) meta.push("最多 " + value.maxFingers + " 指");
  if (typeof value.screenW === "number" && typeof value.screenH === "number" && value.screenW > 0) meta.push("屏幕 " + value.screenW + "x" + value.screenH);
  if (meta.length > 0) lines.push(meta.join("，"));
  return [{ type: "text", text: lines.join("\n") }];
}

/** 屏幕节点树渲染成 AI 可读文本列表（带索引，方便 android_tap 引用坐标）。 */
function renderScreen(_args, value) {
  value = renderValue(value, _args);
  if (!value.ok) return renderResult(value);
  const lines = [];
  lines.push("当前前台应用: " + (value.package || "未知"));
  lines.push("节点数: " + value.count + (value.truncated ? "（已截断，仅显示部分）" : ""));
  // A8：前台判定异常提示。锁屏/桌面/过渡动画期间，无障碍给出的前台包名与节点数都可能失真，
  // 而模型会把它当前台事实 → 结论跑偏。这里如实提示"以截图为准"。
  const count = typeof value.count === "number" ? value.count : -1;
  if (value.package === hostAppId() || count === 0 || (count >= 0 && count < 3)) {
    lines.push("⚠ 前台判定可能不准：前台=" + (value.package || "未知") + "，节点数=" + value.count +
      "。可能正处于锁屏/桌面/动画过渡，或无障碍拿不到当前窗口 —— 结论请以 android_see 截图为准。");
  }
  if (value.hint) lines.push("提示: " + value.hint);
  lines.push("");
  const nodes = value.nodes || [];
  nodes.forEach((n, i) => {
    const flags = [];
    if (n.clickable) flags.push("可点击");
    if (n.input) flags.push("可输入");
    if (n.checked) flags.push("已选中");
    if (n.scrollable) flags.push("可滚动");
    const label = n.text || n.desc || "(无文字)";
    const short = label.length > 120 ? label.slice(0, 120) + "…" : label;
    lines.push(`[${i}] ${short}  (${n.x},${n.y} ${n.w}x${n.h})${flags.length ? " " + flags.join("/") : ""}`);
  });
  return [{ type: "text", text: lines.join("\n") }];
}

function apply(ctx) {
  // 状态查询（始终注册：AI 先查状态，未开启时引导用户去系统设置开启）
  ctx.tools.register(defineTool({
    name: "android_a11y_status",
    description:
      "查询 DeepSeek Harness 无障碍服务（屏幕助手）是否已开启，以及当前屏幕焦点应用。" +
      "无障碍服务开启后，AI 才能读取屏幕内容并替你点击/输入/滚动（android_screen/android_tap 等）。" +
      "若未开启（running=false），请引导用户：系统设置 → 无障碍 →（已下载的服务/服务）→ 开启「DeepSeek Harness 屏幕助手」。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          running: { type: "boolean" },
          package: { type: "string" },
          nodeCount: { type: "number" },
          canScreenshot: { type: "boolean" },
          apiLevel: { type: "number" }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      const raw = await a11yRequest("/status", undefined, 4000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: true,
        running: v.running === true,
        package: v.package || "",
        nodeCount: typeof v.nodeCount === "number" ? v.nodeCount : 0,
        canScreenshot: v.canScreenshot === true,
        apiLevel: typeof v.apiLevel === "number" ? v.apiLevel : 0
      };
    }
  }));

  // 读屏（控件树）
  ctx.tools.register(defineTool({
    name: "android_screen",
    description:
      "读取当前屏幕的控件树（无障碍）：返回前台应用包名、屏幕可见控件的文字/描述/坐标/可点击性。" +
      "坐标是屏幕绝对像素坐标，可直接用于 android_tap 的 x/y。" +
      "用于回答「屏幕上现在有什么」「帮我找到某某按钮/选项」「当前在哪个界面」。需要已开启无障碍服务。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          package: { type: "string" },
          count: { type: "number" },
          truncated: { type: "boolean" },
          hint: { type: "string" },
          nodes: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              properties: {
                text: { type: "string" },
                desc: { type: "string" },
                cls: { type: "string" },
                x: { type: "number" },
                y: { type: "number" },
                w: { type: "number" },
                h: { type: "number" },
                clickable: { type: "boolean" },
                input: { type: "boolean" },
                checked: { type: "boolean" },
                selected: { type: "boolean" },
                scrollable: { type: "boolean" },
                depth: { type: "number" }
              }
            }
          }
        }
      },
      render: renderScreen
    },
    async execute(args, exec) {
      const raw = await a11yRequest("/dump", undefined, 8000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      const nodes = Array.isArray(v.nodes) ? v.nodes.map((n) => ({
        text: typeof n.text === "string" ? n.text : "",
        desc: typeof n.desc === "string" ? n.desc : "",
        cls: typeof n.cls === "string" ? n.cls : "",
        x: typeof n.x === "number" ? n.x : 0,
        y: typeof n.y === "number" ? n.y : 0,
        w: typeof n.w === "number" ? n.w : 0,
        h: typeof n.h === "number" ? n.h : 0,
        clickable: n.clickable === true,
        input: n.input === true,
        checked: n.checked === true,
        selected: n.selected === true,
        scrollable: n.scrollable === true,
        depth: typeof n.depth === "number" ? n.depth : 0
      })) : [];
      return {
        ok: true,
        package: v.package || "",
        count: nodes.length,
        truncated: v.truncated === true,
        ...(typeof v.hint === "string" && v.hint ? { hint: v.hint } : {}),
        nodes
      };
    }
  }));

  // 点击
  ctx.tools.register(defineTool({
    name: "android_tap",
    description:
      "点击屏幕上的控件。传 text（控件文字，模糊包含匹配，优先可点击项）、desc（内容描述）、x/y（屏幕绝对像素坐标）或 fx/fy（0~1 分数坐标）。" +
      "优先用 fx/fy 分数坐标（相对屏幕比例）：截图会被模型查看器缩放，用绝对像素容易点偏，分数坐标免疫缩放。" +
      "至少给一个；同时给了 text 与坐标时按 text 查找优先，找不到再按坐标点。需要已开启无障碍服务。",
    parameters: {
      text: { type: "string", description: "控件文字（模糊包含匹配）" },
      desc: { type: "string", description: "控件内容描述（模糊包含匹配）" },
      x: { type: "number", description: "屏幕绝对 x 坐标（像素）" },
      y: { type: "number", description: "屏幕绝对 y 坐标（像素）" },
      fx: { type: "number", description: "分数 x 坐标（0~1，相对屏幕宽度比例；推荐，避免截图缩放误差）" },
      fy: { type: "number", description: "分数 y 坐标（0~1，相对屏幕高度比例；推荐，避免截图缩放误差）" },
      ix: { type: "number", description: "**区域截图专用**：在最近一张 android_see 图里的 x 像素（工具会自动换算回屏幕坐标，免手算）" },
      iy: { type: "number", description: "**区域截图专用**：在最近一张 android_see 图里的 y 像素" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          found: { type: "boolean" },
          method: { type: "string" },
          warning: { type: "string" },
          hint: { type: "string" }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      const params = {};
      if (args.text !== undefined && String(args.text).length > 0) params.text = String(args.text);
      if (args.desc !== undefined && String(args.desc).length > 0) params.desc = String(args.desc);
      if (args.x !== undefined) params.x = String(Number(args.x));
      if (args.y !== undefined) params.y = String(Number(args.y));
      if (args.fx !== undefined) params.fx = String(Number(args.fx));
      if (args.fy !== undefined) params.fy = String(Number(args.fy));
      // A6：ix/iy（区域截图内的像素）→ 自动换算成屏幕 x/y
      const cropErr = applyCropPoint(args, "ix", "iy", params, "x", "y");
      if (cropErr) return { ok: false, error: cropErr };
      if (Object.keys(params).length === 0) {
        return { ok: false, error: "android_tap 需要至少一个参数：text / desc / x / y / fx / fy（或区域截图的 ix/iy）" };
      }
      // A5：底部/边缘点击也可能落在系统手势区或导航栏上
      const warning = edgeGestureWarning(args.fx, args.fy);
      noteInputEvent();
      const raw = await a11yRequest("/tap", params, 8000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: v.found !== false,
        found: v.found === true,
        method: typeof v.method === "string" ? v.method : "",
        ...(warning ? { warning } : {}),
        // v1.19（真机对话实测）：WebView/网页按钮上无障碍 ACTION_CLICK 常被忽略（返回成功但界面无变化）。
        // 如实提示替代路径，别让模型把"点了没反应"当成自己坐标算错。
        ...(v.found === true && v.method === "node-text" && !warning
          ? { hint: "若界面没有变化：WebView/网页按钮经常忽略无障碍 ACTION_CLICK。改用坐标点击（fx/fy）或 android_gesture 的 tap 笔通常有效。" }
          : {}),
        ...(v.found === false && v.error ? { error: v.error } : {})
      };
    }
  }));

  // 输入文本（无障碍版；与特权版 android_input 区分，避免工具名冲突）
  ctx.tools.register(defineTool({
    name: "android_type",
    description:
      "在当前聚焦的输入框中输入文本（通过无障碍服务）。输入前通常先用 android_tap 点击目标输入框使其聚焦。需要已开启无障碍服务。\n" +
      "限制（真机实测）：网页/WebView、contenteditable（例如 DSH 自己的聊天输入框）对无障碍输入不可靠——setText 只改无障碍节点、不触发前端 input 事件；paste:true 也常只落到输入法候选栏、不提交。这类目标请改用系统级 android_input（先 tap 聚焦再 text 输入，两条路径均已实测可用）。\n" +
      "另一个前提：无障碍点击不保证建立输入焦点（键盘没弹出就是没聚焦）——先用 android_input 的 tap 聚焦并确认键盘弹出，再输入。原生 App 的 EditText 用默认 setText 即可。\n" +
      "注：本工具是无障碍版输入（不需要 root/Shizuku）；android_input 需要已授权 Shizuku/root，两者能力不同。",
    parameters: {
      text: { type: "string", required: true, description: "要输入的文本" },
      paste: { type: "boolean", description: "是否用剪贴板粘贴方式输入（WebView/网页输入框建议 true；默认 false 用 setText）" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          focused: { type: "boolean" },
          method: { type: "string" },
          hint: { type: "string" }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      // 空字符串是合法入参（语义 = 清空输入框），只拦真正缺参；用真值判断会把 "" 误判成没传。
      if (args.text === undefined || args.text === null) {
        return { ok: false, error: "android_type 需要 text 参数" };
      }
      const params = { text: String(args.text) };
      if (args.paste === true) params.mode = "paste";
      noteInputEvent();
      const raw = await a11yRequest("/input", params, 8000);
      const v = parseResult(raw);
      // A3：服务端（v1.18 起）会自带可执行的 hint；这里优先用它，旧服务端则用本地启发式兜底。
      const errText = String(v.error || "");
      const serverHint = typeof v.hint === "string" && v.hint ? String(v.hint) : "";
      const hint = serverHint || (!v.ok
        ? (/未找到可输入|没有活动窗口|没有可编辑/.test(errText)
            ? "输入框未聚焦或当前窗口没有可编辑节点：先用 android_tap 点击目标输入框（或用坐标点一次），确认软键盘已弹出，再重试本工具。中文/WebView 建议改用 android_paste_text。"
            : /粘贴未执行|setText 未执行|输入动作未被执行/.test(errText)
              ? "目标节点拒绝粘贴/写文本（常见于 WebView、contenteditable 或第三方输入法）：可改用 android_paste_text，或先 android_see 截图后点击输入法自带的「粘贴」键。"
              : "")
        : "");
      // v1.19（真机对话实测）：服务端可能"动作返回 false 但仍 ok=true"，
      // 之前会让工具显示"操作成功"同时附一句失败说明 → 模型误判为成功。现在一律按失败上报。
      const actionFailed = v.ok === false || (typeof v.error === "string" && v.error.length > 0);
      if (actionFailed) {
        return { ok: false, error: v.error || GUIDE_TEXT, ...(hint ? { hint } : {}) };
      }
      return {
        ok: true,
        ...(v.focused !== undefined ? { focused: v.focused === true } : {}),
        ...(v.method ? { method: String(v.method) } : {}),
        ...(hint ? { hint } : {})
      };
    }
  }));

  // 中文/WebView 输入的标准动作（A4）：写剪贴板 → 聚焦 → ACTION_PASTE，
  // 失败时给出可执行回退路径（而不是让模型自己去猜"为什么没输入进去"）。
  ctx.tools.register(defineTool({
    name: "android_paste_text",
    description:
      "把文本通过剪贴板粘贴进当前输入框（推荐用于中文、emoji，以及 WebView/contenteditable 输入框）。" +
      "内部顺序：写入系统剪贴板 → 让无障碍服务对聚焦的输入框执行粘贴（ACTION_PASTE）。" +
      "若目标未聚焦，请先用 android_tap 点击输入框并确认软键盘弹出。\n" +
      "已知限制（如实说明）：ACTION_PASTE 是否生效取决于输入法/应用自身实现，第三方输入法可能只把内容放进候选栏而不提交。" +
      "失败时本工具会返回回退路径：用 android_see 截图后点击输入法的「粘贴」键（通常在键盘上方一行，屏幕底部约 y≈0.586 一带）。",
    parameters: {
      text: { type: "string", required: true, description: "要粘贴的文本（支持中文）" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          focused: { type: "boolean" },
          method: { type: "string" },
          hint: { type: "string" }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      if (args.text === undefined || args.text === null) {
        return { ok: false, error: "android_paste_text 需要 text 参数" };
      }
      noteInputEvent();
      const raw = await a11yRequest("/input", { text: String(args.text), mode: "paste" }, 8000);
      const v = parseResult(raw);
      const fallback =
        "回退路径：① 确保输入框已聚焦（android_tap 点一次、确认键盘弹出）；" +
        "② 用 android_see 截图，找到输入法的「粘贴」键（键盘上方一行，屏幕底部约 y≈0.586 一带）并用 android_tap 点它；" +
        "③ 仍不行则改用特权通道 android_input（action=text，需 Shizuku/root）。";
      const serverHint = typeof v.hint === "string" && v.hint ? String(v.hint) : "";
      // 同上：动作返回 false 时按失败上报，别显示"操作成功"
      const actionFailed = v.ok === false || (typeof v.error === "string" && v.error.length > 0);
      if (actionFailed) {
        return { ok: false, error: v.error || GUIDE_TEXT, hint: serverHint || fallback };
      }
      const method = v.method ? String(v.method) : "paste";
      return {
        ok: true,
        ...(v.focused !== undefined ? { focused: v.focused === true } : {}),
        method,
        ...(serverHint ? { hint: serverHint } : {})
      };
    }
  }));

  // 返回 / 回桌面
  const globalAction = (toolName, actionPath, description) => {
    ctx.tools.register(defineTool({
      name: toolName,
      description,
      parameters: {},
      output: {
        schema: {
          type: "object",
          additionalProperties: false,
          properties: {
            ok: { type: "boolean", required: true },
            error: { type: "string" }
          }
        },
        render: (_a, v) => renderResult(v)
      },
      async execute(args, exec) {
        noteInputEvent();
        const raw = await a11yRequest(actionPath, undefined, 6000);
        const v = parseResult(raw);
        if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
        return { ok: v.ok !== false, ...(v.error ? { error: v.error } : {}) };
      }
    }));
  };
  globalAction("android_back", "/back", "模拟按下系统返回键（回到上一界面）。需要已开启无障碍服务。");
  globalAction("android_home", "/home", "模拟按下系统 Home 键（回到桌面）。需要已开启无障碍服务。");

  // 滚动
  ctx.tools.register(defineTool({
    name: "android_scroll",
    description:
      "在当前可滚动区域滚动屏幕：direction 为 up（向上滚动看更上面内容）/ down / left / right。" +
      "用于翻页、浏览长列表。需要已开启无障碍服务。",
    parameters: {
      direction: {
        type: "string", required: true, enum: ["up", "down", "left", "right"],
        description: "滚动方向"
      }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          method: { type: "string" }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      noteInputEvent();
      const raw = await a11yRequest("/scroll", { direction: String(args.direction || "down") }, 8000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: v.ok !== false,
        ...(v.method ? { method: String(v.method) } : {}),
        ...(v.error ? { error: v.error } : {})
      };
    }
  }));

  // 截图（需要 attachments 服务 + 视觉模型）
  ctx.inject(["attachments"], (imageCtx) => {
    imageCtx.tools.register(defineTool({
      name: "android_see",
      description:
        "截取当前屏幕（无障碍截图，无需 MediaProjection 弹窗）并把截图作为图片发送给模型查看。" +
        "适合需要看图理解布局/图片内容、或控件树（android_screen）信息不足时（尤其 Unity/游戏等无控件界面）。" +
        "**截图会被模型查看器缩放，绝对像素坐标会点偏——请优先用分数坐标（fx/fy，0~1）配合 android_tap/android_swipe/android_hold/android_gesture 操作**。" +
        "换算：截图上量到的像素 (px,py) → 屏幕坐标 = (px×scaleX, py×scaleY)（scaleX=screenW/imageW，截图原生分辨率≈屏幕，通常≈1）。" +
        "游戏/无控件界面建议 grid:true 叠加 4×4 网格，按「第几行第几列」定位更准。" +
        "需要当前模型支持图片输入；模型不支持图片时请改用 android_screen 读控件文字。" +
        "需要已开启无障碍服务且设备 Android 11+（截图能力），低版本可用 android_screen。",
      parameters: {
        grid: { type: "boolean", description: "true 时在截图上叠加 4×4 网格线，方便按行列定位（游戏/无控件界面推荐）" },
        select: { type: "string", description: "**区域截图**：按无障碍节点文字取景（完全相等优先，其次更短的包含匹配），只截该节点（可配 pad 外扩）。适合看清小字/一条数据，比整屏清晰得多" },
        selectIndex: { type: "number", description: "select 命中多个候选时取第几个（0 起，默认 0）。返回里的 selectCount/selectLabel 会告诉你命中是谁、还有几个候选" },
        region: { type: "string", description: "**区域截图**：显式矩形 \"x,y,w,h\"（屏幕像素，左上角为原点）。与 select 二选一" },
        pad: { type: "number", description: "select 时四周外扩的像素（默认 0，建议 8~24 留点上下文）" }
      },
      output: {
        schema: {
          type: "object",
          additionalProperties: false,
          properties: {
            ok: { type: "boolean", required: true },
            error: { type: "string" },
            path: { type: "string" },
            screenW: { type: "number" },
            screenH: { type: "number" },
            imageW: { type: "number" },
            imageH: { type: "number" },
            scaleX: { type: "number" },
            scaleY: { type: "number" },
            grid: { type: "number" },
            hint: { type: "string" },
            // A7：截图去重证据（时间戳 + 内容哈希 + 是否与上一次相同），
            // 避免"连续两次拿到同一张图"时无法判断是自己没操作还是截图没刷新。
            sha256: { type: "string" },
            capturedAt: { type: "number" },
            sameAsPrevious: { type: "boolean" },
            // A6：区域截图几何（屏幕坐标）与精确换算：screenX = cropX + ix*scaleX
            cropped: { type: "boolean" },
            cropX: { type: "number" },
            cropY: { type: "number" },
            cropW: { type: "number" },
            cropH: { type: "number" },
            selectFx: { type: "number" },
            selectFy: { type: "number" },
            selectLabel: { type: "string" },
            selectCount: { type: "number" },
            selectIndex: { type: "number" },
            image: {
              type: "object",
              additionalProperties: false,
              properties: {
                attachmentId: { type: "string", required: true },
                mediaType: { type: "string", required: true },
                bytes: { type: "number" },
                width: { type: "number" },
                height: { type: "number" },
                name: { type: "string" }
              }
            }
          }
        },
        render: (_args, value) => {
          value = renderValue(value, _args);
          if (!value.ok) return renderResult(value);
          const meta = [
            `${value.image.mediaType} 屏幕截图, ${value.image.width}x${value.image.height} px, ${value.image.bytes} bytes`,
            `屏幕尺寸 ${value.screenW}x${value.screenH}, 截图尺寸 ${value.imageW}x${value.imageH}, 换算系数 scaleX=${value.scaleX} scaleY=${value.scaleY}${value.grid ? `, 已叠加 ${value.grid}x${value.grid} 网格` : ""}`,
            value.capturedAt ? `截取时间 ${new Date(value.capturedAt).toISOString()}，sha256=${value.sha256 || "?"}${value.sameAsPrevious ? "（与上一次截图内容相同）" : ""}` : "",
            value.warning ? `⚠ ${value.warning}` : "",
            value.cropped
              ? `区域截图：屏幕坐标 (${value.cropX},${value.cropY}) 起的 ${value.cropW}x${value.cropH} 区域`
              : "",
            value.cropped
              ? `换算（ix/iy = 图中像素）：screenX = ${value.cropX} + ix×${value.scaleX}，screenY = ${value.cropY} + iy×${value.scaleY}；`
                + "也可以把 ix/iy 直接交给 android_tap/android_swipe/android_hold/android_touch 的 ix/iy 参数，由工具自动换算（推荐，免手算）"
              : "操作优先用分数坐标 fx/fy（0~1）：图中位置 (ix,iy) → fx=ix/imageW, fy=iy/imageH；用绝对像素 = 图中像素 × scaleX/Y",
            value.selectFx !== undefined
              ? `命中节点：「${value.selectLabel || "?"}」中心 fx=${value.selectFx}, fy=${value.selectFy}（可直接 android_tap(fx,fy)）`
                + (value.selectCount ? `；候选 ${value.selectCount} 个${value.selectCount > 1 ? `，当前第 ${value.selectIndex || 0} 个（换一个用 selectIndex）` : ""}` : "")
              : ""
          ].filter(Boolean).join("\n");
          return [{
            type: "text",
            text: `<path>${value.path}</path>\n<type>image</type>\n<content>\n${meta}\n</content>`
          }, {
            type: "image",
            attachment: {
              attachmentId: value.image.attachmentId,
              mediaType: value.image.mediaType,
              bytes: value.image.bytes,
              width: value.image.width,
              height: value.image.height,
              ...(value.image.name === void 0 ? {} : { name: value.image.name })
            }
          }];
        }
      },
      async execute(args, exec) {
        const attachments = imageCtx.get("attachments");
        if (attachments === void 0) {
          return { ok: false, error: "cannot screenshot: no attachment service is mounted" };
        }
        const params = {};
        if (args.grid === true) params.grid = "4";
        if (args.select !== undefined && String(args.select).length > 0) params.select = String(args.select);
        else if (args.region !== undefined && String(args.region).length > 0) params.region = String(args.region);
        if (args.selectIndex !== undefined) params.selectIndex = String(Number(args.selectIndex));
        if (args.pad !== undefined) params.pad = String(Number(args.pad));
        // v1.22：内存前置守卫 —— 全屏截图（没给 select/region）容易把系统推向失控；
        // 区域截图只有几十 KB，任何时候都放行。
        const isFullShot = !params.select && !params.region;
        let mem = null;
        if (isFullShot) {
          mem = await readMem();
          if (mem && mem.availKb < FULL_SHOT_MIN_AVAIL_KB) {
            return {
              ok: false,
              error: "可用内存过低（" + mbOf(mem.availKb) + "MB / 共 " + mbOf(mem.totalKb)
                + "MB），已拒绝全屏截图以免触发系统回收（App 与无障碍服务可能被重启，之后点击/输入会全部失败）。"
                + "请改用区域截图：android_see(select=\"界面上的文字\") 或 android_see(region=\"x,y,w,h\")，"
                + "或用 android_screen 读控件树（最省内存）。"
            };
          }
          if (fullShotBudgetExceeded()) {
            return {
              ok: false,
              error: "全屏截图过于频繁（每分钟上限 " + FULL_SHOT_BUDGET_PER_MIN + " 张），已拒绝。"
                + "请优先用 android_see(select=…) / android_see(region=…) 做区域截图，"
                + "或 android_screen 读控件树；需要等界面变化时不要反复整屏截图。"
            };
          }
        }
        const raw = await a11yRequest("/screenshot", Object.keys(params).length ? params : undefined, 20000);
        const v = parseResult(raw);
        if (!v.ok || !v.path) {
          return { ok: false, error: v.error || "截图失败（可能设备低于 Android 11，或当前页面禁止截图）" };
        }
        let data;
        try {
          data = await readFile(v.path);
        } catch (e) {
          const code = e && e.code ? String(e.code) : "";
          // EACCES 的成因是「截图落在另一个同源包的私有目录」（/data/user/0/<pkg>/ 按 UID 隔离，
          // 跨包必失败）。服务端已改为写入共享目录；这里保留可操作的提示便于在旧包上定位。
          const hint = code === "EACCES"
            ? "\n该截图位于另一个安装包的私有目录（/data/user/0/<包名>/...），跨包按 UID 隔离无法读取。" +
              "请确认无障碍服务与引擎是同一个包。\n临时替代：android_screenshot(save_path=\"" +
              (process.env.DSH_EXT_DIR || "/sdcard/DeepSeekHarness") + "/screenshots/x.png\") + read_image。"
            : "";
          return { ok: false, error: "读取截图失败: " + (code ? code + " " : "") + String(e && e.message || e) + hint };
        }
        try {
          const ref = await attachments.saveImage({
            data,
            mediaType: "image/png",
            name: "screen.png"
          });
          // A7：记录本次截图内容哈希与截取时间，并判断"与上一次是否相同"。
          // 若两次之间发生过输入操作却仍是同一张图，说明截图未刷新（或操作没生效），
          // 必须如实提示，别让模型把旧画面当现状。
          const hash = createHash("sha256").update(data).digest("hex");
          const at = Date.now();
          const prev = lastShot;
          const sameAsPrevious = prev.hash !== "" && prev.hash === hash;
          const staleAfterInput = sameAsPrevious && inputEventSeq > prev.seq;
          lastShot = { hash, seq: inputEventSeq, at };
          rememberCrop(v);   // A6：记录裁剪几何（整屏也记，换算公式统一）
          if (isFullShot) fullShotTimes.push(at);   // v1.22：全屏截图计入预算
          const hints = [];
          if (isFullShot && mem && mem.availKb < FULL_SHOT_WARN_AVAIL_KB) {
            hints.push("可用内存偏低（" + mbOf(mem.availKb) + "MB / 共 " + mbOf(mem.totalKb)
              + "MB）：继续高频全屏截图可能导致系统回收 App 与无障碍服务（之后再点击/输入会失败）。"
              + "后续优先用 android_see(select=…) / android_see(region=…) 做区域截图。");
          }
          if (typeof v.hint === "string" && v.hint) hints.push(v.hint);
          if (staleAfterInput) {
            hints.push("与上一次截图内容完全相同，但期间发生过输入操作：截图可能未刷新或操作未生效，建议重新截图确认（或先用 android_screen 看节点是否变化）。");
          }
          return {
            ok: true,
            path: v.path,
            screenW: typeof v.screenW === "number" ? v.screenW : 0,
            screenH: typeof v.screenH === "number" ? v.screenH : 0,
            imageW: typeof v.imageW === "number" ? v.imageW : 0,
            imageH: typeof v.imageH === "number" ? v.imageH : 0,
            scaleX: typeof v.scaleX === "number" ? v.scaleX : 1,
            scaleY: typeof v.scaleY === "number" ? v.scaleY : 1,
            grid: typeof v.grid === "number" ? v.grid : 0,
            sha256: hash.slice(0, 16),
            capturedAt: at,
            sameAsPrevious,
            // A6：记住本次截图几何，供 ix/iy（裁剪图像素）自动换算
            cropped: v.cropped === true,
            cropX: typeof v.cropX === "number" ? v.cropX : 0,
            cropY: typeof v.cropY === "number" ? v.cropY : 0,
            cropW: typeof v.cropW === "number" ? v.cropW : 0,
            cropH: typeof v.cropH === "number" ? v.cropH : 0,
            ...(typeof v.selectFx === "number" ? { selectFx: v.selectFx, selectFy: v.selectFy } : {}),
            ...(typeof v.selectLabel === "string" ? { selectLabel: v.selectLabel } : {}),
            ...(typeof v.selectCount === "number" ? { selectCount: v.selectCount } : {}),
            ...(typeof v.selectIndex === "number" ? { selectIndex: v.selectIndex } : {}),
            ...(hints.length ? { hint: hints.join(" ") } : {}),
            image: {
              attachmentId: ref.attachmentId,
              mediaType: ref.mediaType,
              bytes: ref.bytes,
              width: ref.width,
              height: ref.height,
              name: ref.name
            }
          };
        } catch (e) {
          return { ok: false, error: "截图保存为附件失败: " + String(e && e.message || e) };
        }
      }
    }));
  });

  // ===================== v1.7.3 通用触摸手势工具 =====================
  // 底层：无障碍 dispatchGesture 多笔时间轴（真多指）+ willContinue 按住保持。
  // 一套原语覆盖所有触摸操作（点击/滑动/长按/按住拖动/多指同时），不绑定任何具体 App/游戏。
  // 坐标统一支持 x/y（屏幕绝对像素）或 fx/fy（0~1 分数，推荐——截图会被查看器缩放）。

  // 必须与无障碍服务实际返回的字段一致：additionalProperties:false 下漏声明任何一个字段，
  // 整个调用都会被判为 error（数据其实是对的）。服务端会返回 elapsedMs（已按住时长）。
  const heldSchema = {
    type: "object",
    additionalProperties: false,
    properties: {
      finger: { type: "number" },
      x: { type: "number" },
      y: { type: "number" },
      fx: { type: "number" },
      fy: { type: "number" },
      elapsedMs: { type: "number" }
    }
  };
  const touchOutput = {
    schema: {
      type: "object",
      additionalProperties: false,
      properties: {
        ok: { type: "boolean", required: true },
        error: { type: "string" },
        durationMs: { type: "number" },
        held: { type: "array", items: heldSchema },
        // A5：边缘手势防呆提示（起点落在系统手势区时给出）
        warning: { type: "string" },
        hint: { type: "string" }
      }
    },
    render: (_a, v) => renderResult(v)
  };

  // 滑动
  ctx.tools.register(defineTool({
    name: "android_swipe",
    description:
      "在屏幕上从起点滑动到终点（按下→移动→抬起，单指）。" +
      "参数可用屏幕绝对像素（x1/y1→x2/y2）或分数坐标（fx1/fy1→fx2/fy2，0~1，推荐）。" +
      "durationMs 控制滑动时长（默认 300ms；慢速拖动可加大到 800~1500ms）。" +
      "适合翻页、划动列表、游戏内转向/拖动。需要已开启无障碍服务。",
    parameters: {
      x1: { type: "number", description: "起点 x（像素）" },
      y1: { type: "number", description: "起点 y（像素）" },
      x2: { type: "number", description: "终点 x（像素）" },
      y2: { type: "number", description: "终点 y（像素）" },
      fx1: { type: "number", description: "起点分数 x（0~1，推荐）" },
      fy1: { type: "number", description: "起点分数 y（0~1，推荐）" },
      fx2: { type: "number", description: "终点分数 x（0~1，推荐）" },
      fy2: { type: "number", description: "终点分数 y（0~1，推荐）" },
      ix1: { type: "number", description: "**区域截图专用**：起点在最近一张 android_see 图里的 x 像素（自动换算）" },
      iy1: { type: "number", description: "**区域截图专用**：起点在图里的 y 像素" },
      ix2: { type: "number", description: "**区域截图专用**：终点在图里的 x 像素" },
      iy2: { type: "number", description: "**区域截图专用**：终点在图里的 y 像素" },
      durationMs: { type: "number", description: "滑动时长毫秒（默认 300）" },
      finger: { type: "number", description: "可选：指定手指（0~7）；若该手指正按住则从当前位置滑到终点并抬起" }
    },
    output: touchOutput,
    async execute(args, exec) {
      const params = {};
      for (const k of ["x1", "y1", "x2", "y2", "fx1", "fy1", "fx2", "fy2"]) {
        if (args[k] !== undefined) params[k] = String(Number(args[k]));
      }
      // A6：起点/终点都支持"图内像素"（ix1/iy1、ix2/iy2）→ 自动换算成屏幕坐标
      const cropErr1 = applyCropPoint(args, "ix1", "iy1", params, "x1", "y1");
      if (cropErr1) return { ok: false, error: cropErr1 };
      const cropErr2 = applyCropPoint(args, "ix2", "iy2", params, "x2", "y2");
      if (cropErr2) return { ok: false, error: cropErr2 };
      if (args.durationMs !== undefined) params.duration = String(Number(args.durationMs));
      if (args.finger !== undefined) params.finger = String(Number(args.finger));
      if (!(("x1" in params || "fx1" in params) && ("y1" in params || "fy1" in params) &&
            ("x2" in params || "fx2" in params) && ("y2" in params || "fy2" in params))) {
        return { ok: false, error: "android_swipe 需要起点(x1/y1 或 fx1/fy1)和终点(x2/y2 或 fx2/fy2)" };
      }
      // A5：边缘起点防呆（只对能拿到的分数坐标判定；像素坐标需要屏幕尺寸，这里不额外探测）
      const warning = edgeGestureWarning(args.fx1, args.fy1);
      noteInputEvent();
      const raw = await a11yRequest("/swipe", params, 12000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: true,
        ...(warning ? { warning } : {}),
        ...(typeof v.durationMs === "number" ? { durationMs: v.durationMs } : {}),
        ...(Array.isArray(v.held) ? { held: v.held } : {})
      };
    }
  }));

  // 长按 / 按住指定时长后自动抬起
  ctx.tools.register(defineTool({
    name: "android_hold",
    description:
      "在指定位置按住（长按）durationMs 毫秒后自动抬起，也可用 finger 指定手指。" +
      "**需要一直按住不放（延续到后续操作）时，不要用本工具，改用 android_touch action=down**（down 后手指保持按住，可跨调用延续）。" +
      "适合长按图标、游戏蓄力、按住等待等。需要已开启无障碍服务。",
    parameters: {
      x: { type: "number", description: "按住 x（像素）" },
      y: { type: "number", description: "按住 y（像素）" },
      fx: { type: "number", description: "分数 x（0~1，推荐）" },
      fy: { type: "number", description: "分数 y（0~1，推荐）" },
      ix: { type: "number", description: "**区域截图专用**：在最近一张 android_see 图里的 x 像素（自动换算）" },
      iy: { type: "number", description: "**区域截图专用**：在最近一张 android_see 图里的 y 像素" },
      durationMs: { type: "number", description: "按住时长毫秒（默认 500）" },
      finger: { type: "number", description: "可选：指定手指（0~7）" }
    },
    output: touchOutput,
    async execute(args, exec) {
      const params = {};
      for (const k of ["x", "y", "fx", "fy"]) {
        if (args[k] !== undefined) params[k] = String(Number(args[k]));
      }
      if (args.durationMs !== undefined) params.duration = String(Number(args.durationMs));
      if (args.finger !== undefined) params.finger = String(Number(args.finger));
      // A6：hold 也支持图内像素 ix/iy
      const cropErr = applyCropPoint(args, "ix", "iy", params, "x", "y");
      if (cropErr) return { ok: false, error: cropErr };
      if (!(("x" in params || "fx" in params) && ("y" in params || "fy" in params))) {
        return { ok: false, error: "android_hold 需要 x/y 或 fx/fy（或区域截图的 ix/iy）" };
      }
      const warning = edgeGestureWarning(args.fx, args.fy);
      noteInputEvent();
      const raw = await a11yRequest("/hold", params, 12000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: true,
        ...(warning ? { warning } : {}),
        ...(Array.isArray(v.held) ? { held: v.held } : {})
      };
    }
  }));

  // 状态式虚拟触摸屏（多指核心）
  ctx.tools.register(defineTool({
    name: "android_touch",
    description:
      "虚拟触摸屏状态式控制：action=down（按下并保持）/ move（按住的手指滑到新位置）/ up（抬起）。" +
      "每根手指用 finger 编号（0~7）区分，多根手指可同时按住——多指操作的基础。" +
      "**典型用法：按住摇杆 = down(0) 在摇杆位置，然后 move(0) 拖动控制方向，松开 = up(0)**。" +
      "down 之后手指一直按住，直到你 up / android_touch_status 确认 / 超时（30s）自动抬起。" +
      "跨调用延续：down(0) 后可直接调 android_tap/android_gesture 等，按住的手指不会被松开（自动并入后续手势）。" +
      "坐标支持 x/y 或 fx/fy（0~1，推荐）。需要已开启无障碍服务。",
    parameters: {
      action: { type: "string", required: true, enum: ["down", "move", "up"], description: "down=按下保持 / move=按住移动 / up=抬起" },
      finger: { type: "number", required: true, description: "手指编号 0~7（多指的关键：每根手指一个编号）" },
      x: { type: "number", description: "目标 x（像素）" },
      y: { type: "number", description: "目标 y（像素）" },
      fx: { type: "number", description: "分数 x（0~1，推荐）" },
      fy: { type: "number", description: "分数 y（0~1，推荐）" },
      ix: { type: "number", description: "**区域截图专用**：在最近一张 android_see 图里的 x 像素（自动换算）" },
      iy: { type: "number", description: "**区域截图专用**：在最近一张 android_see 图里的 y 像素" }
    },
    output: touchOutput,
    async execute(args, exec) {
      if (!args.action) return { ok: false, error: "android_touch 需要 action=down|move|up" };
      if (args.finger === undefined) return { ok: false, error: "android_touch 需要 finger=0~7" };
      const params = { action: String(args.action), finger: String(Number(args.finger)) };
      for (const k of ["x", "y", "fx", "fy"]) {
        if (args[k] !== undefined) params[k] = String(Number(args[k]));
      }
      // A6：down/move 支持图内像素 ix/iy
      const cropErr = applyCropPoint(args, "ix", "iy", params, "x", "y");
      if (cropErr) return { ok: false, error: cropErr };
      // A5：down 的落点若在系统手势区，按住可能被系统抢走
      const warning = args.action === "down" ? edgeGestureWarning(args.fx, args.fy) : "";
      noteInputEvent();
      const raw = await a11yRequest("/touch", params, 12000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return { ok: true, ...(warning ? { warning } : {}), ...(Array.isArray(v.held) ? { held: v.held } : {}) };
    }
  }));

  // 组合手势（多笔时间轴，一次全部注入）
  ctx.tools.register(defineTool({
    name: "android_gesture",
    description:
      "一次执行一组多指手势（底层多笔时间轴，全部同时注入，真多指）。strokes 数组按顺序在时间轴上执行，支持：\n" +
      "- down: 按下并保持 {kind:'down', finger, x/y 或 fx/fy}\n" +
      "- move: 按住的手指滑到新位置 {kind:'move', finger, x/y 或 fx/fy}\n" +
      "- up: 抬起 {kind:'up', finger}\n" +
      "- tap: 点按 {kind:'tap', x/y 或 fx/fy, [finger], [durationMs]}\n" +
      "- swipe: 滑动 {kind:'swipe', x/y 或 fx/fy → x2/y2 或 fx2/fy2, [durationMs]}\n" +
      "- hold: 按下→保持 durationMs→抬起 {kind:'hold', x/y 或 fx/fy, [durationMs], [finger]}\n" +
      "- wait: 等待 {kind:'wait', ms}\n" +
      "**典型游戏场景：左手按住摇杆同时右手点击 = [down(0, 摇杆), tap(1, 按钮)]**；按住摇杆拖动 = [down(0, 摇杆中心), move(0, 目标方向)]。" +
      "down 的手指在请求结束后继续保持（可跨请求延续），直到 up / 超时自动抬起。" +
      "坐标全部支持 fx/fy（0~1，推荐）。需要已开启无障碍服务。",
    parameters: {
      strokes: {
        type: "array",
        required: true,
        items: {
          type: "object",
          additionalProperties: false,
          properties: {
            kind: { type: "string", required: true, enum: ["down", "move", "up", "tap", "swipe", "hold", "wait"], description: "笔类型" },
            finger: { type: "number", description: "手指编号 0~7" },
            x: { type: "number", description: "目标 x（像素）" },
            y: { type: "number", description: "目标 y（像素）" },
            fx: { type: "number", description: "分数 x（0~1，推荐）" },
            fy: { type: "number", description: "分数 y（0~1，推荐）" },
            x2: { type: "number", description: "swipe 终点 x（像素）" },
            y2: { type: "number", description: "swipe 终点 y（像素）" },
            fx2: { type: "number", description: "swipe 终点分数 x（0~1）" },
            fy2: { type: "number", description: "swipe 终点分数 y（0~1）" },
            ix: { type: "number", description: "**区域截图专用**：笔起点在图内 x 像素（自动换算）" },
            iy: { type: "number", description: "**区域截图专用**：笔起点在图内 y 像素" },
            ix2: { type: "number", description: "**区域截图专用**：swipe 终点在图内 x 像素" },
            iy2: { type: "number", description: "**区域截图专用**：swipe 终点在图内 y 像素" },
            durationMs: { type: "number", description: "时长（wait=等待毫秒；tap 默认60；swipe 默认300；hold 默认500；move/up 默认100）" },
            ms: { type: "number", description: "wait 的等待毫秒" }
          }
        },
        description: "手势笔列表（按顺序在时间轴上执行）"
      }
    },
    output: touchOutput,
    async execute(args, exec) {
      const strokes = args.strokes;
      if (!Array.isArray(strokes) || strokes.length === 0) {
        return { ok: false, error: "android_gesture 需要 strokes 数组" };
      }
      const ALLOWED = ["kind", "finger", "x", "y", "fx", "fy", "x2", "y2", "fx2", "fy2", "durationMs", "ms"];
      // v1.19（真机对话实测）：模型常用 android_gesture 只传一个 wait 当"睡眠"用，
      // 而服务端会回"手势没有可执行的笔"→ 白白浪费一次调用。纯 wait 直接在本地睡，不再往返。
      if (strokes.every((s) => s && s.kind === "wait")) {
        let ms = 0;
        for (const s of strokes) ms += typeof s.ms === "number" && s.ms > 0 ? s.ms : 0;
        ms = Math.min(ms, 60000);
        noteInputEvent();
        await sleep(ms);
        return { ok: true, durationMs: ms, hint: "wait 笔已在本地等待 " + ms + "ms（服务端不接受纯 wait 手势）" };
      }
      const clean = [];
      let total = 0;
      let strokeIdx = 0;
      for (const s of strokes) {
        if (!s || typeof s !== "object") return { ok: false, error: "strokes 元素必须是对象" };
        const kind = s.kind;
        const dur = typeof s.durationMs === "number" ? s.durationMs : 0;
        if (kind === "wait") total += typeof s.ms === "number" && s.ms > 0 ? s.ms : 0;
        else if (kind === "tap") total += dur > 0 ? dur : 60;
        else if (kind === "swipe") total += dur > 0 ? dur : 300;
        else if (kind === "hold") total += dur > 0 ? dur : 500;
        else if (kind === "move") total += dur > 0 ? dur : 100;
        else if (kind === "up") total += dur > 0 ? dur : 100;
        else if (kind !== "down") return { ok: false, error: "未知 kind: " + String(kind) };
        const o = {};
        for (const k of Object.keys(s)) {
          if (ALLOWED.includes(k)) o[k] = s[k];
        }
        // A6：每根笔都支持"图内像素"（ix/iy，swipe 还可 ix2/iy2）→ 自动换算成屏幕坐标
        const cErr1 = applyCropPoint(s, "ix", "iy", o, "x", "y");
        if (cErr1) return { ok: false, error: "strokes[" + strokeIdx + "]: " + cErr1 };
        const cErr2 = applyCropPoint(s, "ix2", "iy2", o, "x2", "y2");
        if (cErr2) return { ok: false, error: "strokes[" + strokeIdx + "]: " + cErr2 };
        clean.push(o);
        strokeIdx++;
      }
      const raw = await a11yPost("/gesture", clean, total + 15000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      // A5：用第一条定位笔（down/tap/swipe/hold）的分数起点做边缘防呆
      const first = clean.find((s) => s.kind === "down" || s.kind === "tap" || s.kind === "swipe" || s.kind === "hold");
      const warning = first ? edgeGestureWarning(first.fx, first.fy) : "";
      noteInputEvent();
      return {
        ok: true,
        ...(warning ? { warning } : {}),
        ...(typeof v.durationMs === "number" ? { durationMs: v.durationMs } : {}),
        ...(Array.isArray(v.held) ? { held: v.held } : {})
      };
    }
  }));

  // 触摸状态查询
  ctx.tools.register(defineTool({
    name: "android_touch_status",
    description:
      "查询当前按住的手指（虚拟触摸屏状态）。用于确认之前 down 的手指是否还在按住、坐标在哪、按住多久。" +
      "手指按住超过 30 秒会被自动抬起（安全机制）。需要已开启无障碍服务。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          screenW: { type: "number" },
          screenH: { type: "number" },
          maxFingers: { type: "number" },
          holdTimeoutMs: { type: "number" },
          held: { type: "array", items: heldSchema }
        }
      },
      render: (_a, v) => renderTouchStatus(v)
    },
    async execute(args, exec) {
      const raw = await a11yRequest("/touch-status", undefined, 6000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: true,
        screenW: typeof v.screenW === "number" ? v.screenW : 0,
        screenH: typeof v.screenH === "number" ? v.screenH : 0,
        maxFingers: typeof v.maxFingers === "number" ? v.maxFingers : 0,
        holdTimeoutMs: typeof v.holdTimeoutMs === "number" ? v.holdTimeoutMs : 0,
        held: Array.isArray(v.held) ? v.held : []
      };
    }
  }));

  // ==========================================================================
  // A2：统一能力探测（一次问清"现在到底能做什么"）
  // 背景：无障碍开关、截图能力、Shizuku/root、虚拟屏桥是否在跑、能不能列/启动应用，
  // 原来分散在 4~5 个工具各自的失败信息里，模型只能逐个试错。
  // 本工具**不做任何特权操作**：只读 env + 打两个本地 HTTP（都是本 App 自己的服务）。
  // 放在无障碍插件里是有意的——dsh-tool-android 在无特权时整体不注册，
  // 能力探测若放在那里就会"无特权时恰好消失"，正是最需要它的时候没有。
  // ==========================================================================
  // v1.22：内存体检（真机 Agent 复盘要求的前置守卫项）
  ctx.tools.register(defineTool({
    name: "android_mem_status",
    description:
      "查看本机内存状况（读 /proc/meminfo，**全机视图**，不受 App cgroup 隔离）。" +
      "准备做长时间 / 高频的屏幕操作（尤其反复 android_see 全屏截图）前建议先看一眼：" +
      "可用内存低于约 400MB 时，高频全屏截图会显著提高系统回收 App 与无障碍服务的概率" +
      "（表现是无障碍「闪断」、随后 tap/scroll/type 全部失败）。" +
      "紧张时的替代路径：android_see(select=…) / android_see(region=…) 区域截图，或 android_screen 读控件树。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          totalMb: { type: "number" },
          availMb: { type: "number" },
          freeMb: { type: "number" },
          level: { type: "string" },
          recentFullShots: { type: "number" },
          fullShotBudgetPerMin: { type: "number" },
          hint: { type: "string" }
        }
      },
      render(_a, v) {
        v = renderValue(v);
        if (!v.ok) return renderResult(v);
        const lines = [
          "本机内存：" + v.availMb + "MB 可用 / 共 " + v.totalMb + "MB（MemFree " + v.freeMb + "MB）· 等级：" + v.level,
          "最近一分钟全屏截图：" + v.recentFullShots + " / " + v.fullShotBudgetPerMin + " 张（超限会被拒绝）"
        ];
        if (v.hint) lines.push("", "建议: " + v.hint);
        return [{ type: "text", text: lines.join("\n") }];
      }
    },
    async execute() {
      const mem = await readMem();
      if (!mem) return { ok: false, error: "读取 /proc/meminfo 失败（部分系统会限制）" };
      const now = Date.now();
      fullShotTimes = fullShotTimes.filter((t) => now - t < 60000);
      const level = mem.availKb < FULL_SHOT_MIN_AVAIL_KB ? "紧张"
        : (mem.availKb < FULL_SHOT_WARN_AVAIL_KB ? "偏低" : "充足");
      const hints = [];
      if (level === "紧张") {
        hints.push("可用内存紧张：**不要**做全屏截图（会被本插件直接拒绝），改用 android_see(select=/region=) 区域截图"
          + "或 android_screen 读控件树；若已出现工具大面积失败，先停下、让 App 回前台，等系统回收内存后再继续。");
      } else if (level === "偏低") {
        hints.push("可用内存偏低：接下来优先区域截图，避免连续整屏截图。");
      }
      return {
        ok: true,
        totalMb: mbOf(mem.totalKb),
        availMb: mbOf(mem.availKb),
        freeMb: mbOf(mem.freeKb),
        level,
        recentFullShots: fullShotTimes.length,
        fullShotBudgetPerMin: FULL_SHOT_BUDGET_PER_MIN,
        ...(hints.length ? { hint: hints.join(" ") } : {})
      };
    }
  }));

  ctx.tools.register(defineTool({
    name: "android_capabilities",
    description:
      "一次性查询本机当前可用的 Android 能力（无障碍是否开启、能否截图、Shizuku/root 特权通道、虚拟屏服务是否就绪、" +
      "能否做需要特权的系统操作如装机/改设置/模拟输入）。" +
      "**开始任何手机操作任务前建议先调用一次**，避免逐个工具试错（例如无特权时 android_input/android_package 根本不会出现在工具列表里）。" +
      "返回每项能力的可用性与不可用时的下一步建议。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          a11yRunning: { type: "boolean" },
          canScreenshot: { type: "boolean" },
          privileged: { type: "boolean" },
          privilegedChannel: { type: "string" },
          canSystemOps: { type: "boolean" },
          canListApps: { type: "boolean" },
          canLaunchApps: { type: "boolean" },
          vscreenBridge: { type: "boolean" },
          appId: { type: "string" },
          memAvailMb: { type: "number" },
          memLevel: { type: "string" },
          recentFullShots: { type: "number" },
          hint: { type: "string" }
        }
      },
      render(_a, v) {
        v = renderValue(v);
        if (!v.ok) return renderResult(v);
        const yn = (b) => (b ? "✅ 可用" : "❌ 不可用");
        const lines = [
          "当前 Android 能力总览（appId=" + (v.appId || "?") + "）:",
          "  无障碍读屏/点击: " + yn(v.a11yRunning) + (v.a11yRunning ? "" : "（系统设置 → 无障碍 → 开启「DeepSeek Harness 屏幕助手」）"),
          "  无障碍截图(android_see): " + yn(v.canScreenshot) + (v.canScreenshot ? "" : "（需无障碍开启且 Android 11+）"),
          "  特权通道(Shizuku/root): " + yn(v.privileged) + (v.privileged ? "（" + (v.privilegedChannel || "?") + "）" : "（不授权也能用：文件/读屏/中文输入走无障碍与剪贴板）"),
          "  系统操作(装机/改设置/模拟输入 android_input·android_package…): " + yn(v.canSystemOps),
          "  列应用/启动应用(android_apps·android_launch): " + yn(v.canListApps) + (v.canListApps ? "（免特权；后台启动受 Android 10+ 限制）" : ""),
          "  特权方式启动/停止应用(android_app): " + yn(v.canLaunchApps),
          "  虚拟屏服务(android_vscreen_*): " + yn(v.vscreenBridge) + (v.vscreenBridge ? "" : "（打开一次 App 会自动拉起；整个虚拟屏功能还必须有 Shizuku/root）"),
          "  内存(android_mem_status): " + (v.memAvailMb || 0) + "MB 可用 · " + (v.memLevel || "?")
            + "（最近一分钟全屏截图 " + (v.recentFullShots || 0) + " 张）"
        ];
        if (v.hint) lines.push("", "建议: " + v.hint);
        return [{ type: "text", text: lines.join("\n") }];
      }
    },
    async execute(args, exec) {
      const channel = privilegedChannel();
      const privileged = channel !== "";
      // 1) 无障碍状态（读屏 + 截图能力）
      let a11yRunning = false;
      let canScreenshot = false;
      const a11yRaw = await a11yRequest("/status", undefined, 4000);
      const a11y = parseResult(a11yRaw);
      if (a11y.ok) {
        a11yRunning = a11y.running === true;
        canScreenshot = a11y.canScreenshot === true;
      }
      // 2) 虚拟屏桥是否在监听（本 App 的桥服务，未授权特权时也可能在跑，但功能仍不可用）
      const vsPort = parseInt(process.env.DSH_VS_BRIDGE_PORT || "8999", 10);
      const vscreenBridge = await new Promise((resolve) => {
        let done = false;
        const finish = (val) => { if (!done) { done = true; resolve(val); } };
        const req = httpGet({ host: "127.0.0.1", port: vsPort, path: "/vscreen/status", timeout: 1500 }, (res) => {
          res.resume();
          finish(res.statusCode === 200);
        });
        req.on("error", () => finish(false));
        req.on("timeout", () => { req.destroy(); finish(false); });
      });
      const hints = [];
      if (!a11yRunning) hints.push("开启无障碍（系统设置 → 无障碍 → DeepSeek Harness 屏幕助手）后，android_screen/android_tap/android_type/android_see 才可用。");
      // v1.22：内存状况一并报出，并据此给截图策略（全屏截图是内存杀手）
      const mem = await readMem();
      const memLevel = !mem ? "未知"
        : (mem.availKb < FULL_SHOT_MIN_AVAIL_KB ? "紧张"
          : (mem.availKb < FULL_SHOT_WARN_AVAIL_KB ? "偏低" : "充足"));
      if (memLevel === "紧张") {
        hints.push("可用内存紧张（" + mbOf(mem.availKb) + "MB）：**不要**用 android_see 全屏截图（会被拒绝），"
          + "改用 android_see(select=文字) / android_see(region=x,y,w,h) 区域截图或 android_screen 读控件树；"
          + "否则容易触发系统回收 App 与无障碍服务，之后点击/输入会成片失败。");
      } else if (memLevel === "偏低") {
        hints.push("可用内存偏低（" + mbOf(mem.availKb) + "MB）：优先区域截图（android_see 的 select/region 参数），避免连续整屏截图。");
      }
      hints.push("截图省内存的姿势：android_see(select=\"界面上的文字\") 或 region=\"x,y,w,h\"（区域图几十 KB），"
        + "比整屏截图（每张 2~4MB、位图缓冲 ~10MB）省得多；需要大量读界面时优先 android_screen 读控件树。");
      if (!privileged) hints.push("未授予 Shizuku/root：特权工具（android_input/android_package/android_app/android_setting/android_screenshot）不会出现在工具列表；中文输入请用 android_paste_text，截图请用 android_see。");
      hints.push("列应用/启动应用**不需要特权**：用 android_apps 列（走 PackageManager），用 android_launch(package=…) 启动；"
        + "注意 Android 10+ 后台启动 Activity 有限制，App 在前台时最稳；要指定 activity 或在虚拟屏启动仍需 Shizuku/root。");
      if (privileged && !vscreenBridge) hints.push("虚拟屏桥未就绪：打开一次 App 即可拉起（桥在 App 进程内，随 App 启动）。");
      return {
        ok: true,
        a11yRunning,
        canScreenshot,
        privileged,
        privilegedChannel: channel,
        canSystemOps: privileged,
        // v1.19：列应用/启动应用走 App 进程的 PackageManager（targetSdk=28 不受包可见性过滤），
        // 因此**无特权也为真**；受限之处是 Android 10+ 的后台启动 Activity（BAL）。
        canListApps: true,
        canLaunchApps: true,
        vscreenBridge,
        appId: hostAppId(),
        memAvailMb: mem ? mbOf(mem.availKb) : 0,
        memLevel,
        recentFullShots: (() => {
          const now = Date.now();
          fullShotTimes = fullShotTimes.filter((t) => now - t < 60000);
          return fullShotTimes.length;
        })(),
        ...(hints.length ? { hint: hints.join(" ") } : {})
      };
    }
  }));
}

export { apply, inject, name };
