#!/usr/bin/env python3
"""CI-only SSH and persistence test under QEMU; the Pixel uses AVF, never QEMU."""
import os
import functools
import http.server
import io
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import threading
import time


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Run guest builds and boot tests in CI.')
    out, work = map(Path, sys.argv[1:])
    disk = work / 'test.img'
    subprocess.run(['cp', '--sparse=always', str(out / 'arch-rootfs.img'), str(disk)], check=True)
    identity = work / 'identity'
    subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(identity)], check=True)
    seed = work / 'seed.bin'
    seed.write_bytes((' '.join(identity.with_suffix('.pub').read_text().split()[:2]) + '\n').encode().ljust(4096, b'\0'))
    known = work / 'known_hosts'
    ssh = ['ssh', '-F', '/dev/null', '-i', str(identity), '-p', '22222',
           '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'IdentityAgent=none',
           '-o', 'StrictHostKeyChecking=yes', '-o', f'UserKnownHostsFile={known}',
           '-o', 'GlobalKnownHostsFile=/dev/null', '-o', 'ConnectTimeout=5', 'root@127.0.0.1']
    expected_key = None
    # Deterministic repository fixture: test Pacman's real alpm download sandbox
    # without relying on an external mirror or changing the guest's package DB.
    repository = work / 'repository'
    repository.mkdir()
    with tarfile.open(repository / 'sandbox.db', 'w:gz') as archive:
        description = b'%NAME%\nsandbox-fixture\n\n%VERSION%\n1-1\n\n%ARCH%\naarch64\n\n%DESC%\nCI sandbox fixture\n'
        entry = tarfile.TarInfo('sandbox-fixture-1-1/desc')
        entry.size = len(description)
        archive.addfile(entry, io.BytesIO(description))
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0),
        functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(repository)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    with (out / 'ci-console.txt').open('wb') as log:
        for iteration in range(2):
            process = subprocess.Popen([
                'qemu-system-aarch64', '-machine', 'virt', '-cpu', 'max', '-m', '1024',
                '-nodefaults', '-no-reboot', '-kernel', str(out / 'Image'),
                '-append', f'console=hvc0 root=/dev/vda ro rootwait init=/usr/local/sbin/termux-vm-init panic=-1 termux_ci=1 termux_epoch={int(time.time())}',
                '-drive', f'file={disk},format=raw,if=none,id=root', '-device', 'virtio-blk-pci,drive=root',
                '-drive', f'file={seed},format=raw,if=none,id=seed,readonly=on', '-device', 'virtio-blk-pci,drive=seed',
                '-netdev', 'user,id=net,hostfwd=tcp:127.0.0.1:22222-:2222', '-device', 'virtio-net-pci,netdev=net',
                '-object', 'rng-random,filename=/dev/urandom,id=rng', '-device', 'virtio-rng-pci,rng=rng',
                '-device', 'virtio-serial-device', '-chardev', 'stdio,id=console,signal=off',
                '-device', 'virtconsole,chardev=console', '-display', 'none'],
                stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
            try:
                for _ in range(120):
                    text = (out / 'ci-console.txt').read_text(errors='replace')
                    if text.count('TERMUX_ARCH_READY_V2') > iteration:
                        break
                    if process.poll() is not None:
                        raise RuntimeError('Guest exited before readiness')
                    time.sleep(1)
                else:
                    raise TimeoutError('Guest did not become ready')
                keys = re.findall(r'^TERMUX_ARCH_HOST_KEY_V2 (ssh-ed25519 [A-Za-z0-9+/]{68})\r?$', text, re.M)
                key = keys[-1]
                if expected_key is not None:
                    assert key == expected_key, 'Guest SSH identity must survive reboot'
                expected_key = key
                known.write_text(f'[127.0.0.1]:22222 {key}\n')
                # DHCP runs asynchronously so local SSH readiness never needs internet.
                for _ in range(30):
                    connected = subprocess.run(ssh + ['true'], capture_output=True, timeout=10)
                    if connected.returncode == 0:
                        break
                    time.sleep(1)
                else:
                    raise TimeoutError(f'DHCP/SSH not ready: {connected}')
                result = subprocess.run(ssh + ['date +%s'], capture_output=True, timeout=20)
                assert result.returncode == 0 and abs(int(result.stdout) - time.time()) < 30, result
                result = subprocess.run(ssh + ['termux-landlock-check'], capture_output=True, timeout=20)
                assert result.returncode == 0, result
                print(result.stdout.decode().strip())
                config = ('[options]\nArchitecture = aarch64\nDownloadUser = alpm\nSigLevel = Never\n'
                          f'[sandbox]\nServer = http://10.0.2.2:{server.server_port}\n')
                command = ("set -eu; ip -4 route show default | grep -q '^default '; "
                           "grep -q '^nameserver ' /etc/resolv.conf; "
                           "install -d -m 755 /tmp/pacman-sandbox-test; "
                           "cat > /tmp/pacman-sandbox-test/pacman.conf; "
                           "pacman --config /tmp/pacman-sandbox-test/pacman.conf "
                           "--dbpath /tmp/pacman-sandbox-test -Syy --noconfirm; "
                           "pacman --config /tmp/pacman-sandbox-test/pacman.conf "
                           "--dbpath /tmp/pacman-sandbox-test -Sl | grep 'sandbox-fixture 1-1'")
                result = subprocess.run(ssh + [command], input=config.encode(), capture_output=True, timeout=45)
                assert result.returncode == 0, result
                assert b'sandbox-fixture' in result.stdout, result
                if iteration == 0:
                    result = subprocess.run(ssh + ["printf 'persisted\\n' > /root/ci-persistence; printf 'out'; printf 'err' >&2; exit 37"],
                                            capture_output=True, timeout=20)
                    assert (result.returncode, result.stdout, result.stderr) == (37, b'out', b'err'), result
                    result = subprocess.run(ssh + ['cat'], input=b'input\x00and binary\n', capture_output=True, timeout=20)
                    assert result.returncode == 0 and result.stdout == b'input\x00and binary\n', result
                else:
                    result = subprocess.run(ssh + ['cat /root/ci-persistence'], capture_output=True, timeout=20)
                    assert result.returncode == 0 and result.stdout == b'persisted\n', result
                result = subprocess.run(ssh[:-1] + ['-tt', ssh[-1], 'test -t 0 && stty size'],
                                        input=b'', capture_output=True, timeout=20)
                assert result.returncode == 0, result
                if iteration == 0:
                    # A package/script update can unlink files still held by PID 1.
                    # Shutdown must release them before remount-read-only.
                    result = subprocess.run(ssh + [
                        'set -eu; cp /usr/local/sbin/termux-vm-init /usr/local/sbin/termux-vm-init.new; '
                        'mv /usr/local/sbin/termux-vm-init.new /usr/local/sbin/termux-vm-init; '
                        'cp /usr/bin/bash /usr/bin/bash.new; mv /usr/bin/bash.new /usr/bin/bash'],
                        capture_output=True, timeout=20)
                    assert result.returncode == 0, result
                process.stdin.write(b'poweroff\n')
                process.stdin.flush()
                assert process.wait(timeout=20) == 0
                text = (out / 'ci-console.txt').read_text(errors='replace')
                assert text.count('TERMUX_ARCH_STOPPING_V2') == iteration + 1
                subprocess.run(['e2fsck', '-fn', str(disk)], check=True, timeout=30)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
    print((out / 'ci-console.txt').read_text(errors='replace'))
    server.shutdown()
    server.server_close()
    print('Landlock enforcement, DHCP, sandboxed Pacman download, SSH, persistence and clean shutdown passed')


if __name__ == '__main__':
    main()
