#!/system/bin/sh
# DeepSeek Harness 手机版 APK 构建脚本
# 需要开发环境（runtime/dshroot/.dsh 等），路径可通过环境变量覆盖
set -e
# 开发环境主目录（runtime/dshroot/.dsh 所在位置）
H="${DSH_DEV_HOME:-/data/data/com.coomi.android/files/home}"
source "$H/build/env.sh"

# 本脚本所在目录（android-app/）
P="$(cd "$(dirname "$0")" && pwd)"
# android.jar 放 android-app/sdk/（可 export ANDROID_JAR 覆盖）
AJ="${ANDROID_JAR:-$P/sdk/android.jar}"
# javac 所在目录：优先 JAVA_BIN，否则从 DSH_DEV_HOME 推导（devhome 与 usr 同在 buildenv 下）
JAVA="${JAVA_BIN:-$H/../usr/lib/jvm/java-17-openjdk/bin}"
[ -x "$JAVA/javac" ] || { echo "!! 找不到 javac：$JAVA/javac（请 export JAVA_BIN=.../java-17-openjdk/bin）"; exit 1; }
# classpath 分隔符：Windows(Git Bash) 用 ';'，POSIX/Android 用 ':'（Windows 的 Java 程序不认 ':' 分隔）
# Windows 上 d8/apksigner 是 .bat（bash 不会自动补 .bat 后缀，显式指定）
CP_SEP=":"
D8="d8"
APKSIGNER="apksigner"
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=";"; D8="d8.bat"; APKSIGNER="apksigner.bat" ;;
esac
# 变体表（B11）：appId / 端口 / 外部目录 / 显示名 / 默认密钥文件名 的单一真源。
# DSH_VARIANT=official|lite|compat|community（默认 official）
[ -f "$P/variants.sh" ] || { echo "!! 缺少 android-app/variants.sh（变体表）"; exit 1; }
. "$P/variants.sh"

# 签名密钥（自行准备，不入仓库）。DSH_KEYSTORE 可覆盖，便于一键脚本用缓存里的密钥。
# 默认按变体取：official/lite/compat → release.jks；community → community.jks（公开密钥）。
KEY="${DSH_KEYSTORE:-$P/$V_KEY_FILE}"

# 产物路径：official 保持历史名 DeepSeekHarness.apk（兼容 tools/build-apk.sh），
# 其余变体带后缀，避免不同变体互相覆盖。
if [ -n "${DSH_APK_OUT:-}" ]; then
  APK_OUT="$DSH_APK_OUT"
elif [ "$DSH_VARIANT" = "official" ]; then
  APK_OUT="$P/DeepSeekHarness.apk"
else
  APK_OUT="$P/DeepSeekHarness-$DSH_VARIANT.apk"
fi

echo "== 0/7 组装 payload =="
# 移动端适配注入（mobile.css，不覆盖原生 index.html，DSH 更新后也自动重新注入）
sh "$P/../mobile-patch/inject.sh"

rm -rf "$P/staging" "$P/out" "$P/assets"
mkdir -p "$P/staging/runtime/bin" "$P/staging/runtime/lib" \
         "$P/staging/runtime/etc" \
         "$P/staging/bin" "$P/staging/dshroot" \
         "$P/staging/dshhome/profiles/web" "$P/assets"

# Termux 共存修复（v1.7.4）：内置 node 在 Termux 环境编译，OPENSSLDIR 编译死为
# /data/data/com.termux/files/usr。装了 Termux 的设备读其 openssl.cnf 触发 EACCES，
# node 启动即崩；没装 Termux 时靠 ENOENT 静默才碰巧正常。App 启动引擎时注入
# OPENSSL_CONF 指向本文件（最小配置，显式激活内置 default provider，无需外部模块）。
cat > "$P/staging/runtime/etc/openssl.cnf" <<'EOF'
openssl_conf = openssl_init

[openssl_init]
providers = provider_sect

[provider_sect]
default = default_sect

[default_sect]
activate = 1
EOF

cp -L "$H/runtime/bin/node" "$P/staging/runtime/bin/node"
# Android 内置 ripgrep（grep/glob 工具用，@vscode/ripgrep 无 android 平台包）：
# rg 由 dsh-tool-fs-search 在 @vscode/ripgrep 解析失败后回退查找（runtime/bin/rg）
if [ -f "$H/runtime/bin/rg" ]; then
  cp -L "$H/runtime/bin/rg" "$P/staging/runtime/bin/rg"
  chmod +x "$P/staging/runtime/bin/rg"
  echo "  内置 rg: runtime/bin/rg"
# Android 内置 curl（AI 的 bash 工具用，Android 系统不带 curl）：
# termux 静态构建（NDK r29，interpreter /system/bin/linker64），依赖 libcurl/libnghttp2/3/ngtcp2/libssh2/openssl
if [ -f "$H/runtime/bin/curl" ]; then
  cp -L "$H/runtime/bin/curl" "$P/staging/runtime/bin/curl"
  chmod +x "$P/staging/runtime/bin/curl"
  echo "  内置 curl: runtime/bin/curl"
fi
fi

for f in $(find "$H/runtime/lib" -maxdepth 1 -type f); do
  cp -L "$f" "$P/staging/runtime/lib/"
done

# ⚠ v1.18（B8）：devhome 里的 soname 别名（libicudata.so → libicudata.so.78.3 等）往往是**实体副本**
#（payload.zip 存不了符号链接，解压出来就是真文件）。上面的批量复制会把它们一起带进包，
# 于是设备侧真的存在 3 份独立 inode（实测 libicudata 33MB × 3，见 LINKS.txt 之后的删除步骤）。
cat > "$P/staging/runtime/lib/LINKS.txt" <<'EOF'
libcrypto.so	libcrypto.so.3
libicudata.so	libicudata.so.78.3
libicudata.so.78	libicudata.so.78.3
libicui18n.so	libicui18n.so.78.3
libicui18n.so.78	libicui18n.so.78.3
libicuio.so	libicuio.so.78.3
libicuio.so.78	libicuio.so.78.3
libicutest.so	libicutest.so.78.3
libicutest.so.78	libicutest.so.78.3
libicutu.so	libicutu.so.78.3
libicutu.so.78	libicutu.so.78.3
libicuuc.so	libicuuc.so.78.3
libicuuc.so.78	libicuuc.so.78.3
libsqlite3.so	libsqlite3.so.3.53.4
libsqlite3.so.0	libsqlite3.so.3.53.4
libssl.so	libssl.so.3
libz.so	libz.so.1.3.2
libz.so.1	libz.so.1.3.2
EOF

# v1.18（B8）：按 LINKS.txt 把"别名文件"从包里剔掉 —— 只留真实文件 + LINKS.txt，
# 由 App 解压时的 applyLinks()（硬链 → 软链 → 复制兜底）重建这些名字。
# 收益：设备侧不再有 ICU 三份独立 inode（实测 −73MB），包内也少一份压缩体积。
# 需要旧行为（包内自带实体别名，排查链接问题时最省事）：export DSH_MATERIALIZE_LINKS=1
if [ -z "$DSH_MATERIALIZE_LINKS" ]; then
  _removed=0
  while IFS=$'\t' read -r _link _target; do
    case "$_link" in ""|\#*) continue ;; esac
    [ -n "$_target" ] || continue
    if [ -f "$P/staging/runtime/lib/$_link" ] && [ "$_link" != "$_target" ]; then
      rm -f "$P/staging/runtime/lib/$_link"
      _removed=$((_removed + 1))
    fi
  done < "$P/staging/runtime/lib/LINKS.txt"
  echo "  soname 别名（$_removed 个）不打进包，改由 App 解压时按 LINKS.txt 重建（硬链优先）"
fi

# soname 实体化（历史修复，v1.18 起默认**关闭**）：
#   · 为什么曾经要实体化：APK/jar 打包会把符号链接压平成普通内容，部分设备解压后又建不了软链
#     （FUSE/权限）→ node 启动报 "library libz.so.1 not found"。
#   · 为什么现在可以不做：App 解压后会调用 AccessibilityService 之外的 applyLinks()
#     （MainActivity.applyLinks，顺序 = 硬链 Os.link → 软链 → **复制兜底**），
#     按同一份 LINKS.txt 重建这些名字 —— 复制兜底保证任何设备都不会起不来。
#   · 实测代价（B8，2026-10-05 模拟器）：实体化会让 ICU 三份成为**独立 inode**，
#     设备侧多占 ~73MB、APK 内也多一份压缩体积（libicudata 33MB × 3）。
#   · 需要旧行为（例如排查链接问题）时：export DSH_MATERIALIZE_LINKS=1
if [ -n "$DSH_MATERIALIZE_LINKS" ]; then
  cd "$P/staging/runtime/lib"
  while IFS=$'\t' read -r _link _target; do
    case "$_link" in ""|\#*) continue ;; esac
    [ -n "$_target" ] || continue
    if [ ! -e "$_link" ] && [ -f "$_target" ]; then
      cp -L "$_target" "$_link"
      echo "  soname 实体化: $_link"
    fi
  done < LINKS.txt
  cd - >/dev/null
else
  echo "  soname 链接留给 App 解压时按 LINKS.txt 重建（硬链优先→软链→复制兜底），包内不留重复实体"
fi

# 模拟器调试可选开关（x86_64 + ndk_translation）：
# 转译器 /system/bin/ndk_translation_program_runner_binfmt_misc_arm64 自己是 x86_64，
# 它按「裸名」从 LD_LIBRARY_PATH 解析 libz.so / libssl.so / libcrypto.so。
# 若这三个裸名指向 arm64 副本 → 转译器报
#   CANNOT LINK EXECUTABLE ... is for EM_AARCH64 (183) instead of EM_X86_64 (62)
# 把 DSH_X64_BARE_LIBS 指向一个含 x86_64 版这三个库的目录即可在构建期一次做完
#（docs/开发指南.md 第六节原本要求解压后手工 push 进设备，且每次重解压都要重做）。
# 真机 arm64 不要设置这个变量；三个裸名库可从模拟器 /system/lib64/ 取。
if [ -n "$DSH_X64_BARE_LIBS" ]; then
  for _l in libz.so libssl.so libcrypto.so; do
    [ -f "$DSH_X64_BARE_LIBS/$_l" ] || { echo "!! DSH_X64_BARE_LIBS 缺少 $_l：$DSH_X64_BARE_LIBS"; exit 1; }
    cp -f "$DSH_X64_BARE_LIBS/$_l" "$P/staging/runtime/lib/$_l"
  done
  # 从 LINKS.txt 移除这三条：否则 App 解压后会把裸名重建为指向 arm64 的链接，覆盖掉刚注入的 x86_64 库。
  # ⚠ v1.18：**不要**再清空整个 LINKS.txt（旧的 DSH_X64_NO_LINKS 做法）。当时清空是因为
  #    "App 会按 LINKS.txt 把实体文件重建为软链、而 ndk_translation 下软链加载不了" —— 那是
  #    链接**实体化**时代的因果。现在 App 侧 applyLinks() 是**硬链优先**（Os.link），
  #    硬链对 guest 加载器就是普通文件，没有软链问题；带版本号的条目必须保留，
  #    否则 node 会报 "library libz.so.1 not found"。
  grep -v -E '^(libcrypto\.so|libssl\.so|libz\.so)[[:space:]]' \
    "$P/staging/runtime/lib/LINKS.txt" > "$P/staging/runtime/lib/LINKS.txt.x64"
  mv -f "$P/staging/runtime/lib/LINKS.txt.x64" "$P/staging/runtime/lib/LINKS.txt"
  if [ -n "$DSH_X64_NO_LINKS" ]; then
    echo "  [模拟器] 注意：DSH_X64_NO_LINKS 自 v1.18 起不再清空 LINKS.txt（硬链优先已足够，清空会让 node 缺 libz.so.1）"
  fi
  echo "  [模拟器] 已注入 x86_64 裸名库并从 LINKS.txt 移除对应条目"
fi

mkdir -p "$P/staging/dshroot/lib"
# 排除项（B7 体积优化；实测 dshroot 树里这些占 ~76MB / ~12000 个文件）：
#   1) dsh 的 node_modules/.bin（原脚本即排除）
#   2) dsh/node_modules/@deepseek-ai/dsh —— 开发树里被误造的「dsh 自我递归嵌套」
#      （实测 632 层、路径约 19000 字符）。tar 的 --exclude 会在深入前跳过它，
#      构建产物不需要它，复制耗时也从「卡死」降到数分钟。
#   3) *.map / *.d.ts / *.d.mts / *.d.cts —— sourcemap 与 TypeScript 类型声明，运行时（纯 JS）不需要
#   4) __pycache__ / *.pyc —— Python 字节码缓存，python 运行时按需自己生成
# ⚠ **不要**一刀切排除 *.md：dsh-agent-preset/skills/**/SKILL.md 与 skill 模板是运行时真读的，
#    排掉会打断技能目录（收益也只有 ~8MB，不值）。
# ⚠ 也不要排除 licenses —— MIT/Apache 分发要求随附声明。
# ⚠ 不要在这里改用 robocopy：它遇到那棵病态目录会不返回（实测挂住）或报错 16。
( cd "$H/dshroot/lib" && tar cf - \
    --exclude='./node_modules/@deepseek-ai/dsh/node_modules/.bin' \
    --exclude='./node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh' \
    --exclude='*.map' \
    --exclude='*.d.ts' \
    --exclude='*.d.mts' \
    --exclude='*.d.cts' \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    . ) \
  | ( cd "$P/staging/dshroot/lib" && tar xf - )

# v1.21：内置默认插件 ds-harness-remote（维护者要求 Android 默认就装好它）——
# 等价于 `dsh plugin --profile web add github:blue-soda/ds-harness-remote`，但走**构建期内置**：
#   · 运行时装不了的原因（实测）：Android 侧 git 调用报 "Unable to get realpath of git"、
#     pnpm 对 github: 默认走 ssh、且该插件 git-hosted 需要 pnpm 允许 prepare 构建；
#   · **必须放到 profile 的 node_modules**（`dshhome/profiles/web/node_modules/`）：
#     实测 bundle 名是从 profile 解析的（`dsh plugin add` 也是装到那儿），
#     放 DSH 树会报 `ds-harness-remote: failed to import`；
#   · 插件自带构建产物（dist/index.js + dist/client.github.js），设备端无需再构建；
#   · 依赖自带齐全（qrcode/werift/ws/zod + 传递依赖）—— zod/ws/schemastery 其实 DSH 也有，
#     但版本要对得上才敢共用，这里自带给全（源文件 ~22MB，压缩进 APK 实测 +11MB）。
# 说明：vendor/ 与 dsh-patches/overlay（快照式补丁）是两套东西 —— 前者是"新增一个包"，后者是"替换已有文件"。
VENDOR_PLUGIN="$P/../vendor/ds-harness-remote"
if [ -d "$VENDOR_PLUGIN" ]; then
  DEST="$P/staging/dshhome/profiles/web/node_modules/ds-harness-remote"
  mkdir -p "$DEST"
  ( cd "$VENDOR_PLUGIN" && tar cf - \
      --exclude='*.map' --exclude='*.d.ts' --exclude='*.d.mts' --exclude='*.d.cts' \
      --exclude='__pycache__' --exclude='*.pyc' \
      --exclude='./node_modules/*/test' --exclude='./node_modules/*/tests' \
      --exclude='./node_modules/*/docs' \
      . ) | ( cd "$DEST" && tar xf - )
  echo "内置插件 -> profiles/web/node_modules/ds-harness-remote ($(du -sh "$DEST" 2>/dev/null | cut -f1))"
else
  echo "⚠ 未找到 vendor/ds-harness-remote，跳过内置插件"
fi

# dshroot 版本标记：App 用它判断「外部 /sdcard/DeepSeekHarness/dshroot」是否需要补齐。
# 外部已有的文件永不覆盖（保留 AI 运行时修改），缺失文件才从 APK 补上。
DSHROOT_REV="$(date +%Y%m%d%H%M%S)"
echo "$DSHROOT_REV" > "$P/staging/dshroot/REVISION"
echo "$DSHROOT_REV" > "$P/assets/dshroot_revision.txt"

# 内核树布局标记：布局本身变了（如依赖树结构换了）必须全量重推 dshroot。
# 否则同内核升级走 fast 同步（只补白名单文件），整棵树仍是旧结构，修好的补丁包不生效。
DSHROOT_LAYOUT="hoisted-1"
echo "$DSHROOT_LAYOUT" > "$P/assets/dshroot_layout.txt"

# 内核版本标记（v1.5.2 慢启动修复）：REVISION 是构建时间戳，每次构建都变；
# App 用它区分「同内核升级（内容几乎不变，快速同步即可）」与「内核升级（新增文件，需全量补齐）」。
DSHROOT_PKG_JSON="$P/staging/dshroot/lib/node_modules/@deepseek-ai/dsh/package.json"
if [ -f "$DSHROOT_PKG_JSON" ]; then
  grep -m1 '"version"' "$DSHROOT_PKG_JSON" | sed -E 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' > "$P/assets/dshroot_kernel_version.txt"
  echo "  内核版本标记: $(cat "$P/assets/dshroot_kernel_version.txt")"
else
  echo "  !! 未找到 dsh/package.json，内核版本标记留空（App 将保守全量补齐）"
  : > "$P/assets/dshroot_kernel_version.txt"
fi

# ── payload/bin/bash ───────────────────────────────────────────────────────────
# v1.20：**优先用真 bash**（由 tools/fetch-bash.py 取到 $H/bash/bin/bash）。
# 为什么必须真 bash：DSH 内置终端是这样起 shell 的 ——
#     bash --rcfile /data/.../cache/tmp/dsh-shell-XXXX/bashrc -i
# 那份 bashrc 是**纯 bash 语法**（BASH_VERSINFO / PROMPT_COMMAND / declare -p / 数组 / $'…'），
# 而 payload 里的 bash 原本只是 /system/bin/sh（mksh）的包装：mksh 既认不出 --rcfile，
# 也跑不了这份 rcfile → 终端面板报 `/system/bin/sh: --: unknown option` 并退出(1)。
# 取不到真 bash 时回退到「过滤 bash 专有参数 + -i」的 mksh 包装（终端能用但集成度低）。
# 注意：这里用 -f 而不是 -x —— Windows/NTFS 不保存可执行位，Git Bash 下 -x 恒为假。
# 真正 chmod 由 App 侧 setExecutables() 在解压后做（bin/bash 已在 execs 名单里）。
if [ -f "$H/bash/bin/bash" ]; then
  cp -L "$H/bash/bin/bash" "$P/staging/bin/bash"
  chmod +x "$P/staging/bin/bash"
  # bash 的共享库：runtime/lib 里通常已有（libreadline/libncursesw/libiconv/libandroid-support），
  # 缺哪个补哪个（引擎的 LD_LIBRARY_PATH 指向 runtime/lib，子进程继承）。
  mkdir -p "$P/staging/runtime/lib"
  for _so in "$H/bash/lib/"*.so*; do
    [ -e "$_so" ] || continue
    _base=$(basename "$_so")
    [ -e "$P/staging/runtime/lib/$_base" ] || cp -L "$_so" "$P/staging/runtime/lib/$_base"
  done
  echo "  payload/bin/bash <- 真 bash（$(wc -c < "$P/staging/bin/bash" | tr -d ' ') 字节）"
else
cat > "$P/staging/bin/bash" <<'EOF'
#!/system/bin/sh
# v1.20（回退版）：**不是真 bash**，而是 /system/bin/sh（mksh）的包装。
#   正常构建应带真 bash（$H/bash/bin/bash）；这里是没取到真 bash 时的兜底。
#   DSH 按 bash 语义传 `--rcfile <path>` / `--noprofile` / `--norc` / `--`，mksh 全不认 →
#   报 `/system/bin/sh: --: unknown option` 退出(1)。所以：
#     · 丢弃这些 bash 专有选项（**任意位置**，实测 `--` 会出现在中间；
#       `--rcfile` 后面还跟着一个路径，要连它一起丢）
#     · 丢完没参数时补 `-i`，真 PTY 里才有提示符
#     · 其余调用（如 `bash -c 'cmd'`）原样转发
# 诊断：argv 追加到 payload/dshhome/logs/bash-args.log（超 200 行清空）。
_self_dir=$(dirname "$0")
_log="$_self_dir/../dshhome/logs/bash-args.log"
mkdir -p "$_self_dir/../dshhome/logs" 2>/dev/null
if [ -d "$_self_dir/../dshhome/logs" ]; then
  if [ "$(wc -l < "$_log" 2>/dev/null || echo 0)" -gt 200 ]; then : > "$_log"; fi
  printf '%s ARGS: %s\n' "$(date '+%m-%d %H:%M:%S')" "$*" >> "$_log" 2>/dev/null
fi

_skip=0
_keep=""
for _a in "$@"; do
  if [ "$_skip" = "1" ]; then _skip=0; continue; fi
  case "$_a" in
    --rcfile|--init-file) _skip=1; continue ;;
    --rcfile=*|--init-file=*) continue ;;
    --noprofile|--norc|--|--login|--posix|--verbose|--debugger|--restricted) continue ;;
  esac
  _keep="$_keep$_a
"
done
_old_ifs=$IFS
IFS='
'
set -f                      # 关掉 glob，避免参数里的 * 被展开
set -- $_keep
set +f
IFS=$_old_ifs

[ "$#" -eq 0 ] && set -- -i
exec /system/bin/sh "$@"
EOF
  chmod +x "$P/staging/bin/bash"
  echo "  !! 未找到 \$H/bash/bin/bash，payload/bin/bash 用 mksh 回退包装"
  echo "     （跑 python tools/fetch-bash.py 可取到真 bash；见 CHANGES「终端」一节）"
fi
# 内置 pnpm（v1.15.3）：插件管理的「添加插件」全程 pnpm add，而 payload 原本不带 pnpm → 退出码 127，
# 三种输入（本地目录 / 包名 / GitHub 地址）全装不上。这里把 pnpm 的纯 JS bundle 一起打进 payload：
#   payload/bin/pnpm  ← wrapper（payload/bin 在引擎 PATH 上，所以插件管理器能直接找到 pnpm）
#   payload/pnpm/     ← pnpm 本体（bin/pnpm.cjs + dist/pnpm.cjs 等）
# 取舍：不带 dist/node_modules（实测安装流程不依赖它，省 9MB）；不带 *.node
#      （本项目 payload 红线=不带原生模块，且 pnpm 只提供 darwin/win32 的）。
if [ -d "$H/pnpm" ]; then
  rm -rf "$P/staging/pnpm"
  cp -r "$H/pnpm" "$P/staging/pnpm"
  cat > "$P/staging/bin/pnpm" <<'PNPMEOF'
#!/system/bin/sh
# DSH 内置 pnpm 包装脚本（Android 适配）
#  · 不能用 #!/usr/bin/env node —— Android 没有 /usr/bin/env
#  · node 优先走 PATH（App 启动引擎时已把 payload/runtime/bin 放进 PATH），兜底按自身位置推导
#  · 默认 copy 导入：app 私有目录（SELinux）与 FUSE 外部存储都拒绝硬链接，pnpm 默认 hardlink 会失败
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v pnpm 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
NODE=$(command -v node 2>/dev/null)
[ -x "$NODE" ] || NODE="$DIR/../runtime/bin/node"
if [ -z "$DIR" ] || [ ! -f "$DIR/../pnpm/bin/pnpm.cjs" ]; then
  echo "dsh: 内置 pnpm 缺失（$DIR/../pnpm/bin/pnpm.cjs）" >&2
  exit 127
fi
exec "$NODE" "$DIR/../pnpm/bin/pnpm.cjs" --config.package-import-method=copy "$@"
PNPMEOF
  chmod +x "$P/staging/bin/pnpm"
  echo "  内置 pnpm: bin/pnpm + pnpm/ ($(du -sh "$P/staging/pnpm" | cut -f1))"
else
  echo "  !! 未找到 pnpm/（插件管理「添加插件」将不可用）"
fi

# 内置 git（v1.15.5）：插件管理的「添加插件」支持 GitHub 地址（git 形态的 spec 会先 git ls-remote 探活），
# AI 自己也能用 git。payload 原本不带 git → 与 pnpm 同因（子进程找不到命令）。
#   payload/bin/git                  ← 主程序（在引擎 PATH 上；git 还要靠 PATH 里的 git 拉起远程助手）
#   payload/git/libexec/git-core/    ← git-remote-https / git-remote-http（独立二进制，非内建）
#   payload/git/templates/           ← 仓库模板（配 GIT_TEMPLATE_DIR）
# 取舍：不打包 libexec/git-core 里那 146 个与主程序同尺寸的硬链接副本（git-add/git-branch/… 多为内建，
#      由主程序在进程内处理）；不带 less（分页器，用 GIT_PAGER=cat 代替）。
#      依赖库 libiconv.so / libcharset.so 走 runtime/lib（已在 LD_LIBRARY_PATH 上）。
if [ -d "$H/git" ]; then
  rm -rf "$P/staging/git"
  mkdir -p "$P/staging/git/libexec/git-core"
  cp -L "$H/git/bin/git" "$P/staging/bin/git"
  cp -L "$H/git/libexec/git-core/git-remote-https" "$P/staging/git/libexec/git-core/"
  cp -L "$H/git/libexec/git-core/git-remote-http"  "$P/staging/git/libexec/git-core/"
  cp -r "$H/git/templates" "$P/staging/git/templates"
  chmod +x "$P/staging/bin/git" "$P/staging/git/libexec/git-core/"*
  echo "  内置 git: bin/git + git/ ($(du -sh "$P/staging/git" | cut -f1))"
else
  echo "  !! 未找到 git/（「添加插件」的 GitHub 地址输入与 AI 的 git 将不可用）"
fi

# 内置 Python（v1.16.0）：AI 常要跑脚本做数据处理/解析，payload 原本完全没有 python。
# 走项目既有老路（rg / curl / git / pnpm 都是这么来的）：Termux aarch64 deb 解包。
#   payload/bin/python3  ← wrapper（payload/bin 在引擎 PATH 上，AI 直接 python3 就能用）
#   payload/python/      ← 本体（bin/python3.14 + lib/python3.14 标准库 + lib-dynload）
# 依赖库统一走 runtime/lib（已在 LD_LIBRARY_PATH 上），本版新增 13 个 .so 及其 soname 实体名：
#   libpython3.14 / libandroid-support / libandroid-posix-semaphore / libbz2 / libcrypt /
#   libexpat / libgdbm(+compat) / liblzma / libncursesw / libpanelw / libreadline / libzstd
#   （libffi / libsqlite3 / libssl / libcrypto / libz / libc++_shared 早已随 node 带入，复用）
# 取舍：不带 include/（C 头）与 pkgconfig —— 只有现场编译 C 扩展才需要；
#      PYTHONHOME **不用设**：python 按 argv0 自推 prefix=<payload>/python（真机实测通过）。
if [ -d "$H/python" ]; then
  rm -rf "$P/staging/python"
  cp -r "$H/python" "$P/staging/python"
  cat > "$P/staging/bin/python3" <<'PYEOF'
#!/system/bin/sh
# DSH 内置 Python 包装脚本
#  · 不能用 #!/usr/bin/env python3 —— Android 没有 /usr/bin/env
#  · LD_LIBRARY_PATH 兜底：正常由 App 启动引擎时设好，这里防手工执行时缺库
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v python3 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
[ -n "$LD_LIBRARY_PATH" ] || { LD_LIBRARY_PATH="$DIR/../runtime/lib"; export LD_LIBRARY_PATH; }
if [ -z "$DIR" ] || [ ! -x "$DIR/../python/bin/python3.14" ]; then
  echo "dsh: 内置 python 缺失（$DIR/../python/bin/python3.14）" >&2
  exit 127
fi
exec "$DIR/../python/bin/python3.14" "$@"
PYEOF
  chmod +x "$P/staging/bin/python3"
  cp "$P/staging/bin/python3" "$P/staging/bin/python"
  chmod +x "$P/staging/bin/python"
  cat > "$P/staging/bin/pip" <<'PIPEOF'
#!/system/bin/sh
# DSH 内置 pip 包装脚本（与 bin/python3 同源，只换 -m 参数）
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v pip 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
[ -n "$LD_LIBRARY_PATH" ] || { LD_LIBRARY_PATH="$DIR/../runtime/lib"; export LD_LIBRARY_PATH; }
if [ -z "$DIR" ] || [ ! -x "$DIR/../python/bin/python3.14" ]; then
  echo "dsh: 内置 python 缺失（$DIR/../python/bin/python3.14）" >&2
  exit 127
fi
exec "$DIR/../python/bin/python3.14" -m pip "$@"
PIPEOF
  chmod +x "$P/staging/bin/pip"
  cp "$P/staging/bin/pip" "$P/staging/bin/pip3"
  chmod +x "$P/staging/bin/pip3"
  echo "  内置 python: bin/python3 + python/ ($(du -sh "$P/staging/python" | cut -f1))"
else
  echo "  !! 未找到 python/（AI 的 python / python3 将不可用）"
fi

# 内置 npm（v1.16.0）：AI 需要自己装 node 包。payload **已有 node v26.4.0**（引擎就跑在它上面，
# 且 runtime/bin 在引擎 PATH 上，AI 本就能直接 node xxx.js），但 Termux 的 nodejs 包不含 npm
# （npm 是单列的另一个 deb），所以「装 node 包」这条链是断的。
#   payload/bin/npm  ← wrapper → node + payload/npm/bin/npm-cli.js
#   payload/bin/npx  ← wrapper → node + payload/npm/bin/npx-cli.js
#   payload/npm/     ← npm 本体（bin/ lib/ node_modules/ package.json …）
# 取舍：裁掉 docs/（2.4M）与 man/（466K）—— 纯文档，运行时用不到，16M → 13M。
#      全局安装落点由 HOME 决定（App 里 HOME = filesDir，可写，无需额外配置）。
if [ -d "$H/npm" ]; then
  rm -rf "$P/staging/npm"
  cp -r "$H/npm" "$P/staging/npm"
  cat > "$P/staging/bin/npm" <<'NPMEOF'
#!/system/bin/sh
# DSH 内置 npm 包装脚本
#  · 不能用 #!/usr/bin/env node —— Android 没有 /usr/bin/env
#  · node 优先走 PATH（App 启动引擎时已把 payload/runtime/bin 放进 PATH），兜底按自身位置推导
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v npm 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
NODE=$(command -v node 2>/dev/null)
[ -x "$NODE" ] || NODE="$DIR/../runtime/bin/node"
if [ -z "$DIR" ] || [ ! -f "$DIR/../npm/bin/npm-cli.js" ]; then
  echo "dsh: 内置 npm 缺失（$DIR/../npm/bin/npm-cli.js）" >&2
  exit 127
fi
#  · 全局安装落点改到 $HOME/.npm-global：默认前缀是 <payload>/runtime（payload 内，
#    升级会被覆盖，且未必可写）；HOME = App filesDir，可写且跨版本升级保留。
[ -n "$HOME" ] && { NPM_CONFIG_PREFIX="$HOME/.npm-global"; export NPM_CONFIG_PREFIX; }
exec "$NODE" "$DIR/../npm/bin/npm-cli.js" "$@"
NPMEOF
  chmod +x "$P/staging/bin/npm"
  cat > "$P/staging/bin/npx" <<'NPXEOF'
#!/system/bin/sh
# DSH 内置 npx 包装脚本（同 npm，只换入口）
SELF="$0"
case "$SELF" in
  */*) ;;
  *) SELF=$(command -v npx 2>/dev/null) ;;
esac
DIR=$(cd "$(dirname "$SELF")" 2>/dev/null && pwd)
NODE=$(command -v node 2>/dev/null)
[ -x "$NODE" ] || NODE="$DIR/../runtime/bin/node"
if [ -z "$DIR" ] || [ ! -f "$DIR/../npm/bin/npx-cli.js" ]; then
  echo "dsh: 内置 npx 缺失（$DIR/../npm/bin/npx-cli.js）" >&2
  exit 127
fi
exec "$NODE" "$DIR/../npm/bin/npx-cli.js" "$@"
NPXEOF
  chmod +x "$P/staging/bin/npx"
  echo "  内置 npm: bin/npm + bin/npx + npm/ ($(du -sh "$P/staging/npm" | cut -f1))"
else
  echo "  !! 未找到 npm/（AI 的 npm / npx 将不可用）"
fi

# Shizuku 运行时（rish dex）：独立放 assets 供权限界面检测，同时放 payload 供 DSH 插件调用
cp "$H/rish/rish_shizuku.dex" "$P/assets/rish_shizuku.dex"
mkdir -p "$P/staging/rish"
cp "$H/rish/rish_shizuku.dex" "$P/staging/rish/rish_shizuku.dex"
chmod 644 "$P/staging/rish/rish_shizuku.dex"

# ============================================================================
# 变体构建（B11）：把 src/ 与 AndroidManifest.xml 生成为变体专属副本。
#   · Java 包名 = 变体 appId（aapt 生成的 R.java 也落在该包下，故必须整体搬包）
#   · 生成 BuildVariant.java：端口 / 外部目录 / appId 常量，供壳与 vscreen 核心引用
#   · manifest：package / provider authorities / 版本后缀 / 显示名 一并替换
# ⚠ 不要手改 out/ 下的产物；要改变体请改 android-app/variants.sh。
# ============================================================================
SRC_STAGE="$P/out/src"
MANIFEST="$P/out/manifest/AndroidManifest.xml"
rm -rf "$P/out/src" "$P/out/manifest"
mkdir -p "$SRC_STAGE" "$P/out/manifest"
find "$P/src" -name '*.java' > "$P/out/srcfiles.txt"
while IFS= read -r f; do
  rel="${f#$P/src/}"                       # com/deepseek/harness/MainActivity.java
  sub="${rel#com/deepseek/harness/}"       # MainActivity.java / vscreen/Main.java
  dest="$SRC_STAGE/$V_PKG_PATH/$sub"
  mkdir -p "$(dirname "$dest")"
  sed -e "s|^package com\.deepseek\.harness|package $V_APP_ID|" \
      -e "s|^import com\.deepseek\.harness\.|import $V_APP_ID.|" \
      "$f" > "$dest"
done < "$P/out/srcfiles.txt"
cat > "$SRC_STAGE/$V_PKG_PATH/BuildVariant.java" <<EOF
package $V_APP_ID;

/** 构建期生成：变体常量。真源 = android-app/variants.sh，请勿手改本文件。 */
public final class BuildVariant {
    public static final String VARIANT = "$DSH_VARIANT";
    public static final String APP_ID = "$V_APP_ID";
    public static final String EXT_DIR_NAME = "$V_EXT_DIR";
    public static final int ENGINE_PORT = $V_PORT;
    public static final int NOTIFY_PORT = $V_NOTIFY_PORT;
    public static final int VS_BRIDGE_PORT = $V_VS_BRIDGE_PORT;
    public static final int VS_CORE_PORT = $V_VS_CORE_PORT;
    private BuildVariant() {}
}
EOF
sed -e "s|package=\"com\.deepseek\.harness\"|package=\"$V_APP_ID\"|" \
    -e "s|android:authorities=\"com\.deepseek\.harness\.|android:authorities=\"$V_APP_ID.|g" \
    -e "s|android:versionName=\"\([^\"]*\)\"|android:versionName=\"\1$V_SUFFIX\"|" \
    "$P/AndroidManifest.xml" > "$MANIFEST"
if [ "$DSH_VARIANT" != "official" ]; then
  sed -i "s|android:label=\"@string/app_name\"|android:label=\"$V_LABEL\"|" "$MANIFEST"
  sed -i "s|android:label=\"DeepSeek Harness 屏幕助手\"|android:label=\"$V_LABEL 屏幕助手\"|" "$MANIFEST"
fi
# 自检：替换必须真的发生（否则会静默产出"包名没变"的包，两个变体互相覆盖）
grep -q "package=\"$V_APP_ID\"" "$MANIFEST" || { echo "!! manifest package 未替换为 $V_APP_ID"; exit 1; }
grep -q "authorities=\"$V_APP_ID.shizuku\"" "$MANIFEST" || { echo "!! manifest shizuku authority 未替换"; exit 1; }
grep -q "authorities=\"$V_APP_ID.logshare\"" "$MANIFEST" || { echo "!! manifest logshare authority 未替换"; exit 1; }
grep -q "^package $V_APP_ID;" "$SRC_STAGE/$V_PKG_PATH/MainActivity.java" || { echo "!! Java package 未替换"; exit 1; }
echo "  变体: $DSH_VARIANT（appId=$V_APP_ID 引擎=$V_PORT 通知=$V_NOTIFY_PORT vscreen=$V_VS_BRIDGE_PORT/$V_VS_CORE_PORT 外部目录=$V_EXT_DIR）"

# v1.9 虚拟屏服务 jar（VirtualScreenServer）：独立于 App classes，供 app_process 特权加载。
# app_process -Djava.class.path 必须指向含 *.dex 的 jar（裸 dex 会报 Unsupported class loader）。
# 单独 javac 编译 src/.../vscreen/ 源码 → d8 成 classes.dex → jar 打包 → 放 assets（App 提取后传给引擎）
echo "== vscreen jar =="
VSC_SRC="$SRC_STAGE/$V_PKG_PATH/vscreen"
mkdir -p "$P/out/vsc-classes" "$P/out/vsc-dex"
if ls "$VSC_SRC"/*.java >/dev/null 2>&1; then
  if "$JAVA/javac" -source 1.8 -target 1.8 -bootclasspath "$AJ" -d "$P/out/vsc-classes" "$VSC_SRC"/*.java "$SRC_STAGE/$V_PKG_PATH/BuildVariant.java" >"$P/out/vsc-javac.log" 2>&1; then
    ( cd "$P/out/vsc-classes" && "$D8" --release --lib "$AJ" --min-api 24 --output "$P/out/vsc-dex" $(find . -name '*.class' | sed 's|^\./||') ) 2>/tmp/vsc-d8.log || ( echo "  d8 retry"; cd "$P/out/vsc-classes" && find . -name '*.class' > "$P/out/vsc.rsp" && "$D8" --release --lib "$AJ" --min-api 24 --output "$P/out/vsc-dex" @"$P/out/vsc.rsp" 2>/tmp/vsc-d8b.log )
    if [ -f "$P/out/vsc-dex/classes.dex" ]; then
      # 用 jar 把 classes.dex 打成 jar（app_process 认含 dex 的 jar）
      ( cd "$P/out/vsc-dex" && "$JAVA/jar" cf "$P/assets/vscreen_shizuku.jar" classes.dex )
      echo "  vscreen_shizuku.jar: $(stat -c%s "$P/assets/vscreen_shizuku.jar") bytes"
    else
      echo "  !! vscreen dex 生成失败（d8）"
    fi
  else
    echo "  !! vscreen javac 失败，日志：$P/out/vsc-javac.log"
    tail -10 "$P/out/vsc-javac.log"
  fi
else
  echo "  (无 vscreen 源码，跳过)"
fi

# 移动端适配资源：随 APK 打包，MainActivity 注入 WebView（不依赖服务器 dist）
cp "$P/../mobile-patch/mobile.css" "$P/assets/mobile.css"
cp "$P/../mobile-patch/mobile.js" "$P/assets/mobile.js"

# 控制台主题包四件套（v1.17.4）：规范 THEME-PACK-SPEC.md / console.schema.json / 示例 / 离线校验器。
# 随 APK 分发（assets/console-theme/），App 首次运行解到 /sdcard/<包名目录>/console/，
# 让跑在设备上的 AI 与用户都能直接读到（不必联网）。源目录 <仓库根>/console-theme/。
# ⚠ 四份源码各一份，改一处要同步四份（与 mobile.css 同理）。
CT_SRC="$P/../console-theme"
if [ -d "$CT_SRC" ]; then
  mkdir -p "$P/assets/console-theme"
  cp "$CT_SRC/THEME-PACK-SPEC.md" "$CT_SRC/console.schema.json" "$CT_SRC/console.example.json" "$CT_SRC/theme_pack_check.py" "$P/assets/console-theme/"
  for _ct in THEME-PACK-SPEC.md console.schema.json console.example.json theme_pack_check.py; do
    [ -f "$P/assets/console-theme/$_ct" ] || { echo "!! assets/console-theme/$_ct 缺失"; exit 1; }
  done
  echo "  控制台主题规范: assets/console-theme/（4 个文件）"
else
  echo "  !! 未找到 console-theme/（主题页的规范与自检入口将不可用）"; exit 1
fi

# 设备端技能（D1）：仓库 skills/<name>/SKILL.md → assets/skills/，App 启动时落到
# <filesDir>/.agents/skills/（引擎默认扫描的用户技能根，见 dsh-skill-filesystem）。
# 放这里而不是 payload：payload 会被解压/同步重写，filesDir 不会。
SK_SRC="$P/../skills"
if [ -d "$SK_SRC" ]; then
  rm -rf "$P/assets/skills"
  mkdir -p "$P/assets/skills"
  cp -r "$SK_SRC/." "$P/assets/skills/"
  _sk_n=$(ls -1 "$P/assets/skills" | wc -l | tr -d ' ')
  [ "$_sk_n" -gt 0 ] || { echo "!! assets/skills 为空"; exit 1; }
  echo "  设备端技能: assets/skills/（$_sk_n 个）"
else
  echo "  !! 未找到 skills/（内置技能将不可用）"; exit 1
fi

cp "$H/.dsh/cordis.patch.yml" "$P/staging/dshhome/"
cp "$H/.dsh/profiles/web/cordis.patch.yml" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/profiles/web/cordis.yml" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/profiles/web/package.json" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/profiles/web/pnpm-workspace.yaml" "$P/staging/dshhome/profiles/web/"
cp "$H/.dsh/settings.yaml" "$P/staging/dshhome/" 2>/dev/null   || cp "$H/.dsh/settings.yaml.imported" "$P/staging/dshhome/settings.yaml"   || { echo "!! 找不到 .dsh/settings.yaml（也没有 settings.yaml.imported），中止"; exit 1; }

# 安全检查：payload 里绝不能出现 API Key 或凭证文件
if grep -rqE "sk-[A-Za-z0-9]{20,}" "$P/staging" 2>/dev/null; then
  echo "!! 检测到 API Key 混入 payload，中止"; exit 1
fi
if [ -e "$P/staging/dshhome/.credentials.yaml" ]; then
  echo "!! 检测到 .credentials.yaml，中止"; exit 1
fi

echo "--- payload 各部分大小 ---"
du -sh "$P/staging/runtime" "$P/staging/dshroot" "$P/staging/dshhome" "$P/staging/bin"

( cd "$P/staging" && jar cMf "$P/assets/payload.zip" . )
echo "payload.zip: $(du -sh "$P/assets/payload.zip" | cut -f1)"

# ============================================================================
# B9：payload 完整性清单（assets/payload_manifest.txt）
# 目的：把"静默不一致"变成可读数字 ——
#   · 打包期：tools/build-apk.sh 核对 entries/bytes 与 payload.zip 是否一致；
#   · 运行期：App 启动后核对 runtime/ 的 文件数 与 字节数（别名由 applyLinks 重建，
#     所以期望文件数 = 包内文件数 + LINKS.txt 条目数；**字节数不变**）。
#     历史上 ICU 被实体化成 3 份独立 inode 的静默膨胀，就是这一条能当场抓到的。
# 计数口径 = **payload.zip 里的条目**（= staging 树，不含本清单文件自身）。
# ============================================================================
if command -v sha256sum >/dev/null 2>&1; then
  _sha256() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  _sha256() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  _sha256() { echo "none"; }
fi
_count() {  # $1=目录 → 文件行数
  find "$1" -type f 2>/dev/null | wc -l | tr -d ' '
}
# 字节数：用 stat -c %s（GNU coreutils 与 Android toybox 都支持），避免依赖 GNU find -printf
_sum_bytes() {
  find "$1" -type f -exec stat -c %s {} + 2>/dev/null | awk '{s+=$1} END {print s+0}'
}
_PAYLOAD_FILES=$(find "$P/staging" -type f | wc -l | tr -d ' ')
_PAYLOAD_BYTES=$(_sum_bytes "$P/staging")
_LINKS_N=$(grep -cv '^[[:space:]]*$' "$P/staging/runtime/lib/LINKS.txt" 2>/dev/null || echo 0)
# 每个目录算一行：files / bytes
_subtree_line() {  # $1=相对路径
  echo "subtree $1 files=$(_count "$P/staging/$1") bytes=$(_sum_bytes "$P/staging/$1")"
}
{
  echo "# dsh-payload-manifest v1"
  echo "# 计数口径：payload.zip 内的全部条目（= staging 树）；runtime/lib 的 soname 别名由 App 侧 applyLinks 重建"
  echo "variant=$DSH_VARIANT"
  echo "app_id=$V_APP_ID"
  echo "entries=$_PAYLOAD_FILES"
  echo "bytes=$_PAYLOAD_BYTES"
  echo "payload_zip_bytes=$(stat -c%s "$P/assets/payload.zip")"
  echo "payload_zip_sha256=$(_sha256 "$P/assets/payload.zip")"
  echo "links_expected=$_LINKS_N"
  _subtree_line runtime
  _subtree_line runtime/lib
  _subtree_line dshroot
  _subtree_line dshhome
  _subtree_line bin
  _subtree_line rish
} > "$P/assets/payload_manifest.txt"
echo "  payload 清单: entries=$_PAYLOAD_FILES bytes=$_PAYLOAD_BYTES links=$_LINKS_N"
grep -E '^subtree (runtime|runtime/lib) ' "$P/assets/payload_manifest.txt" | sed 's/^/    /'

echo "== 1/7 资源编译 (aapt) =="
mkdir -p "$P/out/gen" "$P/out/classes" "$P/out/dex"
aapt package -f -m -J "$P/out/gen" -M "$MANIFEST" -S "$P/res" -I "$AJ"
R_JAVA="$P/out/gen/$V_PKG_PATH/R.java"
[ -f "$R_JAVA" ] || { echo "!! aapt 未生成 $R_JAVA（包名与 manifest 不一致？）"; find "$P/out/gen" -name 'R.java'; exit 1; }

echo "== 2/7 javac =="
# 解压 Shizuku API + provider + aidl 的 classes.jar 供编译和 dex
SHIZUKU_CLS="$P/out/shizuku-cls"
rm -rf "$SHIZUKU_CLS"; mkdir -p "$SHIZUKU_CLS"
for AAR in "$P/libs/shizuku-api.aar" "$P/libs/shizuku-provider.aar" "$P/libs/shizuku-aidl.aar"; do
  TMP="$P/out/$(basename "$AAR" .aar)"
  rm -rf "$TMP"; mkdir -p "$TMP"
  ( cd "$TMP" && "$JAVA/jar" xf "$AAR" classes.jar )
  ( cd "$SHIZUKU_CLS" && "$JAVA/jar" xf "$TMP/classes.jar" )
done
SHIZUKU_JARS="$P/out/shizuku-api/classes.jar${CP_SEP}$P/out/shizuku-provider/classes.jar${CP_SEP}$P/out/shizuku-aidl/classes.jar"
# Windows(Git Bash)：MSYS 不转换"分号分隔的 POSIX 路径列表"，javac 会整体当一条路径找不到 → 用 cygpath 转 Windows 路径
GEN_CP="$P/out/gen"
if [ "$CP_SEP" = ";" ] && command -v cygpath >/dev/null 2>&1; then
  GEN_CP="$(cygpath -w "$P/out/gen")"
  SHIZUKU_JARS="$(cygpath -w "$P/out/shizuku-api/classes.jar")${CP_SEP}$(cygpath -w "$P/out/shizuku-provider/classes.jar")${CP_SEP}$(cygpath -w "$P/out/shizuku-aidl/classes.jar")"
fi
# javac 必须成功：失败立即中止（曾因 javac 找不到而产出无 MainActivity 的坏 APK，安装即闪退）
# v48：vscreen 全量源码编入 APK（App 进程内嵌 Operit server，不再单独 jar 部署）
# 变体构建：源码来自 out/src（包名已改写为变体 appId），并额外编译生成的 BuildVariant.java
VSC_SRC="$SRC_STAGE/$V_PKG_PATH/vscreen"
JAVA_SRCS="$(find "$SRC_STAGE" -name '*.java')"
if ! "$JAVA/javac" -source 1.8 -target 1.8 -bootclasspath "$AJ" \
  -classpath "$GEN_CP${CP_SEP}$SHIZUKU_JARS" -d "$P/out/classes" \
  $JAVA_SRCS \
  "$R_JAVA" \
  >"$P/out/javac.log" 2>&1; then
  echo "!! javac 编译失败，日志：$P/out/javac.log"
  tail -20 "$P/out/javac.log"
  exit 1
fi
grep -v "bootstrap class path\|warning:\|RestrictTo\|Note:\|deprecat" "$P/out/javac.log" || true
NCLASS="$(find "$P/out/classes" -name '*.class' | wc -l)"
echo "  javac 完成，class 数：$NCLASS"
[ "$NCLASS" -gt 0 ] || { echo "!! javac 产物为空，中止构建"; exit 1; }

echo "== 3/7 d8 -> dex =="
# class 列表写入 response file（Windows 命令行 8191 字符限制，98+ 个绝对路径会超长）
CLS_RSP="$P/out/classes.rsp"
if [ "$CP_SEP" = ";" ] && command -v cygpath >/dev/null 2>&1; then
  { find "$P/out/classes" -name '*.class'; find "$SHIZUKU_CLS" -name '*.class'; } | cygpath -w -f - > "$CLS_RSP"
else
  { find "$P/out/classes" -name '*.class'; find "$SHIZUKU_CLS" -name '*.class'; } > "$CLS_RSP"
fi
"$D8" --release --lib "$AJ" --min-api 24 --output "$P/out/dex" @"$CLS_RSP" \
  || { echo "!! d8 失败"; exit 1; }
# 校验 dex 必须包含 MainActivity（防止再次产出安装即闪退的坏包）
if ! grep -aq "MainActivity" "$P/out/dex/classes.dex"; then
  echo "!! classes.dex 缺少 MainActivity，中止构建"; exit 1
fi
echo "  classes.dex 含 MainActivity，$(stat -c%s "$P/out/dex/classes.dex") bytes"

echo "== 4/7 aapt 打包 + assets =="
aapt package -f -M "$MANIFEST" -S "$P/res" -I "$AJ" -A "$P/assets" -0 zip -F "$P/out/unsigned.apk"
( cd "$P/out/dex" && aapt add "$P/out/unsigned.apk" classes.dex )

echo "== 5/7 zipalign =="
zipalign -f 4 "$P/out/unsigned.apk" "$P/out/aligned.apk"

echo "== 6/7 签名 =="
[ -f "$KEY" ] || { echo "!! 缺少签名密钥 $KEY（release.jks 不入仓库，请自行准备；community 变体用 community.jks）"; exit 1; }
"$APKSIGNER" sign --ks "$KEY" --ks-pass "pass:${KEYSTORE_PASS:?请先 export KEYSTORE_PASS=签名密码}" --ks-key-alias "${KEYSTORE_ALIAS:-dsh}" --key-pass "pass:$KEYSTORE_PASS" \
  --out "$APK_OUT" "$P/out/aligned.apk"

echo "== 7/7 校验 =="
"$APKSIGNER" verify --print-certs "$APK_OUT"
aapt dump badging "$APK_OUT" | head -8
ls -la "$APK_OUT"
echo "BUILD OK [$DSH_VARIANT] -> $APK_OUT"
