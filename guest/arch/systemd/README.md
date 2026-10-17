# Systemd Arch AVF guest

The existing kernel `init=/usr/local/sbin/termux-vm-init` path now mounts `/proc`
if needed, makes the persistent root writable, and executes systemd as PID 1.
The kernel and writable guest disk do not need replacement for this migration.
Cloud-init is not installed; provisioning remains in the idempotent bootstrap
helper until a separate user-data/datasource design is introduced.

## Boot responsibilities

| Unit | Responsibility |
| --- | --- |
| `termux-vm-bootstrap` | Seed the clock and current authorized SSH key every boot; generate missing host keys and initialize Pacman trust once. |
| `termux-vm-sshd`, `termux-vm-vsock-ssh` | Private SSH and host-only vsock transport. Only CI exposes SSH over Ethernet. |
| `termux-vm-network` | Existing userspace TAP/DHCP/DNS helper, supervised as a service. |
| `termux-vm-shared-storage` | Optional verified Virtio-FS mount and normal service-stop unmount. |
| `termux-vm-ready`, `termux-vm-console` | Raw host-key/readiness protocol and independent fixed `poweroff` control on hvc0. |

`termux-vm.target` is enabled under `multi-user.target`, alongside ordinary guest
services. Readiness never waits for `network-online.target`: Android starts its
network backend only after the readiness marker. An optional share failure is
reported separately, preserving the host's existing failure diagnosis.

The installer masks the stock SSH/network management services that would
conflict with this transport, and the hvc0 login getty that would consume control
commands. It does not delete user service units. `/tmp` remains a tmpfs and the
journal is volatile, bounded to 32 MiB.

## Shutdown contract

The console service asks systemd to power off. Systemd stops services and
performs its normal filesystem teardown. A static final hook then checks that
root is actually read-only and no mount remains at or below `/mnt/android`.
After syncing and rechecking, it emits `TERMUX_ARCH_SHARED_UNMOUNTED_V1` and
`TERMUX_ARCH_STOPPING_V2` directly to hvc0. Failed proof emits no clean marker.

Unlike the legacy PID 1 helper, systemd does not let a failing shutdown hook veto
poweroff. If filesystem cleanup fails, the host therefore records an unclean
exit rather than a clean shutdown. The hook never fabricates success or forces
an unmount. Preserving the legacy behavior of waiting alive for a retry would
require a separate shutdown initramfs design.

The host's stop request waits briefly and can return `status=stopping` while
systemd finishes a service's graceful stop. This is not a shutdown failure. The
owner retains the VM and continues watching it; poll status until `running=false`
and require `clean_shutdown=true` before maintenance or replacement.

## CI and migration payload

The Arch guest workflow builds `guest-update.tar`, boots a disposable image under
QEMU, tests systemd services and persistent state, and checks clean filesystem
shutdown. The small `arch-systemd-update-<commit>` artifact contains the update,
its provenance, and checksums, avoiding a full rootfs download for existing VMs.
The Android API build and kernel-maintenance helper from the same source commit
provide the recovery init selector.

Download that tested API APK and retain the matching `kernel-upgrade.jar` before
the migration. Install the inert guest payload while the legacy guest is still
running. Finish terminal work, obtain restart approval, and stop the VM cleanly.
Only while it is fully stopped, install the updated API APK; then the recovery
selector is available before the first systemd boot. Never update the API APK
while its service owns a running guest.

Verify the selected CI commit in `provenance.txt` and verify `guest-update.tar`
against `SHA256SUMS` before extracting it. Stage the payload in a private guest
directory and, as guest root, run:

```sh
bash /root/verified-systemd-update/systemd/install.sh / /root/verified-systemd-update
```

The installer records a private backup of every replaced file/symlink under
`/var/lib/termux-vm/systemd-backups/`. It does not reload or restart anything; the
new init is atomically activated last, after its dependencies are installed, and
takes effect on the next boot. The preserved legacy init is
`/usr/local/sbin/termux-vm-legacy-init`. Guest account data, SSH host identity,
package state, and the root disk are retained.

After the approved reboot, verify PID 1, service state, SSH, networking, and the
configured shared storage before accepting the migration:

```sh
cat /proc/1/comm
systemctl status termux-vm.target
systemctl --failed
journalctl -b -u termux-vm-bootstrap -u termux-vm-sshd
```

## Recovery without guest SSH

An updated API APK accepts a private host-side `init-mode` containing only
`systemd` or `legacy`. The maintenance helper writes it under the same owner lock
as the VM manager, only when the VM is fully stopped with no active sessions.
It never opens or rewrites the guest disk or kernel.

```sh
python guest/arch/kernel-upgrade/kernel_upgrade.py \
  --helper /private/path/kernel-upgrade.jar boot-mode --mode legacy
```

Then start Arch normally. This selects the preserved Bash init without needing
guest SSH or offline disk edits. After correcting the systemd configuration,
stop cleanly and select `--mode systemd` to retry. A failed boot may select legacy
once the owner has fully stopped, even when `clean_shutdown` is false.
