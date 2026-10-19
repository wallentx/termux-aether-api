# Diskless Virtio-FS mount probe

This is an opt-in developer test for selected **Android shared storage**, not
private Termux home sharing. The Pixel host attachment was verified on Android
build `CP41.260828.004.A8`; private-directory traversal was denied by SELinux.
The mount/read/write implementation passed CI and a Pixel run on 2026-09-22 UTC.
See the [validation evidence](../../../docs/validation/virtiofs-2026-09-22/README.md).
No performance improvement is claimed.

## What the test does

The guest uses a static PID 1 in a small initramfs, 256 MiB RAM, one default vCPU,
no network and **zero disks**. It mounts the `aether_probe` Virtio-FS tag with
`nodev,nosuid,noexec`, reads a host sentinel, creates a guest sentinel exclusively,
flushes it, renames and reads it back, then unmounts and powers off. The host checks
the console markers and the resulting file contents independently. A timeout,
kernel panic, missing marker or wrong file is a failure.

The existing Arch kernel, root disk, keys and service configuration are not
replaced or used by this runner. The workflow builds a separate artifact and
does not publish a release, APK or root filesystem. Adding built-in FUSE and
Virtio-FS drivers does not automatically share any directory in regular Arch.

## Run after the changes are available in CI

1. Push probe changes on a `wallentx/**` branch, or dispatch **Virtio-FS diskless probe** on the branch containing this change in
   `wallentx/termux-aether-api`. The workflow builds the kernel in CI, boots the
   diskless test under QEMU, and packages the same kernel/initramfs with a Pixel
   launcher. QEMU checks Linux correctness, not Pixel permissions or speed.
2. Download and extract its successful `virtiofs-probe-<commit>` artifact into
   Termux-private storage. Use an artifact from your trusted repository/run;
   SHA-256 protects integrity, not provenance by itself.
3. With the existing Shizuku authorization active, run from this repository:

   ```sh
   python guest/arch/virtiofs-probe/run_device.py /path/to/extracted/artifact
   ```

   Allow up to 45 seconds for the device test. It needs neither a screen-lock
   change nor an APK install. It creates a uniquely named disposable Downloads
   folder and a separate `/data/local/tmp/aether-vufs-<id>` staging directory.
4. Read the printed evidence directory under
   `~/.local/state/termux-aether/virtiofs-<id>/`. A pass requires actual host-visible
   file I/O, not merely a successful AVF `start()` call. Successful runs remove
   their disposable share and staging directory; failures retain those exact
   paths in `result.json` for diagnosis.

The runner checks artifacts before copying, then checks the shell-owned staged
files against those same trusted hashes before executing. It does not trust a
checksum manifest copied through shared storage as the verification authority.
The Java launcher requires isolated path names, a non-null initramfs and no disks;
its owner watchdog limits the VM's lifetime to 30 seconds.

## Local checks (no VM or kernel build)

```sh
python -m unittest discover -s guest/arch/virtiofs-probe -p 'test_*.py' -v
clang -fsyntax-only -Wall -Wextra -Werror guest/arch/virtiofs-probe/init.c
bash -n guest/arch/build.sh
actionlint .github/workflows/virtiofs-probe.yml
```

Java source can also be compiled with `javac --release 8` and converted using
`dx --dex` locally or D8 in CI. Compile success does not validate the installed
AVF reflection API; device execution is required. The workflow's kernel config
checks run even when a cached kernel is restored.

## Limits

This test does not establish symlink, executable-bit, ownership, locking or
project-build behavior on Android emulated storage. Keep private projects on the
existing SSHFS share or guest-local ext4. A user-facing sharing command and any
performance comparison should follow successful CI and Pixel I/O checks.

References: [Linux Virtio-FS](https://docs.kernel.org/filesystems/virtiofs.html),
[initramfs archive format](https://docs.kernel.org/driver-api/early-userspace/buffer-format.html),
[CI backend usage](https://gitlab.com/virtio-fs/virtiofsd/-/blob/main/README.md).
