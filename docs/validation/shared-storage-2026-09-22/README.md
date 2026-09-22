# Pixel normal-session Virtio-FS validation

Passed on 2026-09-22 UTC in native Termux UID 10445 with the existing authorized
Shizuku shell UID 2000. Android build: `CP41.260828.004.A8`. No local APK or
kernel build, screen-lock change, root filesystem reinstall or SSH-key reset.

| Component | Tested source/evidence |
| --- | --- |
| API APK | `adc3dc22ec74bbaebc067947695a6885db7134c0`; [successful APK/unit-test CI](https://github.com/wallentx/termux-aether-api/actions/runs/35690767287) |
| Guest init, mount, shutdown helpers | `b2b5d15d174da8e5f9216ad0bd969d9520a1548b`; [successful guest boot CI](https://github.com/wallentx/termux-aether-api/actions/runs/35689991717) |
| CLI + device harness | Local API-package commits `8096892`, `5c831d7`; 40 Arch CLI unit tests passed |
| Kernel | Previously verified `1bab0b33d9bede1ac3546e59caf1b1c824a9c37e5f733a5601b4de88a4d8837a`; not replaced during this integration |

The APK and guest update artifacts passed their published SHA256SUMS before
installation. Only three guest helpers were replaced, with the original init
**renamed** into a backup so its running PID 1 inode remained linked:
`/root/.aether-storage-update-78aac3eb9dd742f1921d398f19924595/`.
The root disk retained device 65042, inode 288875 and size 17179869184 bytes.

## Tested behavior

| Check | Result |
| --- | --- |
| Default disabled boot | Passed with the new API before opt-in |
| Normal enabled boot | `aether_shared virtiofs rw,nosuid,nodev,noexec,relatime` at `/mnt/android` |
| Bidirectional I/O | Guest read the host sentinel; host read the guest's flushed and renamed file |
| Suspend/resume | Status remained responsive; resumed the same CID and observed the host's edited file |
| Default session release | Flushed, emitted `TERMUX_ARCH_SHARED_UNMOUNTED_V1`, remounted root read-only, cleanly stopped |
| Restart persistence | Both files survived the next boot |
| Disable/re-enable | Disabled boot had no mount, preserved the shared files, and re-enable restored access |
| Configuration while suspended | Rejected with nonzero CLI exit; existing share retained |

The committed `device-results.json` is the output of
`termux-aether-api-package/tests/device_arch_storage.py`. Its unique fixture was
removed after passing. Final state: sharing configured, VM stopped,
`clean_shutdown=true`, zero active sessions. No share process is intentionally
kept alive outside the VM lifetime.

## Suspend/status failure found and recovered

The first APK (`b2b5d15`) mounted the folder successfully, but a status request
after `--keep-memory` timed out. Existing status code called
`getActualMemoryBalloonBytes()` while holding the VM control lock. Guest-statistics
queries can wait on a suspended guest and block resume/shutdown along with status.
Platform logs showed balloon-statistics errors.

After explicit user approval, only the stalled Arch owner process was terminated;
its VMM/backend exited. The disk and kernel were preserved. API `adc3dc2` removes
live guest statistics from ordinary status and caches the balloon capability.
The next boot logged ext4 journal recovery complete, mounted the share, unmounted
it, and cleanly shut down. `recovery-stop.json` retains this console evidence.
The full suspend/status/resume/default-release sequence then passed.

Balloon control remains available through the explicit memory operation. Status
now reports `memory_balloon_bytes=null` and `memory_balloon_stats=not_polled`.
The old 2026-09-19 balloon-size observations remain historical evidence, not live
statistics promised by this version.

## Paths and limits

| Side | Path |
| --- | --- |
| Android/Termux | `/storage/emulated/0/Download/AetherShared` (`~/storage/downloads/AetherShared` in Termux) |
| Arch | `/mnt/android` |

The host platform backend owns the export; its reflection schema was verified on
this preview. Private Termux directories still use SSHFS. Android shared storage
does not imply full Unix permissions/symlink/execution semantics. No performance
comparison with SSHFS or guest ext4 has been made.

The API changes are pushed on `wallentx/restore-aether-api-dev`. The API-package
changes are committed locally on `dev`; that branch was not pushed in this task.
