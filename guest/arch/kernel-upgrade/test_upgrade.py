import unittest
from kernel_upgrade import require_stopped, verify_artifact
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
