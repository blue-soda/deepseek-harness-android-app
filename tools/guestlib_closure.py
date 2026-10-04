#!/usr/bin/env python3
"""计算 arm64 node 在 ndk_translation 下需要的 guest 库闭包。

ndk_translation 给 arm64 guest 的库搜索路径只有系统 guest 目录
（/system/lib64/arm64 等），不认 LD_LIBRARY_PATH。因此 node 的每一个
DT_NEEDED（及其传递依赖）都必须能在 guest 目录里找到同名文件。

本脚本从 bin/node 出发做 BFS，对照 guest 目录现有文件名，
输出「必须补进 guest 目录」的清单。
"""
import struct
import sys
from pathlib import Path

# adb shell "ls /system/lib64/arm64" 的实测结果（56 项）
GUEST = {
    "android.hardware.renderscript@1.0.so", "ld-android.so", "libaaudio.so",
    "libamidi.so", "libandroid_runtime.so", "libandroid.so", "libandroidicu.so",
    "libbase.so", "libbcinfo.so", "libbinder_ndk.so", "libblas.so", "libc.so",
    "libc++.so", "libcamera2ndk.so", "libcompiler_rt.so", "libcrypto.so",
    "libcutils.so", "libdl_android.so", "libdl.so", "libEGL.so", "libft2.so",
    "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libhidlbase.so",
    "libicu.so", "libicui18n.so", "libicuuc.so", "libjnigraphics.so",
    "liblog.so", "liblzma.so", "libm.so", "libmediandk.so",
    "libnative_bridge_vdso.so", "libnativehelper.so", "libnativewindow.so",
    "libneuralnetworks.so", "libOpenMAXAL.so", "libOpenSLES.so", "libpng.so",
    "libRS_internal.so", "libRS.so", "libRSCpuRef.so", "libRSDriver.so",
    "libRSSupport.so", "libsqlite.so", "libssl.so", "libstdc++.so",
    "libsync.so", "libunwindstack.so", "libutils.so", "libutilscallstack.so",
    "libvndksupport.so", "libvulkan.so", "libwebviewchromium_plat_support.so",
    "libz.so",
}


def dt_needed(path: Path) -> list[str]:
    """读 ELF64 的 DT_NEEDED 列表。"""
    d = path.read_bytes()
    if d[:4] != b"\x7fELF" or d[4] != 2:
        return []
    e_shoff = struct.unpack_from("<Q", d, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", d, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", d, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", d, 0x3E)[0]
    secs = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        name, typ, flags, addr, off, size, link, info, align, entsize = \
            struct.unpack_from("<IIQQQQIIQQ", d, o)
        secs.append({"name": name, "off": off, "size": size})
    shstr = secs[e_shstrndx]

    def sname(x: int) -> str:
        b = d[shstr["off"] + x:]
        return b[: b.index(b"\0")].decode()

    by_name = {sname(s["name"]): s for s in secs}
    if ".dynamic" not in by_name or ".dynstr" not in by_name:
        return []
    dyn, dynstr = by_name[".dynamic"], by_name[".dynstr"]
    out, o = [], dyn["off"]
    while True:
        tag, val = struct.unpack_from("<qQ", d, o)
        if tag == 0:
            break
        if tag == 1:
            b = d[dynstr["off"] + val:]
            out.append(b[: b.index(b"\0")].decode())
        o += 16
    return out


def main() -> int:
    libdir = Path(sys.argv[1])
    node = Path(sys.argv[2])

    # BFS
    seen: set[str] = set()
    order: list[str] = []
    queue = [("node", dt_needed(node))]
    missing_in_libdir: list[str] = []
    while queue:
        _who, needed = queue.pop(0)
        for n in needed:
            if n in seen:
                continue
            seen.add(n)
            order.append(n)
            if n in GUEST:
                continue  # guest 目录已有同名文件 → 不用补
            f = libdir / n
            if f.exists():
                queue.append((n, dt_needed(f)))
            else:
                missing_in_libdir.append(n)

    must_push = [n for n in order if n not in GUEST]
    satisfied = [n for n in order if n in GUEST]

    print(f"node 的依赖闭包共 {len(order)} 个 soname\n")
    print(f"guest 目录已满足（不用补）：{len(satisfied)} 个")
    for n in satisfied:
        print(f"    {n}")
    print(f"\n**必须补进 /system/lib64/arm64/：{len(must_push)} 个**")
    for n in must_push:
        f = libdir / n
        size = f"{f.stat().st_size:,}" if f.exists() else "缺失!"
        print(f"    {n:28} {size:>12}")
    if missing_in_libdir:
        print(f"\n⚠ 在 runtime/lib 里也找不到的 soname（{len(missing_in_libdir)} 个）：")
        for n in missing_in_libdir:
            print(f"    {n}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
