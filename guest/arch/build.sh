#!/usr/bin/env bash
# Build only on GitHub's Linux runner, never on the Android development device.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && $(uname -s) == Linux ]] || {
    echo 'Run this image build in GitHub Actions.' >&2; exit 2;
}
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d "$RUNNER_TEMP/termux-arch.XXXXXX")
out="$GITHUB_WORKSPACE/guest-out"
mkdir -p "$out" "$work/root"
cd "$work"
kernel_version=6.18.52
mode=${1:-all}
[[ $mode == all || $mode == kernel || $mode == rootfs ]] || exit 2
if [[ $mode != rootfs ]]; then
curl --fail --location --proto '=https' --retry 3 \
    "https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-$kernel_version.tar.xz" -o kernel.tar.xz
printf '%s  kernel.tar.xz\n' 2b69564f7d4fea0c859b1959ba33709ee6e9139bd100e30a853b57159a8221b8 | sha256sum -c -
tar -xf kernel.tar.xz
cd "linux-$kernel_version"
make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- defconfig
scripts/kconfig/merge_config.sh -m .config "$source_dir/kernel.config"
make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- olddefconfig
for option in VIRTIO_PCI VIRTIO_MMIO VIRTIO_BLK VIRTIO_CONSOLE EXT4_FS DEVTMPFS_MOUNT MAGIC_SYSRQ SECURITY_LANDLOCK; do
    grep -qx "CONFIG_$option=y" .config
done
grep -Eq '^CONFIG_LSM="([^" ]*,)?landlock(,[^" ]*)?"$' .config
make -j"$(nproc)" ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- Image
cp arch/arm64/boot/Image "$out/Image"
cp .config "$out/kernel.config"
fi
[[ $mode != kernel ]] || exit 0
cd "$work"
arch_url=https://ca.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz
curl --fail --location --proto '=https' --retry 3 "$arch_url" -o arch.tar.gz
# Upstream publishes MD5. HTTPS authenticates transport; record SHA256 provenance too.
# Pin this snapshot so a later rolling tarball change fails instead of silently changing the guest.
printf '%s  arch.tar.gz\n' 23eec86365b24f7913c403e8f4e8719b | md5sum -c -
sha256sum arch.tar.gz > "$out/rootfs-source.sha256"
printf 'source=%s\nkernel=%s\ncommit=%s\n' "$arch_url" "$kernel_version" "$GITHUB_SHA" > "$out/provenance.txt"
sudo bsdtar -xpf arch.tar.gz -C root
sudo install -m 755 "$source_dir/init" root/usr/local/sbin/termux-vm-init
sudo install -m 755 "$source_dir/network" root/usr/local/sbin/termux-vm-network
sudo install -D -m 755 "$source_dir/dhcp-hook" root/usr/local/libexec/termux-dhcp-hook
sudo ln -sfn /run/termux-network/resolv.conf root/etc/resolv.conf
# No reusable keys or default passwords in published images. Root's impossible
# hash keeps the account usable for public-key SSH with UsePAM=no.
sudo sed -i -E 's/^root:[^:]*:/root:*:/; s/^alarm:[^:]*:/alarm:!:/' root/etc/shadow
sudo rm -f root/etc/ssh/ssh_host_* root/root/.ssh/authorized_keys
sudo test -x root/usr/bin/sshd
sudo test -x root/usr/bin/ip
sudo test -x root/usr/bin/dhcpcd
sudo install -m 600 "$source_dir/sshd_config" root/etc/ssh/sshd_config.termux
aarch64-linux-gnu-gcc -static -O2 -Wall -Wextra -Werror "$source_dir/vsock-ssh.c" -o vsock-ssh
sudo install -m 755 vsock-ssh root/usr/local/sbin/termux-vsock-ssh
aarch64-linux-gnu-gcc -static -O2 -Wall -Wextra -Werror "$source_dir/landlock-check.c" -o landlock-check
sudo install -m 755 landlock-check root/usr/local/bin/termux-landlock-check
aarch64-linux-gnu-gcc -static -O2 -Wall -Wextra -Werror "$source_dir/shutdown.c" -o vm-shutdown
sudo install -m 755 vm-shutdown root/usr/local/sbin/termux-vm-shutdown
aarch64-linux-gnu-gcc -static -O2 -Wall -Wextra -Werror "$source_dir/vsock-net.c" -o vsock-net
sudo install -m 755 vsock-net root/usr/local/sbin/termux-vsock-net
# Small update payload for existing guests: never replace their writable disk.
mkdir -p update
install -m 755 "$source_dir/init" update/termux-vm-init
install -m 755 "$source_dir/network" update/termux-vm-network
install -m 755 "$source_dir/dhcp-hook" update/termux-dhcp-hook
install -m 755 landlock-check update/termux-landlock-check
install -m 755 vm-shutdown update/termux-vm-shutdown
install -m 755 vsock-net update/termux-vsock-net
tar -C update -cf "$out/guest-update.tar" .
sudo mkdir -p root/dev root/proc root/sys root/run root/tmp
sudo test -c root/dev/console || sudo mknod -m 600 root/dev/console c 5 1
truncate -s 6G "$out/arch-rootfs.img"
sudo mkfs.ext4 -q -F -L termux-arch -d root "$out/arch-rootfs.img"
sudo chown "$(id -u):$(id -g)" "$out/arch-rootfs.img"
# Test a disposable copy so CI host keys and test files never ship to devices.
python3 "$source_dir/boot_test.py" "$out" "$work"
zstd -T0 -3 --rm "$out/arch-rootfs.img"
cd "$out"
sha256sum Image arch-rootfs.img.zst guest-update.tar arch-network-host network-host-LICENSES.txt kernel.config provenance.txt rootfs-source.sha256 ci-console.txt > SHA256SUMS
