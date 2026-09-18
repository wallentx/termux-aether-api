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
curl --fail --location --proto '=https' --retry 3 \
    "https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-$kernel_version.tar.xz" -o kernel.tar.xz
printf '%s  kernel.tar.xz\n' 2b69564f7d4fea0c859b1959ba33709ee6e9139bd100e30a853b57159a8221b8 | sha256sum -c -
tar -xf kernel.tar.xz
cd "linux-$kernel_version"
make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- defconfig
scripts/kconfig/merge_config.sh -m .config "$source_dir/kernel.config"
make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- olddefconfig
for option in VIRTIO_PCI VIRTIO_MMIO VIRTIO_BLK VIRTIO_CONSOLE EXT4_FS DEVTMPFS_MOUNT MAGIC_SYSRQ; do
    grep -qx "CONFIG_$option=y" .config
done
make -j"$(nproc)" ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- Image
cp arch/arm64/boot/Image "$out/Image"
cp .config "$out/kernel.config"
cd "$work"
arch_url=https://ca.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz
curl --fail --location --proto '=https' --retry 3 "$arch_url" -o arch.tar.gz
# Upstream publishes MD5. HTTPS authenticates transport; record SHA256 provenance too.
# Pin this snapshot so a later rolling tarball change fails instead of silently changing the guest.
printf '%s  arch.tar.gz\n' 023eec86365b24f7913c403e8f4e8719b | md5sum -c -
sha256sum arch.tar.gz > "$out/rootfs-source.sha256"
printf 'source=%s\nkernel=%s\ncommit=%s\n' "$arch_url" "$kernel_version" "$GITHUB_SHA" > "$out/provenance.txt"
sudo bsdtar -xpf arch.tar.gz -C root
sudo install -m 755 "$source_dir/init" root/usr/local/sbin/termux-vm-init
# Lock the upstream default passwords. No guest network/login is enabled for this milestone.
sudo sed -i -E 's/^(root|alarm):[^:]*:/\1:!:/' root/etc/shadow
sudo mkdir -p root/dev root/proc root/sys root/run root/tmp
sudo test -c root/dev/console || sudo mknod -m 600 root/dev/console c 5 1
truncate -s 6G "$out/arch-rootfs.img"
sudo mkfs.ext4 -q -F -L termux-arch -d root "$out/arch-rootfs.img"
sudo chown "$(id -u):$(id -g)" "$out/arch-rootfs.img"
# QEMU is CI boot validation only; the Pixel launcher exclusively uses Android AVF.
set +e
(sleep 25; printf 'poweroff\n') | timeout 90 qemu-system-aarch64 \
    -machine virt -cpu max -m 1024 -nodefaults -no-reboot \
    -kernel "$out/Image" -append 'console=hvc0 root=/dev/vda ro rootwait init=/usr/local/sbin/termux-vm-init panic=-1' \
    -drive "file=$out/arch-rootfs.img,format=raw,if=none,id=root,readonly=on" \
    -device virtio-blk-device,drive=root -device virtio-serial-device \
    -chardev stdio,id=console,signal=off -device virtconsole,chardev=console \
    -display none > "$out/ci-console.txt" 2>&1
boot_exit=${PIPESTATUS[1]}
set -e
cat "$out/ci-console.txt"
[[ $boot_exit == 0 ]]
grep -q '^TERMUX_ARCH_READY_V1' "$out/ci-console.txt"
grep -q '^TERMUX_ARCH_STOPPING_V1' "$out/ci-console.txt"
zstd -T0 -3 --rm "$out/arch-rootfs.img"
cd "$out"
sha256sum Image arch-rootfs.img.zst kernel.config provenance.txt rootfs-source.sha256 ci-console.txt > SHA256SUMS
