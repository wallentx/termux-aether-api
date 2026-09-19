#!/usr/bin/env python3
"""Run in Pixel Termux. Test guest networking/Pacman without upgrading packages."""
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    report_dir = Path.home() / ('arch-network-test-' + time.strftime('%Y%m%d-%H%M%S'))
    report_dir.mkdir(mode=0o700)
    report = {'uid': os.getuid(), 'checks': [], 'passed': False}
    checks = [
        ('landlock', 'termux-landlock-check'),
        ('address_and_dns', "ip -4 addr show dev eth0; ip -4 route show; cat /etc/resolv.conf; "
         "test -n \"$(ip -4 route show default)\" && getent ahostsv4 archlinuxarm.org"),
        ('https', "curl --noproxy '*' --fail --location --max-time 30 --output /dev/null "
         "--write-out 'HTTPS status=%{http_code} peer=%{remote_ip}\\n' https://archlinuxarm.org/"),
        ('pacman_sandbox', r'''set -eu
testdir=$(mktemp -d /tmp/termux-pacman-check.XXXXXX)
trap 'rm -rf -- "$testdir"' EXIT
chmod 755 "$testdir"
# Keep production repository and sandbox settings, but isolate downloaded DBs.
test "$(pacman-conf DownloadUser)" = alpm
if pacman-conf | grep -Eq '^DisableSandbox(Filesystem|Syscalls)( =.*)?$'; then
    echo 'Pacman sandbox is disabled in the production configuration' >&2
    exit 1
fi
pacman --config /etc/pacman.conf --dbpath "$testdir" --logfile "$testdir/pacman.log" -Sy --noconfirm
test -s "$testdir/sync/core.db"
test -s "$testdir/sync/extra.db"
printf 'Pacman synchronized repositories with the production sandbox settings\n'
'''),
        ('ssh_exposure', "ss -lnt; test -z \"$(ss -H -lnt 'sport = :22' | grep -v '127.0.0.1:22')\""),
    ]
    try:
        clock = subprocess.run(['termux-arch', '--cwd', '/root', '--', 'date', '+%s'],
                               capture_output=True, text=True, timeout=120)
        assert clock.returncode == 0, clock.stderr
        report['clock_skew_seconds'] = round(int(clock.stdout) - time.time(), 1)
        assert abs(report['clock_skew_seconds']) < 30, 'Guest clock differs from Android by 30+ seconds'
        for name, command in checks:
            started = time.monotonic()
            result = subprocess.run(['termux-arch', '--cwd', '/root', '--', 'bash', '-c', command],
                                    capture_output=True, text=True, timeout=180)
            entry = {'name': name, 'exit_code': result.returncode, 'stdout': result.stdout,
                     'stderr': result.stderr, 'elapsed_ms': round((time.monotonic() - started) * 1000)}
            report['checks'].append(entry)
            print(f"{name}: {'PASS' if result.returncode == 0 else 'FAIL'}", flush=True)
            if result.returncode:
                raise RuntimeError(f'{name}: {result.stderr}')
        report['passed'] = True
    finally:
        (report_dir / 'report.json').write_text(json.dumps(report, indent=2))
        print(f'Report: {report_dir / "report.json"}', flush=True)


if __name__ == '__main__':
    main()
