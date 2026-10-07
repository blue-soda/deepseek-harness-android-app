# 控制台主题包规范（THEME-PACK-SPEC）

> 适用：DeepSeek Harness 安卓版（`com.deepseek.harness` / `.beta` / `.compat`）· schema **1** · App ≥ 1.17.3
> 你可以是**人**，也可以是**AI**：照本文产出一个 zip，用户导入即可换掉 App 冷启动那个原生控制台的外观/布局/文案。
> 机器可读版：同目录 `console.schema.json`；示例：`console.example.json`；自检：`theme_pack_check.py`。

## 0. 三十秒上手（给 AI 的最短路径）

1. 读同目录的 `console.example.json`（能直接用的完整例子）与 `console.schema.json`（字段与合法值）；
2. 按用户要求写一份 `console.json`；
3. 打包：`zip mytheme.zip console.json`（要带背景图就一起塞进去）；
4. 自检：`python3 theme_pack_check.py mytheme.zip` → 必须 `通过 ✓`；
5. 交付给用户：他在 **控制台 →「主题」页 → 导入主题包** 选中这个 zip。

> 也可以**不打包**：直接把 `console.json` 放进 `/sdcard/DeepSeekHarness/console/` 就生效（AI 直接写文件时最省事）。

## 1. 文件放在哪

```
/sdcard/DeepSeekHarness/                 ← 正式版（Lite: /sdcard/DeepSeekHarnessLite，兼容版: …Compat）
├── console/
│   ├── console.json          ← 唯一真相来源（schema 1）
│   ├── bg.jpg / logo.png     ← 可选：背景图 / 品牌图标
│   ├── console.json.bak      ← App 每次成功加载前自动备份
│   ├── last-error.txt        ← 最近一次回退的原因
│   └── THEME-PACK-SPEC.md · console.schema.json · theme_pack_check.py   ← 本规范三件套（随 APK 落盘）
└── console-theme-<名字>.zip  ← 「导出主题包」的输出
```

- **热重载**：App 每次渲染控制台前按文件 mtime 判断是否变了 → 改完**不用重启 App**。
- **坏配置永不锁死 App**：语法错/取值非法只会**回退默认**并在控制台顶部提示，配置文件会改名留底（`.bak`）。

## 2. 主题包格式

```
mytheme.zip
├── console.json     必需   配置本体（≤512KB）
├── theme.json       可选   {"schema":1,"name":"主题名","author":"你","app":"1.17.3+"}（≤8KB）
└── bg.jpg           可选   背景图 / logo（PNG·JPG·WebP，单张 ≤2MB）
```

**打包容错（故意放宽，避免 AI 生成的包被无谓拒绝）**：

| 情形 | 是否接受 |
|---|---|
| 只有 `console.json`，没有 `theme.json` | ✅（主题名取 `console.json` 的 `name` 或文件名） |
| 多塞了 `README.md`、`LICENSE` 等 | ✅（忽略，自检里提示一句） |
| 包了一层目录（`mytheme/console.json`） | ✅ |
| 背景图/logo 用 `/sdcard/...` 绝对路径 | ✅（但换设备会失效，自检里会提示） |
| 没打包，直接给 `console.json` | ✅ |

**打包命令**（任选）：

```bash
zip mytheme.zip theme.json console.json bg.jpg            # 有 zip 命令
python3 -c "import zipfile;z=zipfile.ZipFile('mytheme.zip','w');[z.write(f) for f in ('theme.json','console.json','bg.jpg')];z.close()"
```

## 3. 字段速查（完整约束见 `console.schema.json`）

```jsonc
{
  "schema": 1,                     // 必须=1
  "name": "海边的夜",               // 主题名（主题页会显示）
  "author": "AI",  "app": "1.17.3+",

  "appearance": {
    "dark": "follow",              // follow | light | dark（控制台自身深浅色）
    "colors": {                    // 单套色（深浅共用）；被 perScheme 覆盖
      "bg": "#0f1319", "card": "#161c26", "text": "#e8ecf3", "sub": "#8b95a7",
      "line": "#232a38", "accent": "#4d6bfe", "green": "#31c48d", "red": "#f05252",
      "track": "#1f2733", "statusBar": "auto"        // auto 或颜色
    },
    "perScheme": { "light": { /* 同上字段 */ }, "dark": { /* … */ } },
    "fontScale": 1.0,              // 0.8~1.4（文字整体缩放）
    "radius": 14,                  // 0~32（卡片/按钮圆角 dp）
    "mono": false,                 // 日志页等宽字体
    "logo": "logo.png",            // 品牌图标（相对 console/ 或 /sdcard/…）
    "cardsAlpha": 1.0,             // 0~1 卡片不透明度（放背景图时调低）
    "background": {                // 背景图
      "image": "bg.jpg",           // 文件名或 /sdcard/… 绝对路径
      "fit": "cover",              // cover | contain | stretch | tile | center
      "anchor": "center",          // center|top|bottom|left|right|四个角
      "opacity": 0.4,              // 0~1 图片透明度
      "dim": 0.25,                 // 0~1 再压一层暗色（保证文字可读）
      "blur": 0,                   // 0~25，**仅 Android 12+ 生效**
      "parallax": false            // 滚动时背景是否跟随
    }
  },

  "layout": {                      // ✅ 已生效（v1.17.5 起）
    "order": ["extract","engine","rescue","perm","plugins","log","update"],
    "hidden": ["update"],          // ⚠ rescue 与 theme 不可隐藏（救援面）
    "defaultPage": 0,              // 0 控制台 / 1 权限 / 2 插件 / 3 日志 / 4 主题
    "detailOpen": false, "compact": false,
    "pages": [ /* ⏭ 下一轮：声明式控件树，见 §5 */ ]
  },

  "text": {                        // ✅ 已生效（v1.17.5 起）：键 → 新文案（≤40 字），键表见 §4
    "card.extract": "文件", "btn.engine.start": "点火", "title.brand": "DEEPSEEK HARNESS"
  },

  "behavior": { /* ⏭ 下一轮 */ },  // autoExtract / autoStartEngine / enterMainUi / hideButtons / doubleBackExit
  "actions":  [ /* ⏭ 下一轮：自定义按钮，见 §5 */ ]
}
```

**颜色格式**：`#rgb`、`#rrggbb`、`#aarrggbb`（都带 `#`）。**没有** `rgb()`/颜色名/`transparent`。

## 4. 卡片 id / 积木 id / 动作类型（AI 只用记这三张表）

**卡片 id（`layout.order` / `layout.hidden`）**

| id | 是什么 | 可隐藏 |
|---|---|---|
| `extract` | 解压文件（状态 + 进度条 + 按钮） | ✅ |
| `engine` | 启动引擎（状态 + 启动/重启/停止） | ✅ |
| `rescue` | 救援（安全模式 / 导出全部数据 / 从备份导入还原） | ❌ **不可** |
| `actions` | **自定义按钮**（`actions` 里配的按钮；没配就不显示） | ✅ |
| `perm` | 授予权限（8 项） | ✅ |
| `plugins` | 插件开关 | ✅ |
| `log` | 日志（查看/分享/清空） | ✅ |
| `update` | 检查更新 | ✅ |
| `theme` | 主题页 | ❌ **不可**（它是导入主题的入口，删了就换不回来了） |
| `selfcheck` | 内核自检（清单式一致性证明 · 自愈账本） | ❌ **不可**（自修复的唯一入口，隐藏了就没法跑自检） |
| `browser` | AI 浏览器（同屏查看 · 页签列表 · 关闭浏览器） | ✅ （关掉只是少了同屏入口，不影响自救） |
| `env` | 运行环境（重新解压 / 校验文件的常驻入口） | ❌ **不可**（v1.19.7 起进 KEEP_CARDS：修坏树的一键入口） |
| `sessionadmin` | 会话管理行（**新版骨架**专有） | ✅ |
| `sessionheal` | 会话修复行（**新版骨架**专有） | ✅ |
| `group.sessions` | 「会话」分组小标题（**新版骨架**专有） | ✅ |
| `group.system` | 「系统」分组小标题（**新版骨架**专有） | ✅ |
| `group.diagnose` | 「诊断」分组小标题（**新版骨架**专有） | ✅ |

### v1.19.7 · **新版骨架也吃主题布局**（`order` / `hidden` / `actions` 全适配）
v1.19.6 起主控台缺省走**新版骨架**，但它是硬编码的 ⇒ 主题的
`layout.order`（排序）、`layout.hidden`（隐藏卡片）、`actions`（自定义按钮）**在新版下静默失效**。
v1.19.7 起全部接上：

- **条目化**：新版骨架的每一行都有 id（上表），`order` / `hidden` 对它们与对 classic 卡片**同一套语义**；
- **分组标题自动收**：某一组里的行全被隐藏时，标题自己也不再出现；
- **`actions` 在新版下会渲染**（以前只有 classic 有）；
- **表头与页脚固定**、不参与排序（与 classic 一致）；
- `extract` 在新版里是**主操作块**（环境+引擎+主按钮+详情），受保护、不可隐藏
  （`hidden` 里写 `extract` 或 `engine` 都会被忽略）；
- 只想用旧卡片平铺？写 `"layout": {"style": "classic"}`。

**文案键表（`text` 里能用的键；没给的键用 App 内置中文）**

> 键名规则：`card.*` / `btn.*` / `title.*` / `status.*` / `desc.*`，值 ≤40 字。
> 下表的键 **v1.17.5 起真的会生效**；写表以外的键不报错，只是不起作用（自检会给 note）。

| 键 | 默认中文 | 出现在哪 |
|---|---|---|
| `title.brand` | `DEEPSEEK HARNESS` | 控制台左上角品牌字 |
| `card.extract` | （解压卡片没有静态标题，标题就是状态行） | — |
| `status.extract.idle` / `.done` / `.running` | `未解压` / `已解压` / `正在解压…` | 解压卡片状态行 |
| `desc.extract` | `需要解压运行环境与内核（约 2.5 万个文件 / 约 220 MB）；解压完成后才能启动引擎。` | 解压卡片说明 |
| `status.extract.working` | `正在解压运行时与内核树…` | 解压进行中的说明行 |
| `btn.extract.run` / `btn.extract.rerun` | `解压文件` / `重新解压` | 解压按钮 |
| `status.engine.idle` / `.ready` / `.starting` | `未启动` / `引擎运行中` / `启动中…` | 引擎卡片状态行 |
| `btn.engine.start` / `.openUi` / `.restart` / `.stop` | `启动引擎` / `打开主界面` / `重启` / `停止` | 引擎三个按钮 |
| `card.rescue` | `救援` | 救援卡片标题 |
| `status.rescue.safe` | `安全模式：已开启` | 安全模式生效时的标题 |
| `desc.rescue` | `引擎起不来时，用安全模式跳过用户层启动（不丢数据）；也可以随时导出全部数据做备份。` | 救援说明（默认那条） |
| `btn.rescue.safe` / `.exitSafe` / `.export` / `.import` | `安全模式启动` / `退出安全模式` / `导出全部数据` / `从备份导入还原` | 救援按钮 |
| `card.perm` | `授予权限` | 权限导航行标题 |
| `desc.perm` | `存储 · 通知 · 悬浮窗 · 电池 · root · Shizuku · 无障碍` | 权限导航行副标题 |
| `card.plugins` | `插件` | 插件导航行标题 |
| `desc.plugins` | `关掉用不到的，省上下文` | 插件导航行副标题 |
| `card.log` | `日志` | 日志行标题 |
| `btn.log.view` / `.share` / `.clear` | `查看` / `分享` / `清空` | 日志行按钮 |
| `card.update` | `检查更新` | 检查更新行 |
| `status.ready` / `.serving` / `.starting` | `就绪` / `本地服务已就绪` / `正在启动引擎…` | 底部状态行（三态） |
| `desc.footer` | `换内核版本 / 覆盖安装后需要重新解压；平时只用到「启动引擎」。` | 底部说明 |
| `card.actions` | `自定义按钮` | 自定义按钮卡片标题（配了 actions 才有这张卡） |
| `desc.actions` | `来自主题配置；带红框的按钮执行前会先让你确认。` | 同上，副标题 |
| `card.theme` | `主题` | 主控台的「主题」导航行（**不可隐藏**） |
| `desc.theme` | `外观 / 布局 / 文案都由 console.json 决定 · 点这里导入导出` | 同上，副标题 |
| `status.theme.builtin` / `.reverted` / `.fatal` | `内置默认` / ` 项已回退` / `已整体回退` | 主题行右侧的状态（拼在主题名后面） |
| `title.themePage` | `主题` | 主题页标题 |
| `desc.themePage` | （一句说明） | 主题页开头那段话 |
| `title.themeStatus` | `当前主题` | 主题页第①块标题 |
| `btn.theme.reload` / `.export` / `.import` / `.reset` / `.openDir` | `重新加载` / `导出主题包` / `导入主题包` / `恢复默认` / `打开主题目录` | 主题页五个操作 |
| `title.themeAi` | `让 AI 做主题包` | 主题页第②块标题 |
| `desc.themeAi` | （一句说明） | 同上说明 |
| `btn.theme.copy` | `复制` | 每条提示词右侧的复制按钮 |
| `title.themeSpec` | `规范与自检` | 主题页第③块标题 |
| `desc.themeSpec` | （一句说明） | 同上说明 |
| `btn.theme.openSpec` / `.copyCheck` | `打开规范` / `复制自检命令` | 第③块两个按钮 |
| `title.themeActions` | `可执行动作（本版不会执行）` | 第④块标题（仅当配置里有 shell/http/intent/prompt 动作时出现） |
| `title.grpSessions` / `.grpSystem` / `.grpDiagnose` | `会话` / `系统` / `诊断` | 主控台（精简版）的三个分组小标题（v1.19.5 起） |
| `card.sessionadmin` | `会话管理` | 会话管理入口行标题（v1.19.4 起） |
| `desc.sessionadmin` | `删除不需要的会话 · 先进回收站，可恢复` | 同上，副标题 |
| `card.sessionheal` | `会话修复` | 会话修复入口行标题 |
| `desc.sessionheal` | `坏图毒死的会话：只降级那一条消息` | 同上，副标题 |
| `btn.rescue` | `救援` | 精简版页脚的救援按钮（打开安全模式/导出/导入） |
| ~~`btn.console.classic`~~ | ~~`完整版`~~ | **已下线（v1.19.6）**：主控台只有新版一套；右上角那个"切回旧卡片版"的按钮已移除，旧版改由 `layout.style:"classic"` 切换 |
| `btn.shellTheme` | `界面主题` | 页脚的界面主题按钮 / 导航行 |
| **v1.19.6 · 主控台** | | |
| `title.grpEnv` | `运行环境` | 主控台「系统」组的**常驻**入口行（解压 / 重新解压 / 校验文件） |
| `btn.extract.verify` | `校验文件` | 「运行环境」弹窗里的校验按钮 |
| `btn.extract.detail` | `看详情` | 「运行环境」弹窗里的详情按钮 |
| **v1.19.6 · 权限页** | | |
| `desc.permPage` | `点任意一行去授权 / 管理。root 与 Shizuku 二选一即可（root 优先）。` | 权限页顶部说明 |
| `title.grpPermBasic` | `基本权限` | 权限页分组小标题 |
| `title.grpPermPriv` | `特权通道（二选一）` | 权限页分组小标题 |
| `title.grpPermMore` | `其它` | 权限页分组小标题 |
| `status.perm.granted` / `.noroot` / `.denied` | `已授权` / `本机无 root` / `未授权` | 权限行右列的短状态（整行可点） |
| **v1.19.6 · 插件页** | | |
| `desc.pluginsPage` | `关掉的插件不加载：工具不进 AI 的工具表，也少占上下文。改动在重启引擎后生效。` | 插件页顶部说明 |
| `title.grpPlugList` | `插件列表` | 插件页分组小标题（后面自动接「· 已启用 N / M」） |
| `btn.plugins.restart` | `重启引擎生效` | 插件页底部主按钮 |
| **v1.19.6 · 日志页** | | |
| `desc.logPage` | `「查看」直接看末尾 200 行；「分享」调用系统分享……` | 日志页顶部说明 |
| `title.grpLogFile` | `日志文件` | 日志页分组小标题 |
| `desc.logClear` | `只清日志，不影响正在运行的引擎；之后的新日志会继续写入` | 「清空日志」行副标题 |
| `btn.log.clearShort` | `清空` | 「清空日志」行右列 |
| `title.grpLogCare` | `维护` | 日志页「维护」分组小标题 |
| **v1.19.6 · 主题页** | | |
| `title.grpThemeIo` | `导入 / 导出` | 主题页分组小标题 |
| `desc.themeExport` | `把当前主题打成 zip（含 console.json 与图片），导出后可直接分享` | 「导出主题包」行副标题 |
| `desc.themeImport` | `从 zip 装一份主题；现有配置会先备份，可反悔` | 「导入主题包」行副标题 |
| `btn.theme.backups` | `我保存过的主题` | 恢复旧配置的入口行标题（后面自动接「（N）」） |
| `desc.themeBackups` | `恢复默认 / 导入之前的旧配置都收在这里，点一下直接恢复` | 同上副标题 |
| `desc.themeSpecShort` | `THEME-PACK-SPEC.md · 含卡片 / 积木 / 动作三张 id 表` | 「打开规范」行副标题 |
| `desc.themeDir` | `console.json、背景图与规范都放在这里` | 「打开主题目录」行副标题 |
| `desc.themeDiag` | `看解析结果、回退项与布局页数` | 「诊断」行副标题 |
| `title.grpThemeCare` | `维护` | 主题页「维护」分组小标题 |
| `desc.themeReset` | `把 console.json 改名留底（不删）；之后可从「我保存过的主题」恢复` | 「恢复默认主题」行副标题 |
| `btn.theme.resetShort` | `恢复` | 「恢复默认主题」行右列（红字） |
| **v1.19.6 · 内核自检页** | | |
| `title.grpSelfCheckActions` | `动作` | 自检页「动作」分组小标题 |
| `btn.selfcheck.quick` | `快速自检` | 自检页主按钮（只比大小，秒级） |
| `btn.selfcheck.full` | `全量校验` | 自检页次按钮（逐个 sha256，约 200s） |
| `btn.selfcheck.ledger` | `看账本` | 自检页次按钮 |
| `btn.selfcheck.snapshot` | `配置快照` | 自检页次按钮（唯一会写盘的动作，走红框确认） |
| **v1.19.6 · 会话管理页** | | |
| `desc.sessionadminPage` | `这里可以删掉不需要的会话。删除是「先移到回收站」—— 随时能恢复；真…` | 会话管理页顶部说明 |
| `title.grpSessionList` | `会话列表` | 会话管理页分组小标题 |
| `status.lastOp` | `上次操作` | 「上次操作」结论卡标题（会话管理 / 回收站共用） |
| `status.done` | `完成` | 结论卡右列短状态（绿） |
| `status.failed` | `失败` | 结论卡右列短状态（红） |
| `status.readFailed` | `读取失败` | 列表 / 回收站读取失败时的行标题 |
| `status.sessionNone` | `一条会话都没有` | 一条会话都没有时的行标题 |
| `desc.sessionNone` | `在 Web 界面里发一条消息，这里就会出现它` | 同上行副标题 |
| `desc.sessionadminIdle` | `还没有读取过列表。点下面的「读取会话列表」开始 —— 只读。` | 还没读取过列表时的说明段 |
| `btn.session.load` | `读取会话列表` | 操作区主按钮（首次进入） |
| `btn.session.reload` | `重新读取` | 操作区主按钮（已有数据） |
| `btn.session.more` | `显示更多` | 「显示更多」按钮（后面自动接「（还有 N 条）」） |
| `btn.session.delete` | `删除` | 会话行右列（红字 + ›，整行可点；点了是确认框，不会直接删） |
| `desc.sessionadminWarn` | `边界（写死在代码里）：① 删除 = 移到 <dshHome>/ses…` | 会话管理页页尾「边界」说明 |
| **v1.19.6 · 回收站页** | | |
| `card.sessiontrash` | `回收站` | 回收站页返回行标题；也是主控台里那个带条数的入口按钮 |
| `desc.sessiontrashPage` | `删掉的会话先放在这里（在 sessions/ 之外，内核看不到它们）…` | 回收站页顶部说明 |
| `title.grpTrashList` | `回收站里的会话` | 回收站页分组小标题 |
| `status.trashEmpty` | `回收站是空的` | 回收站为空时的行标题 |
| `desc.trashEmptyHint` | `删掉的会话会先出现在这里` | 同上行副标题 |
| `desc.sessiontrashIdle` | `还没有读取过回收站。点下面的「读取回收站」。` | 还没读取过回收站时的说明段 |
| `btn.session.loadTrash` | `读取回收站` | 操作区主按钮 |
| `btn.session.backAdmin` | `回会话管理` | 操作区次按钮 |
| `btn.session.restore` | `恢复` | 回收站行右列（实心主操作，走确认框） |
| `btn.session.purge` | `彻底删除` | 回收站行右列（红字，走红框确认，不可恢复） |
| `title.grpTrashCare` | `维护` | 回收站「维护」分组小标题 |
| `btn.session.purgeAll` | `清空回收站` | 清空回收站行标题（后面自动接「（N 条 · 体积）」） |
| `desc.sessionPurgeAll` | `把回收站里的会话一次性真正删掉，删完找不回来` | 同上行副标题 |
| `btn.session.purgeAllShort` | `清空` | 同上行右列（红字 + ›，点击即红框确认） |
| `desc.sessiontrashWarn` | `「彻底删除」会真的从磁盘删掉，不可恢复 —— 所以它只在这里提供，且…` | 回收站页页尾说明 |
| **v1.19.6 · 会话修复页** | | |
| `desc.sessionhealPage` | `坏附件（比如内容已经损坏的图片）一旦写进会话历史，之后每次发消息都会…` | 会话修复页顶部说明 |
| `status.lastHeal` | `上次修复` | 「上次修复」结论卡标题 |
| `status.noChange` | `无改动` | 结论卡右列短状态（没有需要修的地方） |
| `desc.sessionhealIdle` | `还没有扫描过。点下面的「扫描会话」开始 —— 扫描全程只读。` | 还没扫描过时的说明段 |
| `status.scanFailed` | `扫描失败` | 扫描失败时的行标题 |
| `title.grpSessionBad` | `扫描结果` | 扫描失败时的分组小标题 |
| `title.grpSessionBadList` | `有问题的会话` | 有问题的会话列表分组小标题 |
| `desc.noBadSession` | `所有会话的附件引用都能对上实体文件、内容也完整 —— 不需要修什么。` | 结论卡副标题（一条坏的都没有） |
| `desc.sessionBadHint` | `点一条查看它坏在哪、再决定要不要修（只会动你点的这一条）：` | 结论卡副标题（有坏的，提示点一条看详情） |
| `desc.sessionNormalOther` | `条会话没有发现问题（未在下面列出）。` | 列表下方补一句（前面自动接「另有 N 」） |
| `btn.session.scan` | `扫描会话` | 页脚主按钮（首次） |
| `btn.session.rescan` | `重新扫描` | 页脚主按钮（已扫描过） |
| `desc.sessionhealWarn` | `边界（写死在代码里）：① 只处理你点选的那一条，没有「批量修复」这种…` | 会话修复页页尾「边界」说明 |
| **v1.19.6 · 内核自检页（续）** | | |
| `title.grpLedger` | `自愈账本` | 自检页「自愈账本」分组小标题 |
| `card.ledger` | `自愈账本` | 账本卡片行标题 |
| `desc.ledger` | `每次自检与修复追加一条 JSON，AI 可以直接读它向你解释修过什么` | 账本卡片行副标题（后面自动接账本摘要） |
| `desc.ledgerDir` | `目录：` | 账本卡片下方路径说明（后面自动接目录） |
| `title.grpHealProposal` | `AI 修复提案` | 自检页「AI 修复提案」分组小标题 |
| `desc.healProposal` | `AI 读自检结果与账本后，可以把判断与建议写成 /sdcard/` | 提案区顶部说明 |
| `status.noProposal` | `当前没有提案文件` | 没有提案文件时的行标题 |
| `desc.noProposal` | `AI 写出提案后，这里会出现它、并列出它想做什么` | 同上行副标题 |
| `desc.healFingerprint` | `源指纹：` | 提案行里「源指纹」的前缀 |
| `desc.healNoFingerprint` | `（提案未提供 → 会跳过过期检查）` | 提案没给源指纹时的提示 |
| `btn.heal.run` | `执行（需确认）` | 提案行右列按钮（走确认框） |
| `btn.heal.notRunnable` | `不可执行` | 动作不在白名单时的按钮（红字，点了不执行） |
| `desc.healProposalWarn` | `⚠ 执行会改动内核树（只碰内核自己的 @deepseek-ai 命名…` | 提案区页尾警告 |
| `desc.selfcheckWarn` | `边界（写死在代码里）：① 自检「只读」，不删不改；② 「缺失/内容不…` | 自检页页尾「边界」说明 |
| **v1.19.6 · AI 浏览器页（阶段 C）** | | |
| `card.browser` | `AI 浏览器` | 主控台「系统」组那一行的标题；浏览器页返回行；卡片版标题 |
| `desc.browser` | `看 AI 正在哪一页 · 同屏查看（画面可直接操作，拖顶部小条搬窗）` | 主控台那一行的副标题 |
| `desc.browserPage` | `AI 浏览器跑在独立进程里：页面崩了不会带走控制台和引擎。这里能看它…` | 浏览器页顶部说明 |
| `title.grpBrowserState` | `浏览器状态` | 浏览器页「浏览器状态」分组小标题 |
| `title.grpBrowserTabs` | `页签` | 浏览器页「页签」分组小标题 |
| `status.browserIdle` | `未运行` | 状态行 / 右列短状态（没有活的浏览器） |
| `status.browserRunning` | `运行中` | 状态行 / 右列短状态（有活的浏览器） |
| `status.browserBusy` | `查询中…` | 上一次 op 还没回来时的状态行 + 提示 |
| `status.browserUnreachable` | `读不到状态` | 读不到状态时的右列短状态（红/次要色） |
| `status.browserPanelOn` | `同屏中` | 状态行后缀（同屏正在显示） |
| `status.browserPanelOff` | `同屏关` | 状态行后缀（同屏没显示） |
| `status.browserNoTab` | `还没有页签` | 一个页签都没有时的行标题 |
| `status.browserActive` | `当前` | 页签行的右列短状态（当前活动页签） |
| `btn.browser.panel` | `同屏查看` | 「同屏查看」开关行标题（开关本体写死「已启用/已关闭」） |
| `btn.browser.reload` | `刷新` | 页脚次按钮（重新查一次状态与页签） |
| `btn.browser.close` | `关闭浏览器` | 页脚主按钮 + 确认框标题/确认按钮 |
| `desc.browserPanel` | `把画面显示成一块浮窗 · 拖顶部小条移动、点小条关掉 · 画面可直接操作` | 「同屏查看」行的副标题 |
| `desc.browserNoTab` | `让 AI 打开一个网页后，这里会列出它的页签` | 「还没有页签」行的副标题 |
| `desc.browserClose` | `关掉全部页签并回收浏览器进程（不影响引擎）` | 关闭确认框正文 |
| `desc.browserWarn` | `边界（写死在代码里）：① 同屏画面可以直接操作（点画面＝点网页），拖顶部小条搬窗、点小条关掉；② 不改窗口尺寸…` | 浏览器页页尾「边界」说明 |
| `status.browserPluginOff` | `插件已关闭` | 浏览器页「插件已关闭」提示行标题（整行可点 → 插件页） |
| `desc.browserPluginOff` | `AI 现在用不了浏览器工具 · 去「插件」页打开 dsh-tool-…` | 同上行副标题 |

**布局字段速查（`layout`）**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `order` | 卡片 id 数组 | 内置顺序 | 只写想调的部分也行：**没列出的卡片按内置顺序排在后面** |
| `hidden` | 卡片 id 数组 | `[]` | `rescue` 写进去会被忽略（自检 warning）——救援面不可移除 |
| `defaultPage` | 0~9 | `0` | 本版只认 0~3（4=主题页属下一轮） |
| `detailOpen` | bool | `false` | 「已解压」详情是否默认展开 |
| `compact` | bool | `false` | 紧凑模式：卡片间距/控制台上下留白减半 |

**内置积木 id（`type:"builtin"` 的 `id`，下一轮生效）**`extract.block` `extract.status` `extract.detail` `engine.status` `engine.buttons` `rescue.buttons` `rescue.desc`
`theme.card` `perm.summary` `perm.list` `plugin.summary` `plugin.list` `log.actions` `log.view`
`update.button` `version.label` `brand.label`

**动作类型（`action.type`，下一轮生效）**
内置：`engine.start` `engine.restart` `engine.stop` `engine.openUi` `extract.run` `extract.verify`
`perm.open` `log.share` `log.clear` `theme.reload` `theme.export` `theme.import` `theme.reset`
通用：`url`（开网址）· `clipboard` · `toast` · `settings`（本 App 系统设置页）
**可执行（点按钮时会先弹确认，把命令/网址/提示词原样摆给你看）**：`shell` · `http` · `intent` · `prompt`

### 4.6 自定义按钮（`actions`，✅ v1.17.7 起生效）

```jsonc
"actions": [
  { "id": "docs",   "label": "打开文档", "type": "url", "url": "https://github.com/woaiys3/deepseek-harness-android-app" },
  { "id": "copy",   "label": "复制密钥", "type": "clipboard", "copy": "token" },
  { "id": "reboot", "label": "重开引擎", "type": "engine.restart" },
  { "id": "model",  "label": "看机型",   "type": "shell", "cmd": "getprop ro.product.model", "confirm": true },
  { "id": "hint",   "label": "打个招呼", "type": "toast", "text": "你好呀" }
]
```

- 配了 `actions` → 主控台多出一张**「自定义按钮」卡片**（卡片 id = `actions`，可用 `layout.order`/`hidden` 调整，
  没配 `actions` 时这张卡片不出现）；
- **哪些现在真的会执行**：内置动作（`engine.*` / `extract.*` / `perm.open` / `log.*` / `theme.*`）、
  `url`、`clipboard`、`toast`、`settings`、**`shell`**（`privileged: true` 走 Shizuku/root，否则 App 身份执行，结果弹窗展示）；
- **还没接执行的**：`http`、`intent`、`prompt`（点它会说明"还没接"，`prompt` 会退化成"把提示词复制到剪贴板"）；
- **安全**：`shell` / `http` / `intent` / `prompt` 这四类按钮**在界面上是红描边**，
  点击后**先弹确认框**把要执行的内容原样摆出来 —— AI 生成的包不可能静默执行任何东西。

### 4.7 声明式布局（`layout.pages`，✅ v1.17.8 起生效）

**整页自己拼**：`pages[0]` 就是主控台（不再用内置卡片布局），`pages[1]`、`pages[2]`… 变成额外页
（自动获得「‹ 返回」和主控台底部的入口行）。

```jsonc
"layout": { "pages": [
  { "id": "main", "title": "控制台", "children": [
      { "type": "builtin", "id": "engine.status" },
      { "type": "row", "children": [
          { "type": "button", "text": "点火", "style": "primary", "weight": 1,
            "action": { "type": "engine.start" } },
          { "type": "button", "text": "重启", "action": { "type": "engine.restart" } },
          { "type": "button", "text": "停",   "action": { "type": "engine.stop" } }
      ]},
      { "type": "spacer", "size": 14 },
      { "type": "text", "text": "下面这排是我自己加的", "size": 11, "color": "sub" },
      { "type": "row", "children": [
          { "type": "button", "text": "打开文档", "action": { "type": "url", "url": "https://example.com" } }
      ]},
      { "type": "builtin", "id": "extract.block" },
      { "type": "builtin", "id": "theme.card" }
  ]},
  { "id": "my", "title": "我的", "children": [
      { "type": "text", "text": "这是我的第二页", "size": 14 }
  ]}
]}
```

**节点类型**：`row` `column` `card` `text` `button` `image` `spacer` `divider` `builtin`
**节点字段**：`text`（文字；**image 节点用它当图片文件名**）、`size`（文字 8~28 sp；**spacer 1~200 dp**；image 高度 dp 8~28）、
`color`（`text|sub|accent|green|red` 或 `#rrggbb`）、`align`（`left|center|right`）、
`weight`（0~1，行内占比）、`width`（dp）、`visible`、`style`（`primary|secondary`）、
`id`（builtin 必填）、`action`（button 用，写法与 `actions[]` 完全一样）、`children`
**嵌套上限 6 层；节点之间没有隐含间距 —— 要空隙就放 `spacer`，要横线就放 `divider`。**

**内置积木 id（`type:"builtin"`）**：`extract.block` `extract.status` `extract.detail` `engine.status`
`engine.buttons` `rescue.desc` `rescue.buttons` `theme.card` `perm.summary` `plugin.summary`
`log.actions` `log.view` `update.button` `version.label` `brand.label`
（⚠ `perm.list` / `plugin.list` 目前退化成"打开这一页"的按钮 —— 页内清单还没拆成积木。）

**三条硬规则**：

1. **救援面不可移除**：树里没有 `rescue.buttons`（或 `rescue.desc`）和 `theme.card` 时，
   App 会**自动补在主控台末尾**并给 warning —— 这是唯一不可协商的约束（否则你会把自己锁在外面）。
2. **额外页自动接入**：`pages[1..]` 会自动获得返回行 + 主控台底部的入口行。
3. **逃生口**：**长按左上角品牌字**（或主题页里的那一行）→「以默认样式打开控制台」，
   忽略主题配置（**文件不删**），随时可恢复。

> 写了 `pages` 时，`layout.order` / `hidden` 会被忽略（以 `pages` 为准，自检会给 warning）。

## 5. 下一轮才生效的字段（现在写了也**不报错**，会自动等到生效）

| 字段 | 用途 | 备注 |
|---|---|---|
| `behavior` | 默认动作：`autoExtract` / `autoStartEngine` / `enterMainUi` / `hideButtons` / `doubleBackExit` | 谨慎用 `enterMainUi`（跳过控制台） |

> 校验器对这些只 **note 提示**「v1 只解析不执行」，不算错误 —— 所以 AI 现在就可以生成"完整版"主题包，
> 等 App 接上对应能力，**同一个包自动升级为可用**。

## 6. 五条提示词模板（用户可直接复制给任意 AI）

**① 从零生成一个主题**
```
请帮我做一个 DeepSeek Harness 安卓控制台的主题包（zip）。
规范：THEME-PACK-SPEC.md（或见 console.schema.json + console.example.json）
要求：深色、低饱和，强调色青绿 #2dd4bf；背景用一张海边夜景（生成 1080×2400 的图，命名 bg.jpg）；
     卡片半透明 cardsAlpha 0.82、背景 dim 0.28（字要看得清）；隐藏"检查更新"；把"启动引擎"改叫"点火"；
     不要任何 shell/http/intent/prompt 动作。
输出：1) console.json 全文 2) theme.json 全文 3) 打包命令 zip mytheme.zip theme.json console.json bg.jpg
```

**② 改我现在这个主题**（把现有 `console.json` 贴给 AI）
```
这是我现在的控制台主题配置（console.json 全文如下）。请在此基础上前进：
- 把强调色换成暖橙 #f59e0b，背景图换成 /sdcard/Pictures/my.jpg
- 圆角改 22、字体放大到 1.1
- 文案里"重启"改成"重开"
其余保持不变，输出新的 console.json + theme.json + 打包命令。
<粘贴你的 console.json>
```

**③ 只要一套配色**（不动布局）
```
只给我 appearance 这一段：企业蓝 #2563eb 为强调色，浅色底 #f8fafc、卡片 #ffffff、文字 #0f172a、
次要 #64748b、分割线 #e2e8f0；深色底 #0b1220、卡片 #111a2b、文字 #e6edf7、次要 #94a3b8、分割线 #1e293b。
输出可直接合并进 console.json 的 JSON 片段。
```

**④ 调布局与文案**（v1.17.5 起可用）
```
这是我现在的控制台主题配置。请只改这三件事，其余保持不动：
1) 顺序：把"日志"提到最上面，"检查更新"挪到最后；
2) 隐藏：把"检查更新"整行藏掉；
3) 文案："启动引擎"改叫"点火"、"重启"改叫"重开"、"停止"改叫"熄火"。
输出新的 console.json（含 layout.order / layout.hidden / text 三段）。
<粘贴你的 console.json>
```

**④·进阶 帮我加个按钮**（⏭ 需要 App 的第 2/3 轮：声明式布局 + 动作系统，现在写了也不执行）
```
在控制台加两个按钮：
1) "重启服务器" —— 执行 shell: svc nginx restart（要二次确认）
2) "整理截图" —— 把提示词"把 /sdcard/Pictures 里今天的截图分类归档"发给 DSH 的 AI
用 layout.pages / actions 写，其余保持现状。注意这两个是"可执行动作"，我会在导入时确认。
```

**⑤ 复制规范全文**
```
<把本文件整份贴进来> 然后按上面的要求生成主题包。
```

## 7. 常见错误（校验器会逐条指出来）

| 错误 | 正确写法 |
|---|---|
| `"bg": "深蓝"` / `"bg": "rgb(0,0,0)"` / `"bg": "#12345"` | `"#0f1319"`（3/6/8 位十六进制，带 `#`） |
| `"order": ["Plugins"]` / `"pluginns"` | 只能用小写卡片 id，见 §4 |
| `"hidden": ["rescue"]` | ⚠ 会被忽略（救援面不可移除）——自检里给 warning |
| `"action": {"type": "shel"}` | `"shell"`；且 `shell` 必须给 `cmd` |
| `background.image: "bg.png"` 但包里只有 `bg.jpg` | 文件名必须与包内文件**完全一致**（含扩展名） |
| `"fontScale": 1.8` | 上限 1.4（超范围回退默认） |
| `"schema": 2` | 当前只支持 1 |
| 图片 >2MB / json >512KB | 压缩后再打包（背景图建议 1080×2400、JPEG 质量 80） |
| `dim: 0` + `cardsAlpha: 1` + 背景图 | 只是 warning：字会看不清，建议 `dim ≥ 0.2` |

**自检输出示例**

```
$ python3 theme_pack_check.py night-ocean.zip
主题包校验：海边的夜
  ✗ [$.appearance.colors.bg] 不是合法颜色（支持 #rgb/#rrggbb/#aarrggbb）
  ✗ [$.layout.order[3]] 未知卡片 id "pluginns"（合法：extract, engine, rescue, perm, plugins, log, update, theme）
  ⚠ [$.layout.hidden[1]] "rescue" 是救援面的一部分，不可移除 → 会被忽略
不通过 ✗ —— 错误 2 / 警告 1
```
加 `--json` 得到 `{"ok":false,"errors":[{"path":…,"msg":…}],"warnings":[…],"notes":[…]}` —— **AI 用它自我修正后再交**。

## 8. 安全规则（AI 必读）

1. 含 `shell` / `http` / `intent` / `prompt` 的包，**导入时用户必须逐个确认**，默认不启用；
   确认只对「这份包 + 内容哈希」生效，包内容一变就要重新确认。
2. **不要**在主题包里放任何可执行文件、脚本、`.so`；v1 只接受 JSON + 图片，其它一律被忽略。
3. 不要在 JSON 里写 API Key、token、密码（主题包会被导出和分享）。
4. 用 `/sdcard/...` 绝对路径的图片不会随包分发；要分享就把它打进 zip 并用文件名引用。

## 9. 生效范围与兼容

| 项 | 说明 |
|---|---|
| App 版本 | ≥ 1.17.3；更老的版本会忽略整个文件（不会崩） |
| 三个变体 | 正式版 / Lite / 兼容版**各自独立**（目录不同），主题不共享 |
| 升级 App | `console/` 目录**不会被覆盖**（在用户数据区之外的数据目录里），主题保留 |
| 卸载重装 | `/sdcard` 上的目录还在 → 主题仍在 |
| Android 版本 | 除 `background.blur`（Android 12+）外，其余全部向下兼容到 Android 8 |
| 「恢复默认」 | 控制台 →「主题」页 → 恢复默认（把 `console.json` 改名留底，不删） |

## 10. 一句话给 AI

> **产出 `console.json`（可含 `theme.json` 与图片）→ 打成 zip → 用 `theme_pack_check.py` 自检到 `通过 ✓` → 交给用户导入。**
