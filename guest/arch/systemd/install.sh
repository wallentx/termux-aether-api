#!/usr/bin/env bash
# Install a verified guest-update payload; does not restart the guest or services.
set -euo pipefail
root=${1:?usage: install.sh ROOT PAYLOAD}
payload=${2:?usage: install.sh ROOT PAYLOAD}
[[ $root == /* && -d $root && -d $payload/systemd/units && ! -L $payload/systemd/units ]]
root=${root%/}
[[ -x $root/usr/lib/systemd/systemd ]]

runtime_sources=(termux-vm-legacy-init termux-vm-systemd termux-vm-shutdown
                 termux-vm-network termux-vm-shared-storage termux-vsock-net termux-vsock-ssh
                 termux-dhcp-hook termux-landlock-check)
runtime_destinations=(usr/local/sbin/termux-vm-legacy-init usr/local/sbin/termux-vm-systemd
                      usr/local/sbin/termux-vm-shutdown usr/local/sbin/termux-vm-network
                      usr/local/sbin/termux-vm-shared-storage usr/local/sbin/termux-vsock-net
                      usr/local/sbin/termux-vsock-ssh usr/local/libexec/termux-dhcp-hook
                      usr/local/bin/termux-landlock-check)
unit_files=(termux-vm-bootstrap.service termux-vm-sshd.service termux-vm-vsock-ssh.service
            termux-vm-network.service termux-vm-shared-storage.service termux-vm-ready.service
            termux-vm-console.service termux-vm.target)
[[ ${#runtime_sources[@]} -eq ${#runtime_destinations[@]} ]]
required_files=(termux-vm-init systemd/termux-vm-check systemd/journald.conf)
required_files+=("${runtime_sources[@]}")
for unit in "${unit_files[@]}"; do required_files+=("systemd/units/$unit"); done
for file in "${required_files[@]}"; do
    [[ -f $payload/$file && ! -L $payload/$file ]]
done

# Only distro defaults that conflict with the AVF network/SSH/console contract.
masks=(sshd.service dhcpcd.service systemd-networkd.service systemd-networkd-wait-online.service
       systemd-networkd.socket systemd-networkd-varlink.socket systemd-networkd-varlink-metrics.socket
       systemd-networkd-resolve-hook.socket systemd-resolved.service systemd-resolved-varlink.socket
       systemd-resolved-monitor.socket systemd-timesyncd.service serial-getty@hvc0.service console-getty.service)
paths=(usr/local/sbin/termux-vm-init "${runtime_destinations[@]}"
       usr/lib/systemd/system-shutdown/termux-vm-check etc/systemd/journald.conf.d/termux-vm.conf
       etc/systemd/system/multi-user.target.wants/termux-vm.target etc/systemd/system/local-fs.target.wants/tmp.mount
       etc/resolv.conf)
for unit in "${unit_files[@]}"; do paths+=("etc/systemd/system/$unit"); done
for unit in "${masks[@]}"; do paths+=("etc/systemd/system/$unit"); done
if [[ -z $root ]]; then
    # Capture exactly the files and symlinks this installer can replace.
    install -d -m 700 "$root/var/lib/termux-vm/systemd-backups"
    backup=$(mktemp -d "$root/var/lib/termux-vm/systemd-backups/install.XXXXXX")
    printf '%s\n' "${paths[@]}" > "$backup/paths"
    for file in "${paths[@]}"; do
        if [[ -e $root/$file || -L $root/$file ]]; then printf '%s\n' "$file"; fi
    done > "$backup/existing"
    tar -C "${root:-/}" -cpf "$backup/before.tar" --verbatim-files-from -T "$backup/existing"
    printf 'SYSTEMD_BACKUP=%s\n' "$backup"
fi
install -d -m 755 "$root/etc/systemd/system/multi-user.target.wants" \
    "$root/etc/systemd/system/local-fs.target.wants" "$root/etc/systemd/journald.conf.d" \
    "$root/usr/lib/systemd/system-shutdown" "$root/usr/local/sbin" \
    "$root/usr/local/libexec" "$root/usr/local/bin"
for index in "${!runtime_sources[@]}"; do
    install -m 755 "$payload/${runtime_sources[$index]}" "$root/${runtime_destinations[$index]}"
done
install -m 755 "$payload/systemd/termux-vm-check" "$root/usr/lib/systemd/system-shutdown/termux-vm-check"
for unit in "${unit_files[@]}"; do
    install -m 644 "$payload/systemd/units/$unit" "$root/etc/systemd/system/$unit"
done
install -m 644 "$payload/systemd/journald.conf" "$root/etc/systemd/journald.conf.d/termux-vm.conf"
for unit in "${masks[@]}"; do ln -sfn /dev/null "$root/etc/systemd/system/$unit"; done
ln -sfn ../termux-vm.target "$root/etc/systemd/system/multi-user.target.wants/termux-vm.target"
ln -sfn /usr/lib/systemd/system/tmp.mount "$root/etc/systemd/system/local-fs.target.wants/tmp.mount"
# resolved is masked above; migrate only its known distro links to the DHCP
# hook's resolver. Preserve regular files and administrator-selected links.
if [[ -L $root/etc/resolv.conf ]]; then
    resolver_target=$(readlink -- "$root/etc/resolv.conf")
    case $resolver_target in
        /run/systemd/resolve/resolv.conf|../run/systemd/resolve/resolv.conf|\
        /run/systemd/resolve/stub-resolv.conf|../run/systemd/resolve/stub-resolv.conf)
            pending_resolver=$(mktemp "$root/etc/.termux-resolv.conf.XXXXXX")
            ln -sfn /run/termux-network/resolv.conf "$pending_resolver"
            # Recheck after staging so a custom DNS change is not overwritten.
            if [[ -L $root/etc/resolv.conf && $(readlink -- "$root/etc/resolv.conf") == "$resolver_target" ]]; then
                mv -Tf -- "$pending_resolver" "$root/etc/resolv.conf"
            else
                rm -f -- "$pending_resolver"
            fi
            ;;
    esac
fi
# Activate only after every dependency is present. Rename is atomic and leaves
# the running legacy PID 1 on its old inode until its normal clean shutdown.
pending=$(mktemp "$root/usr/local/sbin/.termux-vm-init.XXXXXX")
install -m 755 "$payload/termux-vm-init" "$pending"
sync -f "$root/usr/local/sbin"
mv -f -- "$pending" "$root/usr/local/sbin/termux-vm-init"
sync -f "$root/usr/local/sbin"
echo 'Systemd guest files installed; the running init is unchanged until reboot.'
