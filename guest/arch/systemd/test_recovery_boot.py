"""Check the negative boot harness with real log files and a controlled process."""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "arch_boot_test", Path(__file__).resolve().parents[1] / "boot_test.py")
boot_test = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boot_test)


class FakeGuest:
    def __init__(self, log):
        self.log = log
        self.stdin = io.BytesIO()
        self.returncode = None
        self.killed = False

    def emit(self, message):
        self.log.write(message)
        self.log.flush()

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        if not self.killed:
            assert self.stdin.getvalue() == b"poweroff\n"
            self.emit(b"TERMUX_ARCH_STOPPING_V2\n")
            self.returncode = 0
        return self.returncode

    def kill(self):
        self.killed = True
        self.returncode = -9


class RecoveryBootTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.out = Path(self.directory.name)
        self.console = self.out / "ci-console.txt"
        self.console.write_text("TERMUX_ARCH_READY_V2\nTERMUX_ARCH_STOPPING_V2\n" * 2)
        self.guest = None

    def start(self, command, *, stdin, stdout, stderr):
        self.assertFalse(stdout.closed)
        self.assertIn("net.ifnames=0", command[command.index("-append") + 1])
        self.guest = FakeGuest(stdout)
        return self.guest

    def test_waits_for_rejection_with_live_log_then_checks_clean_shutdown(self):
        def delayed_rejection(seconds):
            self.assertEqual(self.guest.stdin.getvalue(), b"")
            self.guest.emit(b"Invalid SSH seed\n")

        with patch.object(boot_test.subprocess, "Popen", side_effect=self.start), \
                patch.object(boot_test.subprocess, "run") as fsck, \
                patch.object(boot_test.time, "sleep", side_effect=delayed_rejection) as sleep:
            boot_test.check_malformed_seed_boot(self.out, self.out / "disk", self.out / "seed")
        sleep.assert_called_once_with(1)
        fsck.assert_called_once_with(["e2fsck", "-fn", str(self.out / "disk")], check=True, timeout=30)
        self.assertTrue(self.guest.log.closed)
        self.assertFalse(self.guest.killed)
        self.assertEqual(self.console.read_text().count("TERMUX_ARCH_STOPPING_V2"), 3)

    def test_unexpected_readiness_fails_and_reaps_guest(self):
        def unexpected_readiness(seconds):
            self.guest.emit(b"TERMUX_ARCH_READY_V2\nInvalid SSH seed\n")

        with patch.object(boot_test.subprocess, "Popen", side_effect=self.start), \
                patch.object(boot_test.time, "sleep", side_effect=unexpected_readiness):
            with self.assertRaises(AssertionError):
                boot_test.check_malformed_seed_boot(self.out, self.out / "disk", self.out / "seed")
        self.assertTrue(self.guest.killed)
        self.assertEqual(self.guest.stdin.getvalue(), b"")

    def test_missing_rejection_times_out_before_poweroff(self):
        with patch.object(boot_test.subprocess, "Popen", side_effect=self.start), \
                patch.object(boot_test.time, "sleep") as sleep:
            with self.assertRaisesRegex(TimeoutError, "did not reject"):
                boot_test.check_malformed_seed_boot(self.out, self.out / "disk", self.out / "seed")
        self.assertEqual(sleep.call_count, 180)
        self.assertTrue(self.guest.killed)
        self.assertEqual(self.guest.stdin.getvalue(), b"")


if __name__ == "__main__":
    unittest.main()
