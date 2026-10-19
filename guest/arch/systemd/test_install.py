import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parent
ARCH = SOURCE.parent
RUNTIME_FILES = {
    'termux-vm-init': Path('usr/local/sbin/termux-vm-init'),
    'termux-vm-legacy-init': Path('usr/local/sbin/termux-vm-legacy-init'),
    'termux-vm-systemd': Path('usr/local/sbin/termux-vm-systemd'),
    'termux-vm-shutdown': Path('usr/local/sbin/termux-vm-shutdown'),
    'termux-vm-network': Path('usr/local/sbin/termux-vm-network'),
    'termux-vm-shared-storage': Path('usr/local/sbin/termux-vm-shared-storage'),
    'termux-vsock-net': Path('usr/local/sbin/termux-vsock-net'),
    'termux-vsock-ssh': Path('usr/local/sbin/termux-vsock-ssh'),
    'termux-dhcp-hook': Path('usr/local/libexec/termux-dhcp-hook'),
    'termux-landlock-check': Path('usr/local/bin/termux-landlock-check'),
}
SYSTEMD_PAYLOAD_FILES = ('systemd/termux-vm-check', 'systemd/journald.conf')
UNIT_PAYLOAD_FILES = tuple(
    f'systemd/units/{path.name}' for path in sorted((SOURCE / 'units').iterdir()) if path.is_file()
)
REQUIRED_PAYLOAD_FILES = tuple(RUNTIME_FILES) + SYSTEMD_PAYLOAD_FILES + UNIT_PAYLOAD_FILES
OLD_INIT = 'legacy boot remains selected\n'
TERMUX_RESOLVER = '/run/termux-network/resolv.conf'
DISTRO_RESOLVERS = (
    '/run/systemd/resolve/resolv.conf',
    '../run/systemd/resolve/resolv.conf',
    '/run/systemd/resolve/stub-resolv.conf',
    '../run/systemd/resolve/stub-resolv.conf',
)


def sentinel(source):
    return f'payload:{source}\n'


class InstallTest(unittest.TestCase):
    def make_fixture(self, work):
        root, payload = work / 'root', work / 'payload'
        (root / 'usr/lib/systemd').mkdir(parents=True)
        (root / 'usr/local/sbin').mkdir(parents=True)
        systemd = root / 'usr/lib/systemd/systemd'
        systemd.write_text('test systemd executable\n')
        systemd.chmod(0o755)
        init = root / RUNTIME_FILES['termux-vm-init']
        init.write_text(OLD_INIT)

        (payload / 'systemd').mkdir(parents=True)
        shutil.copytree(SOURCE / 'units', payload / 'systemd/units')
        shutil.copy2(SOURCE / 'journald.conf', payload / 'systemd/journald.conf')
        (payload / 'systemd/termux-vm-check').write_text('payload:systemd/termux-vm-check\n')
        for source in RUNTIME_FILES:
            (payload / source).write_text(sentinel(source))
        return root, payload

    def run_installer(self, root, payload, commands=None, installer=None):
        environment = dict(os.environ)
        if commands is not None:
            environment['PATH'] = str(commands) + ':' + environment['PATH']
        return subprocess.run(
            ['bash', str(installer or SOURCE / 'install.sh'), str(root), str(payload)],
            env=environment, capture_output=True, text=True,
        )

    def assert_runtime_payload_installed(self, root):
        for source, destination in RUNTIME_FILES.items():
            with self.subTest(source=source):
                installed = root / destination
                self.assertTrue(installed.is_file(), installed)
                self.assertEqual(sentinel(source), installed.read_text())
                self.assertEqual(0o755, installed.stat().st_mode & 0o777)

    def assert_no_write_before_preflight(self, root):
        self.assertEqual(OLD_INIT, (root / RUNTIME_FILES['termux-vm-init']).read_text())
        for source, destination in RUNTIME_FILES.items():
            if source != 'termux-vm-init':
                self.assertFalse((root / destination).exists(), destination)
        self.assertFalse((root / 'etc').exists())

    def test_clean_migration_installs_every_runtime_dependency(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            root, payload = self.make_fixture(Path(temporary))
            result = self.run_installer(root, payload)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assert_runtime_payload_installed(root)

            dependency_sources = [ARCH / 'legacy-init', ARCH / 'network', SOURCE / 'guest-service']
            dependency_sources.extend(sorted((SOURCE / 'units').iterdir()))
            dependencies = set(re.findall(
                r'/usr/local/(?:sbin|bin|libexec)/[A-Za-z0-9._-]+',
                '\n'.join(path.read_text() for path in dependency_sources if path.is_file()),
            ))
            installed = {'/' + str(destination) for destination in RUNTIME_FILES.values()}
            self.assertLessEqual(dependencies, installed)
            for dependency in dependencies:
                self.assertTrue((root / dependency.removeprefix('/')).is_file(), dependency)
            self.assertEqual(
                sentinel('termux-dhcp-hook'),
                (root / RUNTIME_FILES['termux-dhcp-hook']).read_text(),
            )
            self.assertFalse(os.path.lexists(root / 'etc/resolv.conf'))

    def test_distro_resolver_links_migrate_before_init_activation(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            work = Path(temporary)
            for index, target in enumerate(DISTRO_RESOLVERS):
                with self.subTest(target=target):
                    fixture = work / str(index)
                    root, payload = self.make_fixture(fixture)
                    resolver = root / 'etc/resolv.conf'
                    resolver.parent.mkdir()
                    resolver.symlink_to(target)
                    commands = fixture / 'commands'
                    commands.mkdir()
                    mv = commands / 'mv'
                    # Observe the real resolver at the instant init is activated.
                    mv.write_text('#!' + shutil.which('bash') + '\n'
                        'case "${@: -1}" in\n'
                        '  */usr/local/sbin/termux-vm-init)\n'
                        '    [[ $(readlink -- ' + shlex.quote(str(resolver)) + ') == '
                        + shlex.quote(TERMUX_RESOLVER) + ' ]] || exit 74 ;;\n'
                        'esac\n'
                        'exec ' + shlex.quote(shutil.which('mv')) + ' "$@"\n')
                    mv.chmod(0o755)

                    result = self.run_installer(root, payload, commands)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(TERMUX_RESOLVER, os.readlink(resolver))
                    self.assert_runtime_payload_installed(root)
                    self.assertEqual('/dev/null', os.readlink(
                        root / 'etc/systemd/system/systemd-resolved.service'))
                    self.assertFalse(list(resolver.parent.glob('.termux-resolv.conf.*')))

    def test_backup_preserves_dangling_resolver_symlink_and_relative_member_path(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            work = Path(temporary)
            root, payload = self.make_fixture(work)
            resolver = root / 'etc/resolv.conf'
            resolver.parent.mkdir()
            target = '../run/systemd/resolve/resolv.conf'
            resolver.symlink_to(target)
            self.assertFalse(resolver.exists())
            # Exercise the actual backup body inside this fixture. Production
            # keeps backups only for live-root updates, not new image builds.
            source = (SOURCE / 'install.sh').read_text()
            guard = 'if [[ -z $root ]]; then'
            self.assertEqual(1, source.count(guard))
            installer = work / 'install-with-backup.sh'
            installer.write_text(source.replace(guard, 'if true; then', 1))

            result = self.run_installer(root, payload, installer=installer)
            self.assertEqual(0, result.returncode, result.stderr)
            backup = Path(next(line.removeprefix('SYSTEMD_BACKUP=')
                               for line in result.stdout.splitlines()
                               if line.startswith('SYSTEMD_BACKUP=')))
            self.assertEqual(root / 'var/lib/termux-vm/systemd-backups', backup.parent)
            self.assertIn('etc/resolv.conf', (backup / 'paths').read_text().splitlines())
            self.assertIn('etc/resolv.conf', (backup / 'existing').read_text().splitlines())
            with tarfile.open(backup / 'before.tar') as archive:
                member = archive.getmember('etc/resolv.conf')
                self.assertTrue(member.issym())
                self.assertEqual(target, member.linkname)
                self.assertTrue(all(not name.startswith('/') for name in archive.getnames()))
                self.assertEqual(OLD_INIT.encode(), archive.extractfile(
                    str(RUNTIME_FILES['termux-vm-init'])).read())
            self.assertEqual(TERMUX_RESOLVER, os.readlink(resolver))

    def test_custom_resolver_files_and_links_are_preserved(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            work = Path(temporary)
            targets = (None, '/run/custom/resolv.conf', 'resolv.local', TERMUX_RESOLVER,
                       '/opt/run/systemd/resolve/resolv.conf')
            for index, target in enumerate(targets):
                with self.subTest(target=target):
                    root, payload = self.make_fixture(work / str(index))
                    resolver = root / 'etc/resolv.conf'
                    resolver.parent.mkdir()
                    if target is None:
                        resolver.write_text('nameserver 192.0.2.53\n')
                    else:
                        resolver.symlink_to(target)
                    original_inode = resolver.lstat().st_ino

                    result = self.run_installer(root, payload)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(original_inode, resolver.lstat().st_ino)
                    if target is None:
                        self.assertFalse(resolver.is_symlink())
                        self.assertEqual('nameserver 192.0.2.53\n', resolver.read_text())
                    else:
                        self.assertEqual(target, os.readlink(resolver))

    def test_custom_dns_change_during_staging_is_preserved(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            work = Path(temporary)
            for kind in ('file', 'symlink'):
                with self.subTest(kind=kind):
                    fixture = work / kind
                    root, payload = self.make_fixture(fixture)
                    resolver = root / 'etc/resolv.conf'
                    resolver.parent.mkdir()
                    resolver.symlink_to(DISTRO_RESOLVERS[0])
                    commands = fixture / 'commands'
                    commands.mkdir()
                    ln = commands / 'ln'
                    resolver_arg = shlex.quote(str(resolver))
                    change = ('printf "nameserver 192.0.2.53\\n" > ' + resolver_arg
                              if kind == 'file' else
                              shlex.quote(shutil.which('ln')) + ' -s resolv.custom ' + resolver_arg)
                    ln.write_text('#!' + shutil.which('bash') + '\n'
                        'case "${@: -1}" in\n'
                        '  */.termux-resolv.conf.*)\n'
                        '    rm -f -- ' + resolver_arg + '\n'
                        '    ' + change + ' ;;\n'
                        'esac\n'
                        'exec ' + shlex.quote(shutil.which('ln')) + ' "$@"\n')
                    ln.chmod(0o755)

                    result = self.run_installer(root, payload, commands)
                    self.assertEqual(0, result.returncode, result.stderr)
                    if kind == 'file':
                        self.assertFalse(resolver.is_symlink())
                        self.assertEqual('nameserver 192.0.2.53\n', resolver.read_text())
                    else:
                        self.assertEqual('resolv.custom', os.readlink(resolver))
                    self.assertFalse(list(resolver.parent.glob('.termux-resolv.conf.*')))

    def test_missing_payload_leaves_existing_dns_unchanged(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            root, payload = self.make_fixture(Path(temporary))
            resolver = root / 'etc/resolv.conf'
            resolver.parent.mkdir()
            resolver.symlink_to(DISTRO_RESOLVERS[0])
            (payload / 'termux-dhcp-hook').unlink()

            result = self.run_installer(root, payload)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(DISTRO_RESOLVERS[0], os.readlink(resolver))
            self.assertEqual(OLD_INIT, (root / RUNTIME_FILES['termux-vm-init']).read_text())
            self.assertFalse((root / 'etc/systemd').exists())
            self.assertFalse((root / 'var').exists())

    def test_every_required_payload_is_preflighted_before_writes(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            work = Path(temporary)
            for index, missing in enumerate(REQUIRED_PAYLOAD_FILES):
                with self.subTest(missing=missing):
                    root, payload = self.make_fixture(work / str(index))
                    (payload / missing).unlink()
                    result = self.run_installer(root, payload)
                    self.assertNotEqual(0, result.returncode)
                    self.assert_no_write_before_preflight(root)

    def test_symlinked_payload_is_rejected_before_writes(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            root, payload = self.make_fixture(Path(temporary))
            network = payload / 'termux-vm-network'
            network.unlink()
            network.symlink_to('termux-vm-init')
            result = self.run_installer(root, payload)
            self.assertNotEqual(0, result.returncode)
            self.assert_no_write_before_preflight(root)

    def test_failure_before_activation_keeps_legacy_entrypoint_and_retry_succeeds(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            work = Path(temporary)
            root, payload = self.make_fixture(work)
            commands = work / 'commands'
            commands.mkdir()
            # Simulate a failed unit install after all runtime helpers have been copied.
            install = commands / 'install'
            install.write_text('#!' + shutil.which('bash') + '\n'
                'for argument; do case "$argument" in */units/*) exit 73 ;; esac; done\n'
                'exec "' + shutil.which('install') + '" "$@"\n')
            install.chmod(0o755)
            result = self.run_installer(root, payload, commands)
            self.assertEqual(73, result.returncode, result.stderr)
            self.assertEqual(OLD_INIT, (root / RUNTIME_FILES['termux-vm-init']).read_text())
            self.assertEqual(
                sentinel('termux-dhcp-hook'),
                (root / RUNTIME_FILES['termux-dhcp-hook']).read_text(),
            )

            result = self.run_installer(root, payload)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assert_runtime_payload_installed(root)
            self.assertEqual(
                '../termux-vm.target',
                os.readlink(root / 'etc/systemd/system/multi-user.target.wants/termux-vm.target'),
            )
            self.assertEqual('/dev/null', os.readlink(root / 'etc/systemd/system/sshd.service'))


if __name__ == '__main__':
    unittest.main()
