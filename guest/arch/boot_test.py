#!/usr/bin/env python3
"""CI-only SSH and persistence test under QEMU; the Pixel uses AVF, never QEMU."""
import os
import functools
import http.server
import io
from pathlib import Path
import re
import socket
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
    active_ci_units = (
        'termux-vm-bootstrap.service',
        'termux-vm-sshd.service',
        'termux-vm-network.service',
        'termux-vm-ready.service',
        'termux-vm-console.service',
        'termux-vm.target',
    )
    masked_stock_units = (
        'sshd.service',
        'dhcpcd.service',
        'systemd-networkd.service',
        'systemd-networkd-wait-online.service',
        'systemd-networkd.socket',
        'systemd-networkd-varlink.socket',
        'systemd-networkd-varlink-metrics.socket',
        'systemd-networkd-resolve-hook.socket',
        'systemd-resolved.service',
        'systemd-resolved-varlink.socket',
        'systemd-resolved-monitor.socket',
        'systemd-timesyncd.service',
        'serial-getty@hvc0.service',
        'console-getty.service',
    )
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
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(('127.0.0.1', 0))
    def echo_udp():
        while True:
            try:
                packet, peer = udp.recvfrom(65535)
                udp.sendto(packet, peer)
            except OSError:
                return
    threading.Thread(target=echo_udp, daemon=True).start()
    with (out / 'ci-console.txt').open('wb') as log:
        for iteration in range(2):
            network_host = network_guest = None
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
                # systemd needs time to initialize its journal, then bootstrap
                # SSH keys and Pacman trust on a fresh root disk.
                for _ in range(180):
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
                unit_check = 'set -eu; test "$(cat /proc/1/comm)" = systemd; '
                unit_check += ' '.join(
                    f'systemctl is-active --quiet {unit};' for unit in active_ci_units)
                unit_check += ' '.join(
                    f'test "$(systemctl is-enabled {unit} 2>/dev/null || :)" = masked;'
                    for unit in masked_stock_units)
                # CI does not expose vsock or a virtio-fs share. Those units must
                # be skipped, not failed, when their kernel-command-line
                # conditions are absent.
                unit_check += ('systemctl is-failed --quiet termux-vm-vsock-ssh.service && exit 1 || :; '
                               'systemctl is-failed --quiet termux-vm-shared-storage.service && exit 1 || :')
                result = subprocess.run(ssh + [unit_check], capture_output=True, timeout=30)
                assert result.returncode == 0, result
                service_check = b'''set -eu
cat >/etc/systemd/system/termux-ci-service.service <<'UNIT'
[Unit]
Description=CI systemd lifecycle contract

[Service]
Type=simple
ExecStart=/usr/bin/bash -c 'echo TERMUX_ARCH_CI_SERVICE; exec sleep infinity'
UNIT
systemctl daemon-reload
systemctl start termux-ci-service.service
systemctl is-active --quiet termux-ci-service.service
systemctl restart termux-ci-service.service
systemctl status --no-pager termux-ci-service.service >/dev/null
journalctl --no-pager -o cat -b -u termux-ci-service.service | grep -qx TERMUX_ARCH_CI_SERVICE
'''
                result = subprocess.run(ssh + ['bash', '-s'], input=service_check,
                                        capture_output=True, timeout=30)
                assert result.returncode == 0, result
                if iteration == 0:
                    slow_stop_check = b'''set -eu
cat >/etc/systemd/system/termux-ci-slow-stop.service <<'UNIT'
[Unit]
Description=CI systemd shutdown timeout contract

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/bin/true
ExecStop=/usr/bin/sleep 15
TimeoutStopSec=30
UNIT
systemctl daemon-reload
systemctl start termux-ci-slow-stop.service
systemctl is-active --quiet termux-ci-slow-stop.service
'''
                    result = subprocess.run(ssh + ['bash', '-s'], input=slow_stop_check,
                                            capture_output=True, timeout=30)
                    assert result.returncode == 0, result
                # Exercise the same packet protocol and guest TAP as production.
                # SSH replaces AVF vsock only as the CI transport between the two.
                backend = Path(os.environ['RUNNER_TEMP']) / 'arch-network-host-linux'
                network_host = subprocess.Popen([str(backend), '--ci'], stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE, stderr=log)
                network_guest = subprocess.Popen(ssh + ['termux-vsock-net --stdio'],
                    stdin=network_host.stdout, stdout=network_host.stdin, stderr=log)
                network_host.stdout.close()
                network_host.stdin.close()
                result = subprocess.run(ssh + [
                    'for i in $(seq 1 30); do test ! -e /sys/class/net/avf0 || break; sleep .1; done; '
                    'test -e /sys/class/net/avf0 || exit 1; '
                    'nohup termux-vm-network avf0 >/run/bridge-dhcp.log 2>&1 </dev/null & exit 0'],
                    capture_output=True, timeout=15)
                assert result.returncode == 0, result
                for _ in range(30):
                    result = subprocess.run(ssh + [
                        "ip -4 addr show avf0 | grep -q '192.168.127.2/24' && "
                        "ip route get 198.51.100.1 | grep -q 'dev avf0' && "
                        "getent ahostsv4 fixture.test | grep -q '192.168.127.254'"],
                        capture_output=True, timeout=10)
                    if result.returncode == 0:
                        break
                    time.sleep(1)
                else:
                    raise TimeoutError(f'Userspace bridge DHCP/DNS failed: {result}')
                result = subprocess.run(ssh + [
                    f"bash -c 'exec 3<>/dev/udp/192.168.127.254/{udp.getsockname()[1]}; "
                    "printf udp-probe >&3; timeout 5 dd bs=9 count=1 status=none <&3'"],
                    capture_output=True, timeout=15)
                assert result.returncode == 0 and result.stdout == b'udp-probe', result
                result = subprocess.run(ssh + ['date +%s'], capture_output=True, timeout=20)
                assert result.returncode == 0 and abs(int(result.stdout) - time.time()) < 30, result
                result = subprocess.run(ssh + ['termux-landlock-check'], capture_output=True, timeout=20)
                assert result.returncode == 0, result
                print(result.stdout.decode().strip())
                config = ('[options]\nArchitecture = aarch64\nDownloadUser = alpm\nSigLevel = Never\n'
                          f'[sandbox]\nServer = http://fixture.test:{server.server_port}\n')
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
                    # A package update can unlink files still held by PID 1.
                    # The shutdown path must still remount cleanly after replacing
                    # both its manager binary and an ordinary shell binary.
                    result = subprocess.run(ssh + [
                        'set -eu; cp /usr/lib/systemd/systemd /usr/lib/systemd/systemd.new; '
                        'mv /usr/lib/systemd/systemd.new /usr/lib/systemd/systemd; '
                        'cp /usr/bin/bash /usr/bin/bash.new; mv /usr/bin/bash.new /usr/bin/bash'],
                        capture_output=True, timeout=20)
                    assert result.returncode == 0, result
                shutdown_started = time.monotonic()
                process.stdin.write(b'poweroff\n')
                process.stdin.flush()
                assert process.wait(timeout=60) == 0
                shutdown_elapsed = time.monotonic() - shutdown_started
                if iteration == 0:
                    # Android's synchronous stop RPC allows only 12 seconds.
                    # systemd must be allowed to finish an ordinary longer stop.
                    assert 14 <= shutdown_elapsed <= 60, shutdown_elapsed
                text = (out / 'ci-console.txt').read_text(errors='replace')
                assert text.count('TERMUX_ARCH_STOPPING_V2') == iteration + 1
                subprocess.run(['e2fsck', '-fn', str(disk)], check=True, timeout=30)
            finally:
                for network_process in (network_guest, network_host):
                    if network_process is not None:
                        if network_process.poll() is None:
                            network_process.terminate()
                        try:
                            network_process.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            network_process.kill()
                            network_process.wait()
                if process.poll() is None:
                    process.kill()
                    process.wait()
    # Bootstrap must refuse a malformed host key without advertising readiness.
    # The console service is independent so a host can still request shutdown.
    seed.write_bytes(b'not-a-public-key\n'.ljust(4096, b'\0'))
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
        # Let systemd start the console reader, then prove it remains usable even
        # though bootstrap never reached the ready service.
        time.sleep(5)
        process.stdin.write(b'poweroff\n')
        process.stdin.flush()
        assert process.wait(timeout=60) == 0
        text = (out / 'ci-console.txt').read_text(errors='replace')
        assert text.count('TERMUX_ARCH_READY_V2') == 2, text[-4000:]
        assert text.count('TERMUX_ARCH_STOPPING_V2') == 2, text[-4000:]
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
    print((out / 'ci-console.txt').read_text(errors='replace'))
    server.shutdown()
    server.server_close()
    udp.close()
    print('Landlock enforcement, userspace TAP DHCP/DNS/TCP/UDP, sandboxed Pacman download, SSH, persistence and clean shutdown passed')


if __name__ == '__main__':
    main()
