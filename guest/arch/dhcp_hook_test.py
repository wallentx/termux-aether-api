#!/usr/bin/env python3
"""Exercise the real DHCP hook against an isolated runtime directory."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ResolverFailoverTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='aether-dhcp-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.runtime = self.root / 'network'
        self.runtime.mkdir()
        self.hook = self.root / 'dhcp-hook'
        source = Path(__file__).with_name('dhcp-hook').read_text()
        self.hook.write_text(source.replace('/run/termux-network', str(self.runtime)))
        self.bash = shutil.which('bash')
        self.assertIsNotNone(self.bash)

    def event(self, interface, reason, servers=''):
        env = dict(os.environ, interface=interface, reason=reason,
                   new_domain_name_servers=servers)
        subprocess.run([self.bash, str(self.hook)], env=env, check=True, timeout=10)

    def assertResolver(self, interface, server):
        active = self.runtime / 'resolv.conf'
        self.assertTrue(active.is_symlink())
        self.assertEqual(active.resolve(), self.runtime / f'resolv.{interface}.conf')
        self.assertEqual(active.read_text(),
                         f'# Managed by termux-dhcp-hook\nnameserver {server}\n')

    def test_preferred_lease_loss_falls_back_for_every_loss_event(self):
        for reason in ['EXPIRE', 'FAIL', 'STOP', 'STOPPED', 'NOCARRIER', 'DEPARTED']:
            with self.subTest(reason=reason):
                self.event('eth0', 'BOUND', '192.0.2.1')
                self.event('avf0', 'BOUND', '192.0.2.2')
                self.assertResolver('avf0', '192.0.2.2')
                self.event('avf0', reason)
                self.assertFalse((self.runtime / 'resolv.avf0.conf').exists())
                self.assertResolver('eth0', '192.0.2.1')

    def test_secondary_renewal_and_loss_keep_preferred_resolver(self):
        self.event('avf0', 'BOUND', '192.0.2.2')
        self.event('eth0', 'BOUND', '192.0.2.1')
        self.event('eth0', 'RENEW', '192.0.2.3')
        self.assertResolver('avf0', '192.0.2.2')
        self.event('eth0', 'EXPIRE')
        self.assertResolver('avf0', '192.0.2.2')

    def test_reacquired_preferred_lease_takes_over(self):
        self.event('eth0', 'BOUND', '192.0.2.1')
        self.event('avf0', 'BOUND', '192.0.2.2')
        self.event('avf0', 'NOCARRIER')
        self.assertResolver('eth0', '192.0.2.1')
        self.event('avf0', 'REBIND', '192.0.2.3')
        self.assertResolver('avf0', '192.0.2.3')

    def test_last_lease_loss_removes_dangling_link(self):
        for interface in ['eth0', 'avf0']:
            with self.subTest(interface=interface):
                self.event(interface, 'BOUND', '192.0.2.1')
                self.event(interface, 'STOPPED')
                self.assertFalse(os.path.lexists(self.runtime / 'resolv.conf'))

    def test_invalid_servers_and_unrelated_events_do_not_change_dns(self):
        self.event('eth0', 'BOUND', '999.0.0.1 invalid 192.0.2.1')
        self.assertResolver('eth0', '192.0.2.1')
        self.event('wlan0', 'BOUND', '192.0.2.3')
        self.event('eth0', 'UNKNOWN', '192.0.2.4')
        self.assertResolver('eth0', '192.0.2.1')


if __name__ == '__main__':
    unittest.main()
