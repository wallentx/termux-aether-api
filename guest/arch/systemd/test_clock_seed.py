"""Exercise clock correction without running privileged bootstrap operations."""
from pathlib import Path
import re
import subprocess
import unittest


class ClockSeedTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        helper = Path(__file__).with_name("guest-service").read_text()
        # Load the actual pure function, avoiding the dispatcher's device writes.
        match = re.search(r"^seeded_epoch\(\) \{\n.*?^\}\n", helper, re.M | re.S)
        assert match is not None
        cls.function = match.group()

    def calculate(self, epoch, uptime):
        return subprocess.run(
            ["bash", "-c", self.function + '\nseeded_epoch "$@"', "--", epoch, uptime],
            text=True, capture_output=True, check=False,
        )

    def test_delayed_boot_keeps_the_launch_epoch_current(self):
        result = self.calculate("1792264294", "76.45")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "1792264370\n")

    def test_early_boot_does_not_add_a_second(self):
        result = self.calculate("1792264294", "0.99")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "1792264294\n")

    def test_decimal_inputs_are_not_interpreted_as_octal(self):
        result = self.calculate("0179226429", "08.25")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "179226437\n")

    def test_invalid_seed_or_uptime_is_rejected(self):
        for epoch, uptime in (("1", "0"), ("1792264294x", "0"),
                              ("1792264294", "-1"), ("1792264294", "1+1"),
                              ("1792264294", "nan"), ("1792264294", "")):
            with self.subTest(epoch=epoch, uptime=uptime):
                result = self.calculate(epoch, uptime)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
