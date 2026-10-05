#!/usr/bin/env python3
"""把 Termux(aarch64) 的 **真 bash** 及其依赖闭包取进 devhome，供 build.sh 打进 payload。

为什么需要真 bash：DSH 内置终端用 `bash --rcfile <生成的 bashrc> -i` 启动 shell，
那个 bashrc 是纯 bash 语法（BASH_VERSINFO / PROMPT_COMMAND / declare -p / 数组），
而 payload 里的 `bin/bash` 只是 /system/bin/sh(mksh) 的包装 —— mksh 既认不出 --rcfile，
也跑不了那个 rcfile，于是终端报 `--: unknown option` 退出(1)。

产物布局（与 devhome 里的 git/ 树一致，build.sh 会找 <devhome>/bash/bin/bash）：
    <devhome>/bash/bin/bash
    <devhome>/bash/lib/*.so*          （libandroid-support / readline / ncurses / libiconv）

用法：
    python tools/fetch-bash.py [--devhome DIR] [--arch aarch64]
默认 devhome 取 $DSH_DEV_HOME，否则 .cache/devhome
"""
from __future__ import annotations

import argparse
import gzip
import io
import os
import re
import sys
import tarfile
import urllib.request

BASE = "https://packages.termux.dev/apt/termux-main"
INDEX = BASE + "/dists/stable/main/binary-{arch}/Packages.gz"
# Termux 的安装前缀（.deb 里的路径）
TERMUX_PREFIX = "data/data/com.termux/files/usr/"
# 需要的可执行文件与库目录
WANT_BIN = {"bash"}
WANT_LIB_PREFIXES = ("libandroid-support", "libreadline", "libncurses", "libtinfo",
                     "libiconv", "libncursesw", "libandroid-glob", "libandroid-execinfo")
# 只取运行期真正需要的包：**不要**跟着 apt 的 Depends 递归（bash 依赖 termux-tools，
# 而 termux-tools 会把 openssl/curl/libc++ 等半个 Termux 拖进来，对 bash 运行毫无必要）。
# 取完后再用 ELF 的 DT_NEEDED 校验是否还有缺的库（见 verify_needed）。
ROOTS = ["bash", "libandroid-support", "libiconv", "readline", "ncurses"]


def fetch(url: str, timeout: int = 180) -> bytes:
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read()


def load_index(arch: str) -> dict[str, dict]:
    raw = gzip.decompress(fetch(INDEX.format(arch=arch))).decode("utf-8", "replace")
    pkgs: dict[str, dict] = {}
    for stanza in raw.split("\n\n"):
        m = re.match(r"Package: (\S+)", stanza)
        if not m:
            continue
        name = m.group(1)
        entry = {"name": name, "depends": [], "filename": "", "version": ""}
        for line in stanza.split("\n"):
            if line.startswith("Version: "):
                entry["version"] = line[9:].strip()
            elif line.startswith("Filename: "):
                entry["filename"] = line[10:].strip()
            elif line.startswith("Depends: "):
                # "libandroid-support, libiconv, readline (>= 8.3), termux-tools"
                for part in line[9:].split(","):
                    dep = part.strip().split(" ")[0].split("(")[0].strip()
                    if dep:
                        entry["depends"].append(dep)
        pkgs[name] = entry
    return pkgs


def closure(pkgs: dict[str, dict], roots: list[str]) -> list[str]:
    """按 ROOTS 顺序取包（固定列表，不递归 apt 依赖树）。

    apt 的 Depends 是**打包期**语义（bash → termux-tools → openssl/curl/…），
    对我们要跑 bash 这件事毫无必要；真正要的是 ELF 运行期依赖的共享库。
    所以这里只做"存在性"校验，缺库由 verify_needed() 事后用 DT_NEEDED 兜底。
    """
    out: list[str] = []
    for name in roots:
        entry = pkgs.get(name)
        if entry is None:
            print(f"  ! 索引里没有 {name}（跳过）", file=sys.stderr)
            continue
        if not entry["filename"]:
            print(f"  ! {name} 是虚拟包/无文件（跳过）", file=sys.stderr)
            continue
        out.append(name)
    return out


def dynamic_entries(path: str, want_tag: int) -> list[str]:
    """解析 ELF .dynamic 段，取出指定 tag 的字符串项（DT_NEEDED=1 / DT_SONAME=14）。"""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF" or data[4] != 2:      # 只处理 64 位
        return []
    little = data[5] == 1
    e_shoff = int.from_bytes(data[0x28:0x30], "little" if little else "big")
    e_shentsize = int.from_bytes(data[0x3A:0x3C], "little" if little else "big")
    e_shnum = int.from_bytes(data[0x3C:0x3E], "little" if little else "big")
    dyn_off = dyn_size = strtab_off = None
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        sh_type = int.from_bytes(data[off + 4:off + 8], "little" if little else "big")
        sh_off = int.from_bytes(data[off + 0x18:off + 0x20], "little" if little else "big")
        sh_size = int.from_bytes(data[off + 0x20:off + 0x28], "little" if little else "big")
        if sh_type == 6:      # SHT_DYNAMIC
            dyn_off, dyn_size = sh_off, sh_size
        elif sh_type == 3:    # SHT_STRTAB（第一个是 .dynstr）
            if strtab_off is None:
                strtab_off = sh_off
    if dyn_off is None or strtab_off is None:
        return []
    names: list[str] = []
    for pos in range(dyn_off, dyn_off + dyn_size, 16):
        tag = int.from_bytes(data[pos:pos + 8], "little" if little else "big")
        val = int.from_bytes(data[pos + 8:pos + 16], "little" if little else "big")
        if tag == 0:
            break
        if tag == want_tag:
            end = data.index(b"\x00", strtab_off + val)
            names.append(data[strtab_off + val:end].decode("ascii", "replace"))
    return names


def needed_libs(path: str) -> list[str]:
    return dynamic_entries(path, 1)      # DT_NEEDED


def make_soname_aliases(lib_dir: str) -> list[str]:
    """为每个库按 DT_SONAME 再复制一份别名文件。

    运行期动态链接器按 DT_NEEDED 里的 **soname** 找文件（如 libreadline.so.8），
    而 Termux 包里的实体名是 libreadline.so.8.3，原包靠符号链接补齐。
    这里直接复制成两份（Windows 上建符号链接不可靠，也不该依赖权限）。
    """
    made: list[str] = []
    for fn in sorted(os.listdir(lib_dir)):
        path = os.path.join(lib_dir, fn)
        if not os.path.isfile(path) or ".so" not in fn:
            continue
        for soname in dynamic_entries(path, 14):
            if soname == fn or os.path.exists(os.path.join(lib_dir, soname)):
                continue
            with open(path, "rb") as src, open(os.path.join(lib_dir, soname), "wb") as dst:
                dst.write(src.read())
            os.chmod(os.path.join(lib_dir, soname), 0o755)
            made.append(soname)
    return made


def verify_needed(bash_path: str, lib_dir: str) -> list[str]:
    """返回 bash 需要、但我们没取到的库名。libc/libdl/libm 由系统提供，不算缺。"""
    system = {"libc.so", "libdl.so", "libm.so", "liblog.so", "libz.so", "libstdc++.so"}
    have = set()
    for fn in os.listdir(lib_dir):
        have.add(fn)
        have.add(fn.split(".so")[0] + ".so")
    missing = []
    for lib in needed_libs(bash_path):
        if lib in system or lib in have or any(h.startswith(lib) for h in have):
            continue
        missing.append(lib)
    return missing


def extract_deb(deb: bytes, out_bin: str, out_lib: str) -> list[str]:
    """从 .deb 里取出 usr/bin/* 与 usr/lib/*.so*，返回写入的文件名。"""
    written: list[str] = []
    # .deb = ar 归档，逐个成员：debian-binary / control.tar.* / data.tar.*
    if not deb.startswith(b"!<arch>\n"):
        raise ValueError("不是 ar 归档")
    pos = 8
    data_member = None
    while pos + 60 <= len(deb):
        header = deb[pos:pos + 60]
        name = header[0:16].decode("ascii", "replace").strip().rstrip("/")
        size = int(header[48:58].decode("ascii", "replace").strip() or "0")
        body_start = pos + 60
        body = deb[body_start:body_start + size]
        if name.startswith("data.tar"):
            data_member = body
            break
        pos = body_start + size + (size % 2)
    if data_member is None:
        raise ValueError("没找到 data.tar")
    with tarfile.open(fileobj=io.BytesIO(data_member), mode="r:*") as tf:
        for member in tf.getmembers():
            if not member.isfile():
                continue
            # .deb 里成员名常带 "./" 前缀（./data/data/com.termux/...），先归一化
            name = member.name
            while name.startswith("./"):
                name = name[2:]
            name = name.lstrip("/")
            if not name.startswith(TERMUX_PREFIX):
                continue
            rel = name[len(TERMUX_PREFIX):]
            target = None
            if rel.startswith("bin/") or rel.startswith("libexec/"):
                base = os.path.basename(rel)
                if base in WANT_BIN or base.startswith("bash"):
                    target = os.path.join(out_bin, base)
            elif rel.startswith("lib/"):
                base = os.path.basename(rel)
                if base.startswith(WANT_LIB_PREFIXES):
                    target = os.path.join(out_lib, base)
            if target is None:
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            f = tf.extractfile(member)
            if f is None:
                continue
            with open(target, "wb") as out:
                out.write(f.read())
            os.chmod(target, 0o755)
            written.append(os.path.relpath(target))
    return written


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--devhome", default=os.environ.get("DSH_DEV_HOME") or ".cache/devhome")
    ap.add_argument("--arch", default="aarch64")
    args = ap.parse_args()

    out_root = os.path.join(args.devhome, "bash")
    out_bin = os.path.join(out_root, "bin")
    out_lib = os.path.join(out_root, "lib")
    os.makedirs(out_bin, exist_ok=True)
    os.makedirs(out_lib, exist_ok=True)

    print(f"== 读取 Termux 索引（{args.arch}）==")
    pkgs = load_index(args.arch)
    print(f"   共 {len(pkgs)} 个包")
    wanted = closure(pkgs, ROOTS)
    print("== 取这些包：" + ", ".join(f"{n}({pkgs[n]['version']})" for n in wanted) + " ==")

    all_written: list[str] = []
    for name in wanted:
        url = f"{BASE}/{pkgs[name]['filename']}"
        print(f"-- {name} <- {url}")
        try:
            written = extract_deb(fetch(url), out_bin, out_lib)
        except Exception as exc:  # 单个包失败不致命
            print(f"   ! 处理失败：{exc}", file=sys.stderr)
            continue
        all_written += written
        for w in written:
            print(f"   + {w}")

    bash = os.path.join(out_bin, "bash")
    if not os.path.isfile(bash):
        print("!! 没有取到 bash 可执行文件", file=sys.stderr)
        return 1
    aliases = make_soname_aliases(out_lib)
    if aliases:
        print("== 按 DT_SONAME 生成别名：" + ", ".join(aliases) + " ==")
    missing = verify_needed(bash, out_lib)
    print(f"\n== 完成：{bash}（{os.path.getsize(bash)} B），库 {len(os.listdir(out_lib))} 个 ==")
    if missing:
        print("!! bash 还缺这些共享库（需要补进 ROOTS 或 WANT_LIB_PREFIXES）："
              + ", ".join(missing), file=sys.stderr)
        return 2
    print("   ELF 依赖自检：所需共享库都在 bash/lib 里 [OK]")
    print("build.sh 会自动使用 <devhome>/bash/bin/bash 作为 payload/bin/bash，并把 lib/ 拷进 runtime/lib")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
