#!/usr/bin/env python3
"""Run inside real Pixel Termux after staging v2. Does not build or force-stop VMs."""
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import signal
import struct
import subprocess
import termios
import time
import traceback

REPORT = Path.home() / 'arch-workspace-test-20260919'
GUEST = '/root/termux-acceptance-20260919'
report = {'uid': os.getuid(), 'selinux': Path('/proc/self/attr/current').read_text().strip('\0\n'), 'checks': []}


def save(phase):
    report['phase'] = phase
    (REPORT / 'report.json').write_text(json.dumps(report, indent=2))


def run(command, **kwargs):
    start = time.monotonic()
    result = subprocess.run(command, capture_output=True, timeout=90, cwd=Path.home(), **kwargs)
    report['checks'].append({'command': command, 'exit_code': result.returncode,
        'stdout': result.stdout.decode(errors='replace'), 'stderr': result.stderr.decode(errors='replace'),
        'elapsed_ms': round((time.monotonic()-start)*1000, 1)})
    save('running')
    return result


def arch(*args, **kwargs):
    return run(['termux-arch', '--cwd', '/root', '--', *args], **kwargs)


def status():
    result = run(['termux-arch-vm', '--status'])
    assert result.returncode == 0, result
    return json.loads(result.stdout)


def stop():
    result = run(['termux-arch-vm', '--stop'])
    value = json.loads(result.stdout)
    assert value['status'] == 'stopped' and not value['running'] and value['clean_shutdown'], value
    return value


def pty_test():
    child, terminal = pty.fork()
    if child == 0:
        os.chdir(Path.home())
        os.execlp('Æ', 'Æ')
    transcript = bytearray()
    def send(data): os.write(terminal, data)
    def wait_for(needle, start=0, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if needle in transcript[start:]: return
            if select.select([terminal], [], [], 0.25)[0]:
                try: data = os.read(terminal, 65536)
                except OSError: break
                if not data: break
                transcript.extend(data)
        raise AssertionError(f'PTY missing {needle!r}: {bytes(transcript[-1500:])!r}')
    try:
        fcntl.ioctl(terminal, termios.TIOCSWINSZ, struct.pack('HHHH', 31, 93, 0, 0))
        # The command token is deliberately split so echoed input cannot pass the check.
        send(b"printf '\\nPTY_%s\\n' READY\n")
        wait_for(b'PTY_READY\r\n', timeout=70)
        start = len(transcript)
        send(b'stty size\n')
        wait_for(b'31 93\r\n', start)
        fcntl.ioctl(terminal, termios.TIOCSWINSZ, struct.pack('HHHH', 42, 107, 0, 0))
        time.sleep(0.3)
        start = len(transcript)
        send(b'stty size\n')
        wait_for(b'42 107\r\n', start)
        start = len(transcript)
        send(b"printf '\\nSLEEP_%s\\n' START; sleep 30\n")
        wait_for(b'SLEEP_START\r\n', start)
        time.sleep(0.3)
        start = len(transcript)
        send(b'\x03')
        send(b"printf '\\nINTERRUPT_%s\\n' OK\n")
        wait_for(b'INTERRUPT_OK\r\n', start, timeout=5)
        send(b'exit\n')
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            pid, code = os.waitpid(child, os.WNOHANG)
            if pid:
                assert os.waitstatus_to_exitcode(code) == 0, code
                child = None
                break
            time.sleep(0.1)
        else: raise TimeoutError('SSH did not exit')
        report['pty'] = {'interactive_wrapper': True, 'initial_size': [31, 93], 'resize': [42, 107], 'ctrl_c': True}
    finally:
        (REPORT / 'pty-transcript.txt').write_bytes(transcript)
        os.close(terminal)
        if child:
            os.kill(child, signal.SIGTERM)
            os.waitpid(child, 0)


def main():
    REPORT.mkdir(exist_ok=True)
    save('running')
    first = arch('uname', '-a')
    assert first.returncode == 0 and b'termux-avf' in first.stdout, first
    initial = status()
    assert initial['status'] == 'ready' and not initial['root_read_only']
    result = arch('bash', '-c', 'printf out; printf err >&2; exit 37')
    assert (result.returncode, result.stdout, result.stderr) == (37, b'out', b'err'), result
    args = ['', 'two words', "single'quote", '$HOME', '$(touch /root/INJECTION)', 'line\nbreak', '*']
    result = arch('printf', r'%s\0', *args)
    assert result.stdout == b'\0'.join(arg.encode() for arg in args) + b'\0', result
    result = arch('cat', input=b'binary\0input\n'*10000)
    assert result.returncode == 0 and result.stdout == b'binary\0input\n'*10000, result
    # Trim large binary output from the human-sized report after its assertion.
    report['checks'][-1]['stdout'] = '[130000 bytes round-tripped]'
    result = arch('bash', '-c', f'mkdir -p {GUEST}; printf persisted > {GUEST}/marker; sync')
    assert result.returncode == 0, result
    result = run(['termux-arch', '--cwd', GUEST, '--', 'pwd'])
    assert result.returncode == 0 and result.stdout.strip() == GUEST.encode(), result
    result = run(['termux-arch', '--cwd', GUEST+'/missing', '--', 'printf', 'must-not-run'])
    assert result.returncode != 0 and not result.stdout, result
    result = run(['æ', 'printf', 'alias works'])
    assert result.returncode == 0 and result.stdout == b'alias works', result
    pty_test()
    after_sessions = status()
    assert after_sessions['cid'] == initial['cid'], 'Commands must reuse one VM'
    stop()
    result = arch('cat', GUEST+'/marker')
    assert result.returncode == 0 and result.stdout == b'persisted', result
    restarted = status()
    assert restarted['cid'] != initial['cid'] and restarted['ssh_host_key'] == initial['ssh_host_key']
    report['vm'] = {'first_cid': initial['cid'], 'restart_cid': restarted['cid'],
                    'first_ready_ms': initial['ready_after_ms'], 'restart_ready_ms': restarted['ready_after_ms'],
                    'host_key_persisted': True, 'file_persisted': True}
    stop()
    save('passed')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        report['error'] = traceback.format_exc()
        save('failed')
        raise
