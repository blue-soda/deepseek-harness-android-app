#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
theme_pack_check.py —— DeepSeek Harness 安卓控制台主题包 · 离线校验器（单文件、零依赖）

用法：
    python3 theme_pack_check.py mytheme.zip          # 校验主题包
    python3 theme_pack_check.py console.json         # 也可以只校验一份配置
    python3 theme_pack_check.py mytheme.zip --json   # 机器可读输出（给 AI 自我修正用）

退出码：0 = 通过（可能有警告）  1 = 有错误  2 = 用法/文件问题

规则与 App 内置校验同一套；字段含义见同目录 console.schema.json 与 THEME-PACK-SPEC.md。
"""
import json, os, re, sys, zipfile

MAX_JSON = 512 * 1024
MAX_THEME = 8 * 1024
MAX_IMG = 2 * 1024 * 1024
IMG_EXT = (".png", ".jpg", ".jpeg", ".webp")

COLOR_RE = re.compile(r"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
COLOR_KEYS = ("bg", "card", "text", "sub", "line", "accent", "green", "red", "track")
CARD_IDS = ("extract", "engine", "rescue", "actions", "perm", "plugins", "log", "update", "theme")
# 不可移除：救援面
KEEP_CARDS = ("rescue", "theme")
NODE_TYPES = ("row", "column", "card", "text", "button", "image", "spacer", "divider", "builtin")
BUILTIN_IDS = (
    "extract.block", "extract.status", "extract.detail",
    "engine.status", "engine.buttons",
    "rescue.buttons", "rescue.desc", "theme.card",
    "perm.summary", "perm.list", "plugin.summary", "plugin.list",
    "log.actions", "log.view", "update.button", "version.label", "brand.label",
)
ACTION_SIMPLE = ("engine.start", "engine.restart", "engine.stop", "engine.openUi",
                 "extract.run", "extract.verify", "perm.open", "log.share", "log.clear",
                 "theme.reload", "theme.export", "theme.import", "theme.reset",
                 "clipboard", "toast", "settings")
ACTION_EXEC = ("shell", "http", "intent", "prompt")   # 导入时必须逐个确认
ACTION_ALL = ACTION_SIMPLE + ACTION_EXEC + ("url",)
TEXT_KEY_RE = re.compile(r"^(card|btn|title|status|desc)\.[A-Za-z0-9_.]+$")
IMG_REF_RE = re.compile(r"^([^/\\]+\.(png|jpg|jpeg|webp)|/sdcard/.+)$")

errors, warnings, notes = [], [], []


def err(path, msg): errors.append({"path": path, "msg": msg})
def warn(path, msg): warnings.append({"path": path, "msg": msg})
def note(msg): notes.append(msg)


def is_hex(v): return isinstance(v, str) and bool(COLOR_RE.match(v))
def is_num(v, lo, hi): return isinstance(v, (int, float)) and not isinstance(v, bool) and lo <= v <= hi


def check_colors(obj, path):
    if not isinstance(obj, dict):
        err(path, "应为对象"); return
    for k, v in obj.items():
        if k == "statusBar":
            if v != "auto" and not is_hex(v):
                err(f"{path}.statusBar", '应为 "auto" 或 #rgb/#rrggbb/#aarrggbb')
        elif k in COLOR_KEYS:
            if not is_hex(v):
                err(f"{path}.{k}", "不是合法颜色（支持 #rgb/#rrggbb/#aarrggbb）")
        else:
            err(f"{path}.{k}", f"未知字段（合法：{', '.join(COLOR_KEYS + ('statusBar',))}）")


def check_action(a, path):
    if isinstance(a, list):
        if len(a) > 8 or not a:
            err(path, "动作数组长度应为 1~8")
        for i, x in enumerate(a):
            check_action(x, f"{path}[{i}]")
        return
    if not isinstance(a, dict):
        err(path, "动作应为对象或对象数组"); return
    t = a.get("type")
    if t not in ACTION_ALL:
        err(f"{path}.type", f'未知动作类型 "{t}"（合法：{", ".join(ACTION_ALL)}）'); return
    if t == "shell" and not a.get("cmd"):
        err(f"{path}.cmd", "shell 动作必须给 cmd")
    if t == "url" and not str(a.get("url", "")).startswith(("http://", "https://")):
        err(f"{path}.url", "url 动作必须是 http(s) 地址")
    if t == "intent" and not a.get("action") and not a.get("data"):
        err(path, "intent 动作至少要给 action 或 data")
    if t == "prompt" and not a.get("text"):
        err(f"{path}.text", "prompt 动作必须给 text")
    if t == "http" and not str(a.get("url", "")).startswith("http"):
        err(f"{path}.url", "http 动作必须给 url")
    for k in a:
        if k not in ("id", "icon", "type", "label", "confirm", "continueOnError", "cmd", "privileged",
                     "url", "external", "action", "data", "package", "text", "copy", "session", "mode",
                     "method", "body", "headers"):
            err(f"{path}.{k}", "未知字段")


def check_node(n, path, depth=0):
    if depth > 6:
        err(path, "控件树嵌套过深（>6 层）"); return
    if not isinstance(n, dict):
        err(path, "节点应为对象"); return
    t = n.get("type")
    if t not in NODE_TYPES:
        err(f"{path}.type", f'未知节点类型 "{t}"（合法：{", ".join(NODE_TYPES)}）'); return
    if t == "builtin":
        if n.get("id") not in BUILTIN_IDS:
            err(f"{path}.id", f'未知积木 id "{n.get("id")}"（合法见 console.schema.json 的 BUILTIN 列表）')
    if "action" in n:
        check_action(n["action"], f"{path}.action")
    if "weight" in n and not is_num(n["weight"], 0, 1):
        err(f"{path}.weight", "应在 0~1 之间")
    if "size" in n:
        if t == "spacer":
            # spacer 的 size 是"高度 dp"，6dp 这种小缝很常见 → 单独放开
            if not is_num(n["size"], 1, 200):
                err(f"{path}.size", "spacer 的高度应在 1~200（dp）")
        elif not is_num(n["size"], 8, 28):
            err(f"{path}.size", "应在 8~28 之间（文字 sp / image 高度 dp）")
    for i, c in enumerate(n.get("children", []) or []):
        check_node(c, f"{path}.children[{i}]", depth + 1)


def check_config(cfg):
    if not isinstance(cfg, dict):
        err("$", "顶层应为对象"); return
    for k in cfg:
        if k not in ("schema", "name", "author", "app", "appearance", "layout", "text", "behavior", "actions"):
            err("$", f'未知顶层字段 "{k}"')
    if cfg.get("schema") != 1:
        err("$.schema", "必须为 1（当前 App 只支持 schema 1）")

    ap = cfg.get("appearance")
    if ap is not None:
        if not isinstance(ap, dict):
            err("$.appearance", "应为对象")
        else:
            for k in ap:
                if k not in ("dark", "colors", "perScheme", "fontScale", "radius", "mono",
                             "logo", "cardsAlpha", "background"):
                    err("$.appearance", f'未知字段 "{k}"')
            if "dark" in ap and ap["dark"] not in ("follow", "light", "dark"):
                err("$.appearance.dark", '应为 follow / light / dark')
            if "colors" in ap:
                check_colors(ap["colors"], "$.appearance.colors")
            ps = ap.get("perScheme")
            if ps is not None:
                if not isinstance(ps, dict):
                    err("$.appearance.perScheme", "应为对象")
                else:
                    for sk in ps:
                        if sk not in ("light", "dark"):
                            err("$.appearance.perScheme", f'未知方案 "{sk}"（只有 light / dark）')
                        else:
                            check_colors(ps[sk], f"$.appearance.perScheme.{sk}")
            if "fontScale" in ap and not is_num(ap["fontScale"], 0.8, 1.4):
                err("$.appearance.fontScale", "应在 0.8~1.4")
            if "radius" in ap and not is_num(ap["radius"], 0, 32):
                err("$.appearance.radius", "应在 0~32")
            if "cardsAlpha" in ap and not is_num(ap["cardsAlpha"], 0, 1):
                err("$.appearance.cardsAlpha", "应在 0~1")
            if "logo" in ap and not IMG_REF_RE.match(str(ap["logo"])):
                err("$.appearance.logo", "应为图片文件名（png/jpg/jpeg/webp）或 /sdcard/… 路径")
            bg = ap.get("background")
            if bg is not None:
                if not isinstance(bg, dict):
                    err("$.appearance.background", "应为对象")
                else:
                    for k in bg:
                        if k not in ("image", "fit", "anchor", "opacity", "dim", "blur", "parallax"):
                            err("$.appearance.background", f'未知字段 "{k}"')
                    if "image" in bg and not IMG_REF_RE.match(str(bg["image"])):
                        err("$.appearance.background.image", "应为图片文件名或 /sdcard/… 路径")
                    if "fit" in bg and bg["fit"] not in ("cover", "contain", "stretch", "tile", "center"):
                        err("$.appearance.background.fit", "应为 cover/contain/stretch/tile/center")
                    for k, (lo, hi) in (("opacity", (0, 1)), ("dim", (0, 1)), ("blur", (0, 25))):
                        if k in bg and not is_num(bg[k], lo, hi):
                            err(f"$.appearance.background.{k}", f"应在 {lo}~{hi}")
                    if bg.get("dim", 0.25) == 0 and ap.get("cardsAlpha", 1) == 1 and "image" in bg:
                        warn("$.appearance", "背景图 + dim=0 + cardsAlpha=1：文字可能看不清（风格选择，仅提醒）")
                    if bg.get("blur", 0) and bg["blur"] > 0:
                        note("background.blur 只在 Android 12+ 生效，低版本会忽略该项")

    lay = cfg.get("layout")
    if lay is not None:
        if not isinstance(lay, dict):
            err("$.layout", "应为对象")
        else:
            for k in lay:
                if k not in ("order", "hidden", "defaultPage", "detailOpen", "compact", "pages"):
                    err("$.layout", f'未知字段 "{k}"')
            for key in ("order", "hidden"):
                arr = lay.get(key)
                if arr is None: continue
                if not isinstance(arr, list):
                    err(f"$.layout.{key}", "应为数组"); continue
                for i, cid in enumerate(arr):
                    if cid not in CARD_IDS:
                        err(f"$.layout.{key}[{i}]", f'未知卡片 id "{cid}"（合法：{", ".join(CARD_IDS)}）')
                    elif key == "hidden" and cid in KEEP_CARDS:
                        warn(f"$.layout.hidden[{i}]", f'"{cid}" 是救援面的一部分，不可移除 → 会被忽略')
            if "defaultPage" in lay and not is_num(lay["defaultPage"], 0, 9):
                err("$.layout.defaultPage", "应为 0~9 的整数")
            pgs = lay.get("pages")
            if pgs is not None:
                if not isinstance(pgs, list) or not pgs:
                    err("$.layout.pages", "应为非空数组")
                else:
                    for i, pg in enumerate(pgs):
                        if not isinstance(pg, dict) or "id" not in pg or "children" not in pg:
                            err(f"$.layout.pages[{i}]", "每页必须有 id 与 children"); continue
                        for c in pg["children"]:
                            check_node(c, f"$.layout.pages[{i}].children[{pg['children'].index(c)}]")
                if "order" in lay or "hidden" in lay:
                    warn("$.layout", "同时写了 order/hidden 与 pages：以 pages 为准（order/hidden 被忽略）")
                note("layout.pages 已生效（v1.17.8）：pages[0] = 主控台，其余页自动生成入口；"
                     "树里没有 rescue.buttons/theme.card 时 App 会自动补在末尾")

    txt = cfg.get("text")
    if txt is not None:
        if not isinstance(txt, dict):
            err("$.text", "应为对象")
        else:
            for k, v in txt.items():
                if not TEXT_KEY_RE.match(k):
                    err(f"$.text.{k}", "键名应形如 card.extract / btn.engine.start / title.brand / status.ready")
                if not isinstance(v, str) or len(v) > 40:
                    err(f"$.text.{k}", "文案应为 ≤40 字的字符串")

    if "actions" in cfg:
        note('"actions" 已生效（v1.17.7）：主控台会多出一张「自定义按钮」卡片；'
             'shell 可执行（点击时先弹确认），http/intent/prompt 还没接执行')
    if "behavior" in cfg:
        note('"behavior" 属于 v1 尚未生效的字段：本轮只解析不执行（校验不报错）')
    if isinstance(cfg.get("actions"), list):
        for i, a in enumerate(cfg["actions"]):
            check_action(a, f"$.actions[{i}]")


def load_zip(path):
    """返回 (cfg, theme, files, extra) —— 容忍：只给 console.json / 带一层目录 / 多余文件"""
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        # 找 console.json / theme.json（允许一层目录前缀）
        def find(base):
            cands = [n for n in names if os.path.basename(n) == base]
            if not cands: return None
            cands.sort(key=lambda n: n.count("/"))
            return cands[0]
        cj, tj = find("console.json"), find("theme.json")
        if cj is None:
            err("zip", "包里没有 console.json"); return None, None, [], []
        if z.getinfo(cj).file_size > MAX_JSON:
            err("console.json", f"超过 {MAX_JSON//1024}KB 上限")
        try:
            cfg = json.loads(z.read(cj).decode("utf-8-sig"))
        except Exception as e:
            err("console.json", f"JSON 解析失败：{e}"); return None, None, [], []
        theme = None
        if tj is not None:
            try:
                theme = json.loads(z.read(tj).decode("utf-8-sig"))
            except Exception as e:
                err("theme.json", f"JSON 解析失败：{e}")
        # 图片
        prefix = os.path.dirname(cj)
        imgs, extra = [], []
        for n in names:
            if n in (cj, tj): continue
            rel = n[len(prefix) + 1:] if prefix and n.startswith(prefix + "/") else n
            if rel.lower().endswith(IMG_EXT):
                if z.getinfo(n).file_size > MAX_IMG:
                    err(rel, f"图片超过 {MAX_IMG//1024//1024}MB 上限")
                imgs.append(rel)
            else:
                extra.append(n)
        return cfg, theme, imgs, extra


def check_images(cfg, imgs, in_zip=True):
    """背景图/logo 引用的文件必须在包里（或写成 /sdcard 绝对路径）。
    只校验一份裸 console.json 时没有"包"的概念 → 降级成提示，不报错。"""
    ap = (cfg or {}).get("appearance") or {}
    refs = []
    if isinstance(ap.get("logo"), str): refs.append(("$.appearance.logo", ap["logo"]))
    bg = ap.get("background") or {}
    if isinstance(bg.get("image"), str): refs.append(("$.appearance.background.image", bg["image"]))
    base = {os.path.basename(i).lower() for i in imgs}
    for path, ref in refs:
        if ref.startswith("/sdcard/"):
            note(f"{path} 用的是绝对路径 {ref}（不随主题包分发，换设备会失效）")
        elif not in_zip:
            note(f"{path} 引用了 {ref}：记得把它和 console.json 放在同一目录（或写 /sdcard/… 绝对路径）")
        elif os.path.basename(ref).lower() not in base:
            err(path, f'引用了 "{ref}"，但包里没有这个图片文件（包内图片：{", ".join(imgs) or "无"}）')


def main():
    # ⚠️ Windows 下 stdout 默认是 GBK(cp936)：AI/脚本按 UTF-8 读会乱码或直接抛 UnicodeDecodeError。
    # 这里强制 UTF-8，让输出在任何平台都能被机器直接解析。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    as_json = "--json" in sys.argv
    if not args:
        print(__doc__.strip()); return 2
    src = args[0]
    if not os.path.isfile(src):
        print(f"找不到文件：{src}"); return 2

    theme, imgs, extra = None, [], []
    if src.lower().endswith(".zip"):
        cfg, theme, imgs, extra = load_zip(src)
        if extra:
            note(f"包里这些文件会被忽略（无害）：{', '.join(extra[:6])}{' …' if len(extra) > 6 else ''}")
        if theme is not None:
            if not isinstance(theme, dict) or "schema" not in theme:
                warn("theme.json", "缺少 schema 字段")
            if theme.get("schema") not in (None, 1):
                err("theme.json", "schema 版本不是 1")
            if not theme.get("name") and not (cfg or {}).get("name"):
                warn("$", "没给主题名（theme.json.name 与 console.json.name 都空）")
        for i in imgs:
            note(f"包内图片：{i}")
    else:
        try:
            with open(src, "r", encoding="utf-8-sig") as f:
                cfg = json.load(f)
        except Exception as e:
            print(f"JSON 解析失败：{e}"); return 1

    if cfg is not None:
        check_config(cfg)
        check_images(cfg, imgs, in_zip=src.lower().endswith(".zip"))

    exec_actions = [a for a in ((cfg or {}).get("actions") or []) if isinstance(a, dict) and a.get("type") in ACTION_EXEC]
    if exec_actions:
        warn("$.actions", f"含 {len(exec_actions)} 个可执行动作（shell/http/intent/prompt）："
                          f"界面上是红框按钮，点击时会先弹确认框")

    ok = not errors
    if as_json:
        print(json.dumps({"ok": ok, "errors": errors, "warnings": warnings, "notes": notes},
                         ensure_ascii=False, indent=2))
    else:
        name = (theme or {}).get("name") or (cfg or {}).get("name") or os.path.basename(src)
        print(f"主题包校验：{name}")
        print(f"  文件：{src}")
        for e in errors:   print(f"  ✗ [{e['path']}] {e['msg']}")
        for w in warnings: print(f"  ⚠ [{w['path']}] {w['msg']}")
        for n in notes:    print(f"  · {n}")
        print(f"\n{'通过 ✓' if ok else '不通过 ✗'} —— 错误 {len(errors)} / 警告 {len(warnings)}")
        if ok:
            print("可以直接导入：控制台 →「主题」页 → 导入主题包")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
