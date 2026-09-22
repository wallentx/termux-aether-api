#!/usr/bin/env python3
"""Pack the static guest probe as newc without root, mounts or a root filesystem."""
import gzip
from pathlib import Path
import stat
import struct
import sys


def entry(name, mode, data=b"", ino=1, rdev=(0, 0)):
    name = name.encode("ascii") + b"\0"
    fields = (ino, mode, 0, 0, 1, 0, len(data), 0, 0, *rdev, len(name), 0)
    header = b"070701" + b"".join(f"{value:08x}".encode() for value in fields)
    prefix = header + name
    return prefix + b"\0" * (-len(prefix) % 4) + data + b"\0" * (-len(data) % 4)


def build(binary):
    # ELF64 little-endian AArch64 ET_EXEC/ET_DYN; do not package a host x86 binary.
    if len(binary) < 64 or binary[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", binary, 18)[0] != 183:
        raise ValueError("Expected a little-endian ELF64 AArch64 init")
    phoff = struct.unpack_from("<Q", binary, 32)[0]
    phsize, phnum = struct.unpack_from("<HH", binary, 54)
    if phsize < 56 or phoff + phsize * phnum > len(binary) or not phnum:
        raise ValueError("Invalid ELF program headers")
    if any(struct.unpack_from("<I", binary, phoff + phsize * i)[0] == 3 for i in range(phnum)):
        raise ValueError("Init must be static: initramfs contains no dynamic loader")
    archive = b"".join([
        entry("dev", stat.S_IFDIR | 0o755),
        entry("dev/console", stat.S_IFCHR | 0o600, ino=2, rdev=(5, 1)),
        entry("share", stat.S_IFDIR | 0o755, ino=3),
        entry("init", stat.S_IFREG | 0o755, binary, ino=4),
        entry("TRAILER!!!", 0, ino=0),
    ])
    return gzip.compress(archive, mtime=0)


if __name__ == "__main__":
    source, output = map(Path, sys.argv[1:])
    output.write_bytes(build(source.read_bytes()))
