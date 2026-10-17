#!/usr/bin/env bash
# Install a verified guest-update payload; does not restart the guest or services.
set -euo pipefail
root=${1:?usage: install.sh ROOT PAYLOAD}
payload=${2:?usage: install.sh ROOT PAYLOAD}
[[ $root == /* && -d $root && -d $payload/systemd/units ]]
root=${root%/}
[[ -x $root/usr/lib/systemd/systemd ]]
for file in termux-vm-init termux-vm-legacy-init termux-vm-systemd termux-vm-shutdown; do
    [[ -f $payload/$file && ! -L $payload/$file ]]
done
[[ -f $payload/systemd/termux-vm-check ]]

# Only distro defaults that conflict with the AVF network/SSH/console contract.
masks=(sshd.service dhcpcd.service systemd-networkd.service systemd-networkd-wait-online.service
       systemd-networkd.socket systemd-networkd-varlink.socket systemd-networkd-varlink-metrics.socket
       systemd-networkd-resolve-hook.socket systemd-resolved.service systemd-resolved-varlink.socket
       systemd-resolved-monitor.socket systemd-timesyncd.service serial-getty@hvc0.service console-getty.service)
paths=(usr/local/sbin/termux-vm-init usr/local/sbin/termux-vm-legacy-init
       usr/local/sbin/termux-vm-systemd usr/local/sbin/termux-vm-shutdown
       usr/lib/systemd/system-shutdown/termux-vm-check etc/systemd/journald.conf.d/termux-vm.conf
       etc/systemd/system/multi-user.target.wants/termux-vm.target etc/systemd/system/local-fs.target.wants/tmp.mount)
for file in "$payload"/systemd/units/*; do paths+=("etc/systemd/system/${file##*/}"); done
for unit in "${masks[@]}"; do paths+=("etc/systemd/system/$unit"); done
if [[ -z $root ]]; then
    # Capture exactly the files and symlinks this installer can replace.
    install -d -m 700 /var/lib/termux-vm/systemd-backups
    backup=$(mktemp -d /var/lib/termux-vm/systemd-backups/install.XXXXXX)
    printf '%s\n' "${paths[@]}" > "$backup/paths"
    for file in "${paths[@]}"; do
        if [[ -e /$file || -L /$file ]]; then printf '%s\n' "$file"; fi
    done > "$backup/existing"
    tar -C / -cpf "$backup/before.tar" --verbatim-files-from -T "$backup/existing"
    printf 'SYSTEMD_BACKUP=%s\n' "$backup"
fi
install -d -m 755 "$root/etc/systemd/system/multi-user.target.wants" \
    "$root/etc/systemd/system/local-fs.target.wants" "$root/etc/systemd/journald.conf.d" \
    "$root/usr/lib/systemd/system-shutdown"
for file in termux-vm-legacy-init termux-vm-systemd termux-vm-shutdown; do
    install -m 755 "$payload/$file" "$root/usr/local/sbin/$file"
done
install -m 755 "$payload/systemd/termux-vm-check" "$root/usr/lib/systemd/system-shutdown/termux-vm-check"
install -m 644 "$payload/systemd/units/"* "$root/etc/systemd/system/"
install -m 644 "$payload/systemd/journald.conf" "$root/etc/systemd/journald.conf.d/termux-vm.conf"
for unit in "${masks[@]}"; do ln -sfn /dev/null "$root/etc/systemd/system/$unit"; done
ln -sfn ../termux-vm.target "$root/etc/systemd/system/multi-user.target.wants/termux-vm.target"
ln -sfn /usr/lib/systemd/system/tmp.mount "$root/etc/systemd/system/local-fs.target.wants/tmp.mount"
# Activate only after every dependency is present. Rename is atomic and leaves
# the running legacy PID 1 on its old inode until its normal clean shutdown.
pending=$(mktemp "$root/usr/local/sbin/.termux-vm-init.XXXXXX")
install -m 755 "$payload/termux-vm-init" "$pending"
sync -f "$root/usr/local/sbin"
mv -f -- "$pending" "$root/usr/local/sbin/termux-vm-init"
sync -f "$root/usr/local/sbin"
echo 'Systemd guest files installed; the running init is unchanged until reboot.'
