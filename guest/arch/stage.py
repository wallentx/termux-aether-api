#!/usr/bin/env python3
"""Stage a verified CI artifact through an already paired ADB connection. No builds."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import uuid

BASE = "/data/local/tmp/termux-arch-v1"


def verify(directory):
    expected = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9._-]+)", line)
        if not match or match[2] in expected:
            raise ValueError("Invalid or duplicate checksum entry")
        expected[match[2]] = match[1]
    for name in ("Image", "arch-rootfs.img.zst"):
        with (directory / name).open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        if digest != expected.get(name):
            raise ValueError(f"Checksum mismatch: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="ADB serial/IP:port")
    parser.add_argument("artifact", type=Path)
    args = parser.parse_args()
    verify(args.artifact)
    adb = ["adb", "-s", args.serial]
    stage = BASE + ".staging-" + uuid.uuid4().hex
    def shell(command, **kwargs):
        return subprocess.run(adb + ["shell", command], check=True, timeout=900, **kwargs)
    shell(f"test ! -e {BASE} && test ! -L {BASE} && umask 077 && mkdir {stage}")
    # Leave a failed staging directory for inspection; never delete an existing guest.
    subprocess.run(adb + ["push", str(args.artifact / "Image"), stage + "/Image"], check=True, timeout=180)
    unpack = subprocess.Popen(["zstd", "-d", "-c", str(args.artifact / "arch-rootfs.img.zst")], stdout=subprocess.PIPE)
    try:
        shell(f"umask 077; set -eC; cat > {stage}/arch-rootfs.img", stdin=unpack.stdout)
    finally:
        unpack.stdout.close()
        if unpack.wait(timeout=10) != 0:
            raise RuntimeError("Guest decompression failed")
    shell(f"chmod 600 {stage}/Image {stage}/arch-rootfs.img && "
          f"test ! -e {BASE} && test ! -L {BASE} && mv -T {stage} {BASE}")
    print("Guest staged. In Termux: termux-arch-vm --start")


if __name__ == "__main__":
    main()
