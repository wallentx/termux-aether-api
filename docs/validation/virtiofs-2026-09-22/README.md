# Verified diskless Virtio-FS I/O

Tested commit: `e909c7ead7af1fcd8fdd2a7056f0082e5645c556`.
[Successful CI run](https://github.com/wallentx/termux-aether-api/actions/runs/35681060964).
Device run: 2026-09-22 03:26 UTC, using the artifact from that exact run.

Android fingerprint:
`google/kodiak_beta/kodiak:17/CP41.260828.004.A8/16319058:user/release-keys`.
The launcher ran as authorized Shizuku shell UID 2000. The guest used a separately
staged `6.18.52-termux-avf` kernel, an initramfs, 256 MiB RAM, and zero disks.

| Check | Result |
| --- | --- |
| CI kernel configuration | FUSE, Virtio-FS, initrd and gzip support built in. |
| QEMU correctness test | Mount, host-file read, guest-file write, fsync, rename, reread, unmount and shutdown passed; host verified the resulting file. |
| Pixel AVF test | The same operations passed for a newly created Downloads subdirectory; host verified the guest output independently. |
| Cleanup | Probe stage and public test directory removed; no crosvm/probe processes remained. |
| Existing Arch | Still stopped with clean shutdown, zero active sessions and unchanged 16 GiB disk capacity. Its disk was never attached. |

`console.txt` contains the Pixel's BEGIN, IO_OK and PASS markers. `launcher.txt`
records the normal guest shutdown and crosvm's successful exit. The concurrent
`failed to send VcpuControl` message occurs during shutdown; this run still
completed successfully, with the host independently checking file contents and
cleanup. `guest-result.txt` is the file written by the guest and read by Termux.

`PROBE_SHA256SUMS` and `probe-provenance.txt` identify the tested CI inputs.
`virtiofs-console.txt` and `virtiofs-backend.txt` are CI logs, not Pixel timing
measurements. `result.json` and `arch-status-after.json` record the device result
and the separate existing Arch state. Paths in the result identify now-removed
temporary staging directories and the retained private evidence directory.

This validates a selected Android shared-storage folder. It does not bypass the
observed SELinux restriction on Termux-private storage, change the running Arch
configuration, establish full POSIX semantics on emulated storage, or demonstrate
a performance advantage over SSHFS. The next product step is an opt-in shared
storage command and a verified kernel upgrade path that preserves the Arch disk.
