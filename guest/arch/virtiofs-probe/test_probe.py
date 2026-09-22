import gzip
import hashlib
from pathlib import Path
import stat
import struct
import tempfile
import unittest

from build_initramfs import build
from ci_test import verify_result
from run_device import FILES, verify_artifact


def elf(interpreter=False):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<I", data, 64, 3 if interpreter else 1)
    return bytes(data)


class ProbeTests(unittest.TestCase):
    def test_initramfs_layout_and_console_device(self):
        archive = gzip.decompress(build(elf()))
        entries = {}
        offset = 0
        while offset < len(archive):
            self.assertEqual(archive[offset:offset + 6], b"070701")
            fields = [int(archive[offset + 6 + i * 8:offset + 14 + i * 8], 16) for i in range(13)]
            size, namesize = fields[6], fields[11]
            name = archive[offset + 110:offset + 110 + namesize - 1].decode()
            data_start = (offset + 110 + namesize + 3) & ~3
            entries[name] = (fields, archive[data_start:data_start + size])
            offset = (data_start + size + 3) & ~3
        self.assertEqual(set(entries), {"dev", "dev/console", "share", "init", "TRAILER!!!"})
        self.assertEqual(entries["dev/console"][0][1], stat.S_IFCHR | 0o600)
        self.assertEqual(entries["dev/console"][0][9:11], [5, 1])
        self.assertEqual(entries["init"][1], elf())
        self.assertEqual(build(elf()), build(elf()))

    def test_reject_dynamic_wrong_arch_and_truncated_elf(self):
        for data in (elf(True), b"\x7fELF", elf()[:100], elf().replace(b"\xb7\0", b"\x3e\0", 1)):
            with self.subTest(data=data[:20]), self.assertRaises(ValueError):
                build(data)

    def test_result_requires_markers_and_host_visible_write(self):
        with tempfile.TemporaryDirectory() as temp:
            share = Path(temp)
            (share / "host.txt").write_bytes(b"aether-host-v1\n")
            (share / "guest").mkdir()
            result = share / "guest/result.txt"
            result.write_bytes(b"aether-guest-v1\n")
            console = "AETHER_VIRTIOFS_BEGIN_V1\nAETHER_VIRTIOFS_IO_OK_V1\nAETHER_VIRTIOFS_PASS_V1\n"
            verify_result(console, share)
            for invalid in (console.replace("IO_OK", "MISSING"), console + "Kernel panic\n",
                            console + "AETHER_VIRTIOFS_FAIL_V1\n", console + console):
                with self.assertRaises(ValueError):
                    verify_result(invalid, share)
            result.write_bytes(b"wrong\n")
            with self.assertRaises(ValueError):
                verify_result(console, share)

    def test_artifact_checks_corruption_duplicates_and_drivers(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in FILES:
                (root / name).write_bytes(b"fixture")
            (root / "kernel.config").write_text("".join(f"CONFIG_{x}=y\n" for x in
                ("FUSE_FS", "VIRTIO_FS", "BLK_DEV_INITRD", "RD_GZIP")))
            def checksum():
                return "".join(f"{hashlib.sha256((root / name).read_bytes()).hexdigest()}  {name}\n"
                               for name in sorted(FILES))
            manifest = root / "PROBE_SHA256SUMS"
            manifest.write_text(checksum())
            verify_artifact(root)
            original = manifest.read_text()
            manifest.write_text(original + original.splitlines()[0] + "\n")
            with self.assertRaises(ValueError): verify_artifact(root)
            manifest.write_text(original)
            (root / "Image").write_bytes(b"corrupt")
            with self.assertRaises(ValueError): verify_artifact(root)
            (root / "kernel.config").write_text("CONFIG_VIRTIO_FS=m\n")
            manifest.write_text(checksum())
            with self.assertRaises(ValueError): verify_artifact(root)


if __name__ == "__main__":
    unittest.main()
