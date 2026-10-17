import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parent


class InstallTest(unittest.TestCase):
    def test_failure_before_activation_keeps_legacy_entrypoint_and_retry_succeeds(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            work = Path(temporary)
            root, payload, commands = work / 'root', work / 'payload', work / 'commands'
            (root / 'usr/lib/systemd').mkdir(parents=True)
            (root / 'usr/local/sbin').mkdir(parents=True)
            commands.mkdir()
            systemd = root / 'usr/lib/systemd/systemd'
            systemd.write_text('test systemd executable\n')
            systemd.chmod(0o755)
            init = root / 'usr/local/sbin/termux-vm-init'
            init.write_text('legacy boot remains selected\n')
            (payload / 'systemd').mkdir(parents=True)
            shutil.copytree(SOURCE / 'units', payload / 'systemd/units')
            shutil.copy2(SOURCE / 'journald.conf', payload / 'systemd/journald.conf')
            (payload / 'systemd/termux-vm-check').write_text('test checker\n')
            for name in ('termux-vm-init', 'termux-vm-legacy-init', 'termux-vm-systemd', 'termux-vm-shutdown'):
                (payload / name).write_text(name + '\n')
            # Simulate a failed unit install after the helpers have been copied.
            install = commands / 'install'
            install.write_text('#!' + shutil.which('bash') + '\n'
                'for argument; do case "$argument" in */units/*) exit 73 ;; esac; done\n'
                'exec "' + shutil.which('install') + '" "$@"\n')
            install.chmod(0o755)
            command = ['bash', str(SOURCE / 'install.sh'), str(root), str(payload)]
            result = subprocess.run(command, env=dict(os.environ, PATH=str(commands) + ':' + os.environ['PATH']),
                                    capture_output=True, text=True)
            self.assertEqual(73, result.returncode, result.stderr)
            self.assertEqual('legacy boot remains selected\n', init.read_text())
            self.assertEqual('termux-vm-legacy-init\n', (root / 'usr/local/sbin/termux-vm-legacy-init').read_text())
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('termux-vm-init\n', init.read_text())
            self.assertEqual(0o755, init.stat().st_mode & 0o777)
            self.assertEqual('../termux-vm.target', os.readlink(root / 'etc/systemd/system/multi-user.target.wants/termux-vm.target'))
            self.assertEqual('/dev/null', os.readlink(root / 'etc/systemd/system/sshd.service'))


if __name__ == '__main__':
    unittest.main()
