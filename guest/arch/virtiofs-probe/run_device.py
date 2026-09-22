#!/usr/bin/env python3
"""Run a verified, diskless CI probe locally on the Pixel through authorized rish."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import uuid

from ci_test import verify_result

FILES = {"Image", "kernel.config", "probe-initramfs.cpio.gz", "probe.jar", "probe-provenance.txt"}


def verify_artifact(root):
    expected = {}
    for line in (root / "PROBE_SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9._-]+)", line)
        if not match or match[2] in expected:
            raise ValueError("Malformed or duplicate artifact checksum")
        expected[match[2]] = match[1]
    if set(expected) != FILES:
        raise ValueError("Probe artifact has unexpected or missing files")
    for name, digest in expected.items():
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Not a regular artifact file: {name}")
        with path.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != digest:
                raise ValueError(f"Checksum mismatch: {name}")
    config = (root / "kernel.config").read_text().splitlines()
    for option in ("FUSE_FS", "VIRTIO_FS", "BLK_DEV_INITRD", "RD_GZIP"):
        if f"CONFIG_{option}=y" not in config:
            raise ValueError(f"Kernel requires built-in CONFIG_{option}")
    return expected


def shell(command, timeout=45):
    # rish can drop piped stdout on this device. Results are copied to the
    # disposable shared directory and read as files instead of trusting stdout.
    subprocess.run(["rish", "-c", command], check=True, timeout=timeout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path, help="Extracted Virtio-FS probe CI artifact")
    args = parser.parse_args()
    expected = verify_artifact(args.artifact)
    if not shutil.which("rish"):
        raise SystemExit("rish is required; authorize the existing Shizuku integration first")
    token = uuid.uuid4().hex
    public = Path("/storage/emulated/0/Download") / f"aether-vufs-{token}"
    stage = f"/data/local/tmp/aether-vufs-{token}"
    state = Path.home() / ".local/state/termux-aether" / f"virtiofs-{token}"
    state.mkdir(parents=True, mode=0o700)
    public.mkdir()
    share = public / "share"
    share.mkdir()
    (share / "host.txt").write_bytes(b"aether-host-v1\n")
    report = {"status": "failed", "state": str(state), "stage": stage,
              "public": str(public), "root_disk_attached": False}
    staged = False
    try:
        for name in sorted(FILES | {"PROBE_SHA256SUMS"}):
            shutil.copyfile(args.artifact / name, public / name)
        copies = " && ".join(f"cp {shlex.quote(str(public / name))} {stage}/{name}"
                             for name in sorted(FILES | {"PROBE_SHA256SUMS"}))
        # Embed hashes verified from the private artifact. Shared-storage copies
        # (including their manifest) are not trusted as verification authority.
        checksums = "".join(f"{expected[name]}  {name}\n" for name in sorted(FILES))
        check = f"printf '%s' {shlex.quote(checksums)} | sha256sum -c - > integrity.txt"
        shell(f"umask 077 && mkdir {stage} && {copies} && cd {stage} && "
              f"{check} && chmod 0444 probe.jar")
        staged = True
        command = (
            f"cd {stage} && {check} && "
            f"CLASSPATH={stage}/probe.jar:/apex/com.android.virt/javalib/framework-virtualization.jar "
            f"app_process /system/bin VirtioFsProbe --diskless-probe {stage} {shlex.quote(str(share))} "
            f"> {stage}/launcher.txt 2>&1; probe_rc=$?; "
            f"printf '%s\\n' \"$probe_rc\" > {stage}/exit.txt; "
            f"for name in launcher.txt console.txt exit.txt; do "
            f"if [ -f {stage}/\"$name\" ]; then cp {stage}/\"$name\" {shlex.quote(str(public))}/\"$name\"; fi; done"
        )
        shell(command)
        for name in ("launcher.txt", "console.txt", "exit.txt"):
            if (public / name).is_file():
                shutil.copyfile(public / name, state / name)
        if (public / "exit.txt").read_text().strip() != "0":
            raise RuntimeError("AVF launcher failed; see saved launcher.txt")
        verify_result((public / "console.txt").read_text(), share)
        shutil.copyfile(share / "guest/result.txt", state / "guest-result.txt")
        report["status"] = "passed"
        report["verified"] = ["mount", "host-to-guest read", "guest-to-host write", "rename", "unmount"]
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        (state / "result.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"Probe {report['status']}; evidence: {state}")
        # Failed runs retain only their uniquely named staging directories for
        # diagnosis. Nothing under termux-arch-v2 is ever opened or removed.
        if report["status"] == "passed" and staged:
            shell(f"rm -rf -- {stage}", timeout=10)
            shutil.rmtree(public)


if __name__ == "__main__":
    main()
