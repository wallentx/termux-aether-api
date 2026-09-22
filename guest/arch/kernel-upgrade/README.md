# Reversible Arch kernel upgrade

This developer tool replaces only the host-side `Image` used by the existing
Arch VM. It requires authorized shell Shizuku (`rish`), a cleanly stopped guest,
and an existing `owner.lock`. No root, APK update, kernel build, screen-lock
change or root filesystem reinstall is required on the Pixel.

The Pixel upgrade, live-owner rejection, rollback boot, reinstall boot and clean
shutdown passed on 2026-09-22 UTC. See [device evidence](../../../docs/validation/kernel-upgrade-2026-09-22/README.md).

## Use

1. Download `arch-kernel-maintenance-<commit>` from a successful **Arch kernel
   maintenance** workflow run in the trusted fork. Extract it into private
   Termux storage and run `sha256sum -c SHA256SUMS` there. The helper and kernel
   can come from different commits: the helper is not a kernel build.
2. Download the successful **Virtio-FS diskless probe** artifact into private
   storage. Select its full source commit independently from the CI run. The
   installer checks every probe artifact hash, required built-in drivers and
   the matching provenance file. Checksums detect corruption; they do not
   authenticate an untrusted artifact source.
3. Save guest work, exit sessions, and cleanly stop Arch:

   ```sh
   termux-arch-vm --stop
   ```

4. From this API checkout, install the kernel:

   ```sh
   python guest/arch/kernel-upgrade/kernel_upgrade.py \
     --helper /private/path/kernel-upgrade.jar \
     install /private/path/extracted-probe-artifact --commit FULL_40_CHARACTER_COMMIT
   ```

   Save the printed `backup_id`. `status=passed` proves replacement, **not boot**;
   `boot_verified=false` makes that boundary explicit.
5. Verify the new guest, then stop it:

   ```sh
   termux-arch --cwd /root sh -c 'uname -a; grep -w virtiofs /proc/filesystems'
   termux-arch-vm --stop
   ```

Each invocation normally takes a few seconds, with a 90-second shell timeout.
This is a source-checkout maintenance tool; it is not yet installed by the
API package or invoked automatically by `Æ`/`æ`.

## Rollback

With Arch cleanly stopped, use the backup ID printed by install:

```sh
python guest/arch/kernel-upgrade/kernel_upgrade.py \
  --helper /private/path/kernel-upgrade.jar rollback BACKUP_ID
```

Rollback verifies the saved image and also backs up the displaced kernel. It
can recover a failed boot when `clean_shutdown=false`, provided `running=false`,
there are no sessions, and the helper acquires the VM lock. This never force-stops
a guest or repairs a filesystem; new installations still require a clean
shutdown. `inspect` also requires the VM lock.

## Guarantees and failure handling

- The Java helper takes the same `FileChannel.tryLock()` POSIX record lock on
  `owner.lock` as `ArchVmUserService`. A running/suspended VM blocks maintenance;
  holding the maintenance lock also prevents a concurrent VM start. It never
  creates, deletes or replaces that lock file.
- Existing and candidate kernels must be private shell-owned regular files,
  without symlinks/hardlinks, with ARM64 Image headers and matching SHA-256.
  A second checksum of the installed kernel detects changes between inspection
  and replacement. Shared-storage staging is rechecked against private hashes.
- Before replacement, the old image and its checksum are copied and synced to
  `/data/local/tmp/termux-arch-v2/kernel-backups/<id>/`. The candidate is copied to
  a unique adjacent file, synced, verified and atomically renamed to `Image`.
  A crash leaves a complete old/new active image, possibly an incomplete unused
  backup or pending file. Only verified backups can be restored. A failure after
  rename may mean the new image is active: use `inspect` before retrying.
- `arch-rootfs.img` is only statted. Its inode/device/size are reported; its
  contents, SSH credentials, network helper and memory setting are untouched.
  Backups are retained, with approximately one kernel image of extra storage
  per actual replacement. No backup pruning happens automatically.
- Evidence is saved privately under `~/.local/state/termux-aether/kernel-<id>/`.
  Success removes only that invocation's transfer/staging directories. Failure
  retains their exact paths in `result.json`; inspect its logs before cleanup.

The installed kernel enables Virtio-FS but does not export or mount any folder.
Normal sessions still use SSHFS for sharing until the explicit share integration
is implemented. Private Termux directories remain outside the platform
Virtio-FS backend's verified access scope.

## Small local checks and helper build

The Java helper is small and has no Android SDK compile dependency. This does
not build a kernel or APK. On Termux, with Java and `dx` installed:

```sh
mkdir -p "$TMPDIR/aether-kernel-classes"
javac --release 8 -d "$TMPDIR/aether-kernel-classes" guest/arch/kernel-upgrade/*.java
java -cp "$TMPDIR/aether-kernel-classes" KernelUpgradeTest
python -m unittest discover -s guest/arch/kernel-upgrade -p 'test_*.py'
dx --dex --no-strict --output="$TMPDIR/kernel-upgrade.jar" "$TMPDIR/aether-kernel-classes/KernelUpgrade.class"
```

Keep a copy of the resulting jar in private persistent storage if you need it
after a reboot or temporary-file cleanup. CI packages the helper with D8.
