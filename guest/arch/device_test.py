#!/usr/bin/env python3
"""Run from a real Pixel Termux session after APK/CLI installation; no builds.

Waits for image-ready in the report directory so ADB can finish staging.
Leaves the second boot running for an externally controlled owner-death check.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

folder = Path(sys.argv[1]).resolve()
folder.mkdir(exist_ok=True)
report = {"uid": os.getuid(), "pid": os.getpid(), "checks": [], "phase": "starting"}
try:
    report["selinux"] = Path("/proc/self/attr/current").read_text().strip("\0\n")
except OSError:
    report["selinux"] = None


def save(phase):
    report["phase"] = phase
    temp = folder / "report.tmp"
    temp.write_text(json.dumps(report, indent=2) + "\n")
    temp.replace(folder / "report.json")


def invoke(operation):
    before = time.monotonic()
    result = subprocess.run(["termux-arch-vm", "--" + operation], capture_output=True, text=True, timeout=25)
    if result.returncode:
        raise RuntimeError(f"{operation}: CLI exit {result.returncode}: {result.stderr[:200]}")
    value = json.loads(result.stdout)
    report["checks"].append({"operation": operation, "elapsed_ms": round((time.monotonic()-before)*1000, 1), "result": value})
    save(report["phase"])
    return value


def ready():
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        value = invoke("status")
        if value.get("status") == "ready":
            assert value["guest_boot"] == "verified" and value["running"] is True
            assert value["backend"] == "android_avf" and value["service_uid"] == 2000
            return value
        if value.get("status") not in ("starting", "booting"):
            raise RuntimeError(f"Guest did not boot: {value}")
        time.sleep(1)
    raise TimeoutError("Guest readiness deadline")


try:
    # Shizuku binder delivery can lag an APK update; retry only that transient.
    for attempt in range(20):
        value = invoke("status")
        if value.get("reason") != "binder_not_connected":
            break
        time.sleep(1)
    assert value["status"] == "stopped", value
    save("waiting_for_image")
    deadline = time.monotonic() + 2400
    while not (folder / "image-ready").exists():
        if time.monotonic() >= deadline:
            raise TimeoutError("CI image staging deadline")
        time.sleep(2)
    save("booting")
    invoke("start")
    first = ready()
    repeated = invoke("start")
    assert repeated["status"] == "ready" and repeated["ready_after_ms"] == first["ready_after_ms"]
    stopped = invoke("stop")
    assert stopped["running"] is False, stopped
    assert "TERMUX_ARCH_STOPPING_V1" in stopped["console_tail"], stopped
    assert stopped["exit_code"] == 0, stopped
    save("restarting")
    invoke("start")
    ready()
    save("waiting_for_owner_death_test")
    deadline = time.monotonic() + 180
    while not (folder / "owner-killed").exists():
        if time.monotonic() >= deadline:
            raise TimeoutError("Owner-death check deadline")
        time.sleep(1)
    value = invoke("status")
    assert value["status"] == "stopped" and value["running"] is False, value
    # A fresh boot also proves the dead owner did not leave a held native lock.
    invoke("start")
    ready()
    stopped = invoke("stop")
    assert stopped["running"] is False and stopped["exit_code"] == 0, stopped
    save("passed")
except Exception as error:
    report["error"] = str(error)
    try:
        invoke("stop")
    except Exception:
        pass
    save("failed")
    raise
