import unittest
from unittest.mock import patch
from kernel_upgrade import main, require_stopped, verify_artifact
from pathlib import Path
import contextlib
import io
import json
import shlex
import tempfile
from types import SimpleNamespace


class Guards(unittest.TestCase):
    def test_only_clean_stopped_guest(self):
        valid = {"status": "stopped", "running": False, "clean_shutdown": True, "active_sessions": 0}
        require_stopped(valid)
        for key, value in (("status", "error"), ("running", True), ("clean_shutdown", False),
                           ("active_sessions", 1), ("running", None)):
            with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                require_stopped(dict(valid, **{key: value}))
        with self.assertRaises(RuntimeError):
            require_stopped({})

    def test_pin_required_before_artifact_access(self):
        for value in ("dev", "e909c7e", "../bad", ""):
            with self.assertRaises(ValueError):
                verify_artifact(Path("/not-accessed"), value)

    def test_rollback_recovers_failed_boot_but_never_live_guest(self):
        failed = {"status": "error", "running": False, "clean_shutdown": False, "active_sessions": 0}
        require_stopped(failed, rollback=True)
        with self.assertRaises(RuntimeError):
            require_stopped(failed)
        for key, value in (("running", True), ("active_sessions", 1), ("status", "starting")):
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                require_stopped(dict(failed, **{key: value}), rollback=True)

    def test_boot_mode_recovers_stopped_unclean_guest_only(self):
        failed = {"status": "error", "running": False, "clean_shutdown": False, "active_sessions": 0}
        require_stopped(failed, recovery=True)
        require_stopped(dict(failed, status="stopped"), recovery=True)
        for key, value in (("running", True), ("running", None), ("active_sessions", 1),
                           ("active_sessions", None), ("status", "starting"), ("status", "stopping")):
            with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                require_stopped(dict(failed, **{key: value}), recovery=True)
        with self.assertRaises(RuntimeError):
            require_stopped({}, recovery=True)

    def test_boot_mode_cli_rejects_unknown_mode_before_staging(self):
        with patch("kernel_upgrade.shell") as shell, contextlib.redirect_stderr(io.StringIO()):
            for bad in ("init=/bin/sh", "LEGACY", "", "legacy quiet"):
                with self.subTest(mode=bad), self.assertRaises(SystemExit):
                    main(["--helper", "/not-accessed.jar", "boot-mode", "--mode", bad])
            shell.assert_not_called()

    def test_boot_mode_cli_rejects_live_guest_before_staging(self):
        live = {"status": "running", "running": True, "clean_shutdown": False, "active_sessions": 0}
        with tempfile.NamedTemporaryFile(suffix=".jar") as helper, \
                patch("kernel_upgrade.status", return_value=live), \
                patch("kernel_upgrade.shell") as shell:
            for mode in ("systemd", "legacy"):
                with self.subTest(mode=mode), self.assertRaises(RuntimeError):
                    main(["--helper", helper.name, "boot-mode", "--mode", mode])
            shell.assert_not_called()

    def test_boot_mode_cli_does_not_inspect_kernel_or_require_ssh(self):
        failed = {"status": "error", "running": False, "clean_shutdown": False, "active_sessions": 0}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / "helper.jar"
            helper.write_bytes(b"trusted test helper")
            download = root / "Download"
            download.mkdir()
            token = "a" * 32
            public = download / ("aether-kernel-" + token)
            invocations = []

            def shell(command):
                if "app_process /system/bin KernelUpgrade " not in command:
                    return
                arguments = shlex.split(command)
                offset = arguments.index("KernelUpgrade")
                invocation = arguments[offset + 1:offset + 6]
                invocations.append(invocation)
                self.assertEqual("boot-mode", invocation[0])
                self.assertEqual(["-", "-", "legacy"], invocation[2:])
                (public / "exit.txt").write_text("0\n")
                (public / "boot-mode.log").write_text("")
                (public / "result.json").write_text(json.dumps({"operation": "boot-mode",
                    "before_mode": "systemd", "boot_mode": "legacy",
                    "init_path": "/usr/local/sbin/termux-vm-legacy-init"}))

            def path(value):
                return download if str(value) == "/storage/emulated/0/Download" else Path(value)

            with patch("kernel_upgrade.Path", side_effect=path) as paths, \
                    patch("kernel_upgrade.uuid.uuid4", return_value=SimpleNamespace(hex=token)), \
                    patch("kernel_upgrade.status", return_value=failed) as status, \
                    patch("kernel_upgrade.shell", side_effect=shell), \
                    contextlib.redirect_stdout(io.StringIO()):
                paths.home.return_value = root
                main(["--helper", str(helper), "boot-mode", "--mode", "legacy"])
            self.assertEqual(2, status.call_count)
            self.assertEqual(1, len(invocations))
            result = json.loads((root / ".local/state/termux-aether" / ("kernel-" + token)
                                 / "result.json").read_text())
            self.assertEqual("passed", result["status"])
            self.assertEqual("legacy", result["boot_mode"])
            self.assertFalse(result["boot_verified"])


if __name__ == "__main__":
    unittest.main()
