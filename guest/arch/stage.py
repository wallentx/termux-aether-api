#!/usr/bin/env python3
"""Stage a verified CI artifact through an already paired ADB connection. No builds."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

BASE = "/data/local/tmp/termux-arch-v2"


def verify(directory):
    expected = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9._-]+)", line)
        if not match or match[2] in expected:
            raise ValueError("Invalid or duplicate checksum entry")
        expected[match[2]] = match[1]
    for name in ("Image", "arch-rootfs.img.zst", "arch-network-host"):
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
    subprocess.run(adb + ["push", str(args.artifact / "arch-network-host"),
                          stage + "/arch-network-host"], check=True, timeout=180)
    # Decompress the CI artifact into a sparse temporary file so adb can use its
    # compressed sync protocol. Streaming raw bytes through adb shell sends all
    # 6 GiB over Wi-Fi, including the empty filesystem space.
    with tempfile.TemporaryDirectory(prefix="termux-arch-stage-", dir=os.environ.get("TMPDIR")) as work:
        disk = Path(work) / "arch-rootfs.img"
        subprocess.run(["zstd", "-d", "--sparse", "--no-progress", "-o", str(disk),
                        str(args.artifact / "arch-rootfs.img.zst")], check=True, timeout=300)
        if not 1024 * 1024 <= disk.stat().st_size <= 8 * 1024**3:
            raise ValueError("Unexpected guest disk size")
        subprocess.run(adb + ["push", "-z", "zstd", str(disk), stage + "/arch-rootfs.img"],
                       check=True, timeout=900)
    shell(f"chmod 600 {stage}/Image {stage}/arch-rootfs.img && "
          f"chmod 700 {stage}/arch-network-host && "
          f"test ! -e {BASE} && test ! -L {BASE} && mv -T {stage} {BASE}")
    print("Guest staged. In Termux: termux-arch --shell")


if __name__ == "__main__":
    main()
