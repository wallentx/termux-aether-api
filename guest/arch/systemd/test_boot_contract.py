#!/usr/bin/env python3
"""Static contracts for the systemd guest boot graph.

These checks deliberately avoid booting a guest.  QEMU coverage lives in
``../boot_test.py``; this file catches accidental dependency changes before an
expensive image build and makes the AVF boot protocol reviewable.
"""
from __future__ import annotations

from pathlib import Path
import re
import unittest


ARCH = Path(__file__).resolve().parents[1]
SYSTEMD = ARCH / "systemd"
UNITS = SYSTEMD / "units"

REQUIRED_UNITS = {
    "termux-vm-bootstrap.service",
    "termux-vm-sshd.service",
    "termux-vm-vsock-ssh.service",
    "termux-vm-network.service",
    "termux-vm-shared-storage.service",
    "termux-vm-ready.service",
    "termux-vm-console.service",
    "termux-vm.target",
}

STOCK_UNITS_TO_MASK = {
    "sshd.service",
    "dhcpcd.service",
    "systemd-networkd.service",
    "systemd-networkd-wait-online.service",
    "systemd-networkd.socket",
    "systemd-networkd-varlink.socket",
    "systemd-networkd-varlink-metrics.socket",
    "systemd-networkd-resolve-hook.socket",
    "systemd-resolved.service",
    "systemd-resolved-varlink.socket",
    "systemd-resolved-monitor.socket",
    "systemd-timesyncd.service",
    "serial-getty@hvc0.service",
    "console-getty.service",
}


def read_unit(name: str) -> str:
    return (UNITS / name).read_text()


def required_or_wanted_units(name: str) -> set[str]:
    dependencies: set[str] = set()
    for line in read_unit(name).splitlines():
        match = re.fullmatch(r"(?:Wants|Requires)=(.*)", line)
        if match:
            dependencies.update(match.group(1).split())
    return dependencies


class SystemdBootContractTest(unittest.TestCase):
    def test_boot_inventory_is_complete(self) -> None:
        self.assertTrue(SYSTEMD.is_dir())
        self.assertTrue(UNITS.is_dir())
        self.assertEqual(REQUIRED_UNITS, {path.name for path in UNITS.iterdir() if path.is_file()})
        self.assertTrue((SYSTEMD / "guest-service").is_file())
        self.assertTrue((SYSTEMD / "install.sh").is_file())

    def test_thin_init_hands_off_to_systemd_and_legacy_init_is_retained(self) -> None:
        init = (ARCH / "init").read_text()
        self.assertRegex(init, r"(?m)^exec /usr/lib/systemd/systemd(?:\s|$)")
        self.assertNotIn("TERMUX_ARCH_READY_V2", init)
        self.assertNotIn("while true", init)

        legacy = (ARCH / "legacy-init").read_text()
        self.assertIn("TERMUX_ARCH_READY_V2", legacy)
        self.assertIn("termux-vm-shutdown", legacy)

    def test_guest_specific_units_are_enabled_by_a_single_target(self) -> None:
        target = read_unit("termux-vm.target")
        self.assertIn("[Install]", target)
        self.assertRegex(target, r"(?m)^WantedBy=multi-user\.target$")
        pending = ["termux-vm.target"]
        reachable: set[str] = set()
        while pending:
            current = pending.pop()
            for dependency in required_or_wanted_units(current) - reachable:
                if dependency in REQUIRED_UNITS:
                    reachable.add(dependency)
                    pending.append(dependency)
        self.assertEqual(REQUIRED_UNITS - {"termux-vm.target"}, reachable)

    def test_readiness_does_not_wait_for_network(self) -> None:
        ready = read_unit("termux-vm-ready.service")
        all_units = "\n".join(read_unit(unit) for unit in REQUIRED_UNITS)
        self.assertNotRegex(ready, r"(?m)^(?:After|Wants|Requires)=.*network-online\.target")
        self.assertNotIn("network-online.target", all_units)
        self.assertIn("TERMUX_ARCH_READY_V2", (SYSTEMD / "guest-service").read_text())

    def test_console_is_independent_of_bootstrap_failure(self) -> None:
        console = read_unit("termux-vm-console.service")
        self.assertNotRegex(console, r"(?m)^(?:After|Wants|Requires)=.*termux-vm-bootstrap\.service")
        self.assertIn("console", console)

    def test_production_only_services_and_shared_mount_are_conditional(self) -> None:
        vsock = read_unit("termux-vm-vsock-ssh.service")
        shared = read_unit("termux-vm-shared-storage.service")
        self.assertRegex(vsock, r"(?m)^ConditionKernelCommandLine=!termux_ci=1$")
        self.assertRegex(shared, r"(?m)^ConditionKernelCommandLine=termux_shared_storage=1$")

    def test_installer_installs_helper_units_and_expected_masks(self) -> None:
        installer = (SYSTEMD / "install.sh").read_text()
        self.assertIn("termux-vm-systemd", installer)
        self.assertIn("/usr/lib/systemd/system", installer)
        self.assertIn("termux-vm-legacy-init", installer)
        self.assertIn("multi-user.target.wants/termux-vm.target", installer)
        self.assertIn("ln -sfn ../termux-vm.target", installer)
        for unit in STOCK_UNITS_TO_MASK:
            self.assertRegex(installer, rf"\b{re.escape(unit)}\b")

    def test_installer_activates_the_new_entrypoint_last(self) -> None:
        installer = (SYSTEMD / "install.sh").read_text()
        install_loop = re.search(
            r"for file in (.+?); do\n"
            r"    install -m 755 \"\$payload/\$file\" \"\$root/usr/local/sbin/\$file\"\n"
            r"done",
            installer,
        )
        self.assertIsNotNone(install_loop)
        self.assertNotIn("termux-vm-init", install_loop.group(1))
        activation = installer.index('mv -f -- "$pending" "$root/usr/local/sbin/termux-vm-init"')
        for prerequisite in (
            'install -m 644 "$payload/systemd/units/"* "$root/etc/systemd/system/"',
            'for unit in "${masks[@]}"; do ln -sfn /dev/null',
            'ln -sfn ../termux-vm.target',
        ):
            self.assertLess(installer.index(prerequisite), activation)

    def test_bootstrap_owns_state_writes_before_services_can_use_them(self) -> None:
        bootstrap = read_unit("termux-vm-bootstrap.service")
        sshd = read_unit("termux-vm-sshd.service")
        ready = read_unit("termux-vm-ready.service")
        for dependent in (sshd, ready):
            self.assertRegex(dependent, r"(?m)^After=.*\btermux-vm-bootstrap\.service\b")
        helper = (SYSTEMD / "guest-service").read_text()
        for command in ("bootstrap", "check-sshd", "check-vsock", "shared-mount", "shared-unmount"):
            self.assertRegex(helper, rf"(?m)^\s*{command}\)")
        self.assertIn("bootstrap", bootstrap)


if __name__ == "__main__":
    unittest.main()
