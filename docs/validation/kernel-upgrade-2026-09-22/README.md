# Pixel Arch kernel upgrade and rollback

Validated on 2026-09-22 UTC in native Termux (UID 10445), with the maintenance
helper running through existing authorized Shizuku as shell UID 2000. Android:
`google/kodiak_beta/kodiak:17/CP41.260828.004.A8/16319058:user/release-keys`.
No APK or kernel was built locally; only the small Java maintenance helper was
compiled with `javac --release 8` and converted with `dx`.

The candidate came from successful [Virtio-FS CI run 35681060964](https://github.com/wallentx/termux-aether-api/actions/runs/35681060964),
source commit `e909c7ead7af1fcd8fdd2a7056f0082e5645c556`. It previously passed the
[diskless Pixel read/write probe](../virtiofs-2026-09-22/README.md).

| Check | Observed result |
| --- | --- |
| Candidate upgrade | Passed checksum verification and atomic replacement; original kernel retained |
| Running VM contention | Helper rejected maintenance with `Arch is owned; stop it before kernel maintenance` |
| Candidate boot | Guest reported the Sep 22 kernel build and `nodev virtiofs` in `/proc/filesystems` |
| Rollback | Restored exact original SHA-256; booted the Sep 19 kernel build |
| Reinstall | Restored exact candidate SHA-256; booted it and verified `virtiofs` again |
| Disk preservation | Device 65042, inode 288875, size 17179869184 bytes before/after replacements |
| Final lifecycle | `stopped`, `running=false`, `clean_shutdown=true`, zero sessions, 8192 MiB preference retained |

Kernel digests:

| Image | SHA-256 |
| --- | --- |
| Original | `3a04b30955695df5e7b278bd73eeb2e821b251c3a3dfae2532a095ca530dd7a9` |
| Installed candidate | `1bab0b33d9bede1ac3546e59caf1b1c824a9c37e5f733a5601b4de88a4d8837a` |

The final installation's old-kernel backup ID is
`15323a4d3dee42489befc70677c4c7c1`; earlier rollback snapshots also remain.
The private local helper is retained at
`~/.local/lib/termux-aether/kernel-maintenance/kernel-upgrade.jar` with SHA256SUMS.
From this checkout, a future rollback after a clean stop is:

```sh
python guest/arch/kernel-upgrade/kernel_upgrade.py \
  --helper "$HOME/.local/lib/termux-aether/kernel-maintenance/kernel-upgrade.jar" \
  rollback 15323a4d3dee42489befc70677c4c7c1
```

Raw evidence is alongside this document. Complete private invocation records are
under `~/.local/state/termux-aether/kernel-upgrade-device-check/` and the individual
`kernel-<id>/` paths in the JSON. Expected lock failures retained diagnostic
staging directories; successful operations cleaned their transfers/stages.

Local validation passed 18 Java assertions covering replacement, rollback,
lock contention, malformed header, checksum mismatch, stale current hash,
symlink/permissive-file rejection, corrupted backups and untouched fixture-disk
contents/inode. Python guard tests and workflow lint passed. The new helper
workflow has not yet run in CI; the actual helper was tested on this device.

These results establish kernel maintenance and guest boot, not a production
Virtio-FS mount or a speedup. No folder is newly shared by normal `Æ`/`æ` sessions.
Full disk content hashes were not taken; inode/size evidence and the updater's
stat-only disk path establish that it did not replace/copy the root image.
