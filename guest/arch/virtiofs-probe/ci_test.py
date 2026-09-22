#!/usr/bin/env python3
"""Boot only the diskless probe under QEMU. This tests correctness, not speed."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def verify_result(console, share):
    if "AETHER_VIRTIOFS_FAIL_V1" in console or "Kernel panic" in console:
        raise ValueError("Guest reported failure")
    for marker in ("AETHER_VIRTIOFS_BEGIN_V1", "AETHER_VIRTIOFS_IO_OK_V1", "AETHER_VIRTIOFS_PASS_V1"):
        if console.splitlines().count(marker) != 1:
            raise ValueError(f"Missing or duplicate guest marker: {marker}")
    if (share / "host.txt").read_bytes() != b"aether-host-v1\n":
        raise ValueError("Host sentinel changed")
    if (share / "guest/result.txt").read_bytes() != b"aether-guest-v1\n":
        raise ValueError("Guest write not visible to host")
    if (share / "guest/pending.txt").exists():
        raise ValueError("Guest rename incomplete")


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Run this VM test in CI.")
    out = Path(sys.argv[1]).resolve()
    with tempfile.TemporaryDirectory(prefix="aether-vufs-", dir=os.environ["RUNNER_TEMP"]) as temp:
        work = Path(temp)
        share = work / "share"
        share.mkdir()
        (share / "host.txt").write_bytes(b"aether-host-v1\n")
        sock = work / "fs.sock"
        # CI runs as root for the isolated namespace backend and temporary fixture
        # cleanup. This is not the Pixel's host backend or its permission model.
        with (out / "virtiofs-backend.txt").open("wb") as log:
            backend = subprocess.Popen(["/usr/libexec/virtiofsd", "--socket-path", str(sock),
                                        "--shared-dir", str(share)], stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 10
                while not sock.exists():
                    if backend.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("Virtio-FS backend did not become ready")
                    time.sleep(.05)
                with (out / "virtiofs-console.txt").open("wb") as console:
                    subprocess.run([
                        "qemu-system-aarch64", "-machine", "virt", "-cpu", "max", "-m", "256",
                        "-nodefaults", "-no-reboot", "-display", "none", "-serial", "stdio",
                        "-kernel", str(out / "Image"), "-initrd", str(out / "probe-initramfs.cpio.gz"),
                        "-append", "console=ttyAMA0 rdinit=/init panic=-1",
                        "-object", "memory-backend-memfd,id=mem,size=256M,share=on",
                        "-numa", "node,memdev=mem",
                        "-chardev", f"socket,id=fs,path={sock}",
                        "-device", "vhost-user-fs-pci,chardev=fs,tag=aether_probe",
                    ], stdin=subprocess.DEVNULL, stdout=console, stderr=subprocess.STDOUT,
                        timeout=90, check=True)
                verify_result((out / "virtiofs-console.txt").read_text(), share)
                print("Diskless Virtio-FS mount/read/write/rename/unmount verified")
            finally:
                if backend.poll() is None:
                    backend.terminate()
                try:
                    backend.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    backend.kill()
                    backend.wait()


if __name__ == "__main__":
    main()
