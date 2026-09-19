#!/usr/bin/env python3
"""CI-only SSH and persistence test under QEMU; the Pixel uses AVF, never QEMU."""
import os
from pathlib import Path
import re
import subprocess
import sys
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
    with (out / 'ci-console.txt').open('wb') as log:
        for iteration in range(2):
            process = subprocess.Popen([
                'qemu-system-aarch64', '-machine', 'virt', '-cpu', 'max', '-m', '1024',
                '-nodefaults', '-no-reboot', '-kernel', str(out / 'Image'),
                '-append', 'console=hvc0 root=/dev/vda ro rootwait init=/usr/local/sbin/termux-vm-init panic=-1 termux_ci=1',
                '-drive', f'file={disk},format=raw,if=none,id=root', '-device', 'virtio-blk-device,drive=root',
                '-drive', f'file={seed},format=raw,if=none,id=seed,readonly=on', '-device', 'virtio-blk-device,drive=seed',
                '-netdev', 'user,id=net,hostfwd=tcp:127.0.0.1:22222-:22', '-device', 'virtio-net-device,netdev=net',
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
                process.stdin.write(b'poweroff\n')
                process.stdin.flush()
                assert process.wait(timeout=20) == 0
                text = (out / 'ci-console.txt').read_text(errors='replace')
                assert text.count('TERMUX_ARCH_STOPPING_V2') == iteration + 1
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
    print((out / 'ci-console.txt').read_text(errors='replace'))
    print('SSH output, stdin, exit status, PTY, persistent files, host key and clean shutdown passed')


if __name__ == '__main__':
    main()
