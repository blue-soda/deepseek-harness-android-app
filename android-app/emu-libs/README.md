# 模拟器（x86_64）guest 库

`--emulator` 构建会把这三类库打进 APK 的 `lib/x86_64/` 供 `ndk_translation` 使用：

- `libz.so`（zlib）
- `libssl.so` / `libcrypto.so`（OpenSSL）
- `liblzma.so.*`（xz）

来源：从 Android 模拟器 `adb pull /system/lib64/<lib>` 取出（x86_64 版），随机型/系统镜像不同而不同。
构建脚本优先用 `.cache/x64libs/`（本机缓存），没有再回退到本目录。
**这些库只用于模拟器包，绝不能进 arm64 包**（装真机会 `EM_X86_64` 崩溃）。
