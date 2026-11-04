"""Check 16 KB ELF alignment of native libraries produced by a build.

Usage (from the project root, after a build):
    python tools/check_elf_alignment.py            # scans app/build
    python tools/check_elf_alignment.py path/dir   # or any directory / extracted APK

Prints OK/FAIL per 64-bit .so, based on the p_align of its PT_LOAD segments (needs >= 0x4000).
"""
import glob
import os
import struct
import sys

root = sys.argv[1] if len(sys.argv) > 1 else os.path.join("app", "build")
seen = set()
failed = False
for f in sorted(glob.glob(os.path.join(root, "**", "*.so"), recursive=True)):
    p = f.replace("\\", "/")
    if "arm64-v8a" not in p and "x86_64" not in p:
        continue
    data = open(f, "rb").read()
    key = (os.path.basename(f), len(data), "arm64-v8a" in p)
    if key in seen or data[:4] != b"\x7fELF" or data[4] != 2:
        continue
    seen.add(key)
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    aligns = set()
    for i in range(phnum):
        o = phoff + i * phentsize
        if struct.unpack_from("<I", data, o)[0] == 1:  # PT_LOAD
            aligns.add(struct.unpack_from("<Q", data, o + 48)[0])
    ok = all(a >= 0x4000 for a in aligns)
    failed |= not ok
    print(("OK   " if ok else "FAIL ") + p, sorted(hex(a) for a in aligns))
sys.exit(1 if failed else 0)
