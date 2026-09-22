#!/usr/bin/env python3
"""Upgrade only the Arch AVF kernel, preserving its disk and a verified rollback copy."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import uuid


def verify_artifact(root, commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Use the full, independently selected CI source commit")
    # Reuse the exact probe manifest/driver checks that validated this kernel.
    probe = Path(__file__).resolve().parent.parent / "virtiofs-probe"
    import sys
    sys.path.insert(0, str(probe))
    spec = importlib.util.spec_from_file_location("probe_artifact", probe / "run_device.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    expected = module.verify_artifact(root)
    provenance = (root / "probe-provenance.txt").read_text()
    if provenance != f"commit={commit}\npurpose=diskless-virtiofs-probe\n":
        raise ValueError("Artifact source commit/purpose does not match")
    return expected["Image"]


def require_stopped(status, *, rollback=False):
    states = ("stopped", "error") if rollback else ("stopped",)
    if (status.get("status") not in states or status.get("running") is not False
            or status.get("active_sessions") != 0
            or (not rollback and status.get("clean_shutdown") is not True)):
        raise RuntimeError("Arch must be stopped with no sessions (new installs also require clean shutdown); "
                           "use termux-arch-vm --stop first")


def status():
    result = subprocess.run(["termux-arch-vm", "--status"], check=True, capture_output=True,
                            text=True, timeout=30)
    return json.loads(result.stdout)


def shell(command):
    subprocess.run(["rish", "-c", command], check=True, timeout=90)


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--helper", type=Path, required=True, help="Trusted kernel-upgrade.jar built from this source")
    sub = parser.add_subparsers(dest="operation", required=True)
    sub.add_parser("inspect", help="Inspect the installed kernel while Arch is stopped")
    install = sub.add_parser("install", help="Install a verified, extracted Virtio-FS CI kernel")
    install.add_argument("artifact", type=Path)
    install.add_argument("--commit", required=True, help="Exact source commit of the successful CI run")
    rollback = sub.add_parser("rollback", help="Restore a saved kernel; retain the current one too")
    rollback.add_argument("backup", help="32-character backup ID printed by install")
    args = parser.parse_args()
    if not args.helper.is_file() or args.helper.is_symlink():
        parser.error("Helper must be a trusted regular jar file")
    candidate = verify_artifact(args.artifact, args.commit) if args.operation == "install" else "-"
    if args.operation == "rollback" and not re.fullmatch(r"[0-9a-f]{32}", args.backup):
        parser.error("Invalid backup ID")
    if args.operation != "inspect":
        require_stopped(status(), rollback=args.operation == "rollback")
    token = uuid.uuid4().hex
    public = Path("/storage/emulated/0/Download") / f"aether-kernel-{token}"
    stage = f"/data/local/tmp/aether-kernel-{token}"
    state = Path.home() / ".local/state/termux-aether" / f"kernel-{token}"
    state.mkdir(parents=True, mode=0o700)
    public.mkdir()
    report = {"status": "failed", "operation": args.operation, "state": str(state),
              "stage": stage, "public": str(public)}
    success = False
    try:
        inputs = {"kernel-upgrade.jar": args.helper}
        if args.operation == "install":
            inputs["Image"] = args.artifact / "Image"
        expected = {name: sha(path) for name, path in inputs.items()}
        if args.operation == "install" and expected["Image"] != candidate:
            raise ValueError("Artifact changed after verification")
        for name, path in inputs.items():
            shutil.copyfile(path, public / name)
        copies = " && ".join(f"cp {shlex.quote(str(public / name))} {stage}/{name}" for name in inputs)
        checksums = "".join(f"{digest}  {name}\n" for name, digest in expected.items())
        check = f"printf '%s' {shlex.quote(checksums)} | sha256sum -c - > integrity.txt"
        shell(f"umask 077 && mkdir {stage} && {copies} && cd {stage} && {check} && chmod 0444 kernel-upgrade.jar")

        def invoke(operation, current="-", wanted="-", source="-"):
            arguments = " ".join(shlex.quote(value) for value in (operation, stage, current, wanted, source))
            # Complete results travel as files: rish stdout can lose chunks on this firmware.
            shell(f"cd {stage} && rm -f result.json && {check} && "
                  f"CLASSPATH={stage}/kernel-upgrade.jar app_process /system/bin KernelUpgrade {arguments} "
                  f"> {stage}/{operation}.log 2>&1; upgrade_rc=$?; "
                  f"printf '%s\\n' \"$upgrade_rc\" > {stage}/exit.txt; "
                  f"cp {stage}/exit.txt {shlex.quote(str(public))}/exit.txt; "
                  f"cp {stage}/{operation}.log {shlex.quote(str(public))}/{operation}.log; "
                  f"if [ -f {stage}/result.json ]; then cp {stage}/result.json {shlex.quote(str(public))}/result.json; fi")
            shutil.copyfile(public / f"{operation}.log", state / f"{operation}.log")
            if (public / "exit.txt").read_text().strip() != "0":
                raise RuntimeError(f"Kernel {operation} failed; see {state / (operation + '.log')}")
            value = json.loads((public / "result.json").read_text())
            (state / f"{operation}.json").write_text(json.dumps(value, indent=2) + "\n")
            return value

        before = invoke("inspect")
        if args.operation == "inspect":
            report.update(before)
        else:
            # Status is a precondition; the service's owner.lock closes the start/upgrade race.
            require_stopped(status(), rollback=args.operation == "rollback")
            source = args.commit if args.operation == "install" else args.backup
            changed = invoke(args.operation, before["image_sha256"], candidate, source)
            for key in ("disk_inode", "disk_device", "disk_bytes"):
                if before[key] != changed[key]:
                    raise RuntimeError("Disk identity changed unexpectedly")
            report.update(changed)
            report["boot_verified"] = False
        report["status"] = "passed"
        success = True
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        (state / "result.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
        if success:
            shell(f"rm -rf -- {stage}")
            shutil.rmtree(public)


if __name__ == "__main__":
    main()
