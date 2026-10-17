"""Exercise the shutdown proof with fixtures; never shut down the test host."""

import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
ROOT = "/dev/vda / ext4 ro,relatime 0 0\n"


class ShutdownProofTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="shutdown-proof-", dir=os.environ.get("TMPDIR"))
        cls.addClassCleanup(cls.temp.cleanup)
        source = Path(cls.temp.name) / "proof-test.c"
        cls.binary = Path(cls.temp.name) / "proof-test"
        source.write_text(
            '#define main shutdown_hook_main\n'
            '#include "shutdown-check.c"\n'
            '#undef main\n'
            'int main(int argc, char **argv) {\n'
            '    if (argc != 2) return 99;\n'
            '    if (strcmp(argv[1], "missing") == 0) return shutdown_proof(ST_RDONLY, NULL);\n'
            '    unsigned long flags = strcmp(argv[1], "ro") == 0 ? ST_RDONLY : 0;\n'
            '    return shutdown_proof(flags, stdin);\n'
            '}\n'
        )
        subprocess.run(
            shlex.split(os.environ.get("CC", "cc"))
            + ["-std=c11", "-Wall", "-Wextra", "-Werror", "-I", str(HERE), str(source), "-o", str(cls.binary)],
            check=True,
        )

    def check_proof(self, mounts, expected, flags="ro"):
        result = subprocess.run([str(self.binary), flags], input=mounts, text=True, capture_output=True)
        self.assertEqual(result.returncode, expected, result.stderr)
        self.assertEqual(result.stdout, "")

    def test_clean_readonly_root(self):
        self.check_proof(ROOT + "proc /proc proc rw,nosuid,nodev,noexec 0 0\n", 0)

    def test_mount_list_unavailable(self):
        self.check_proof("", 3, flags="missing")

    def test_actual_statvfs_flags_override_mount_text(self):
        self.check_proof(ROOT, 2, flags="rw")

    def test_shared_mount_still_present(self):
        self.check_proof(ROOT + "android /mnt/android virtiofs rw 0 0\n", 6)

    def test_shared_mount_of_other_type_still_blocks(self):
        self.check_proof(ROOT + "tmpfs /mnt/android tmpfs rw 0 0\n", 6)

    def test_nested_shared_mount_without_parent(self):
        self.check_proof(ROOT + "tmpfs /mnt/android/nested tmpfs rw 0 0\n", 6)

    def test_escaped_nested_mount(self):
        self.check_proof(ROOT + "tmpfs /mnt/android/a\\040b tmpfs rw 0 0\n", 6)

    def test_similar_unrelated_path_is_allowed(self):
        self.check_proof(ROOT + "tmpfs /mnt/android-other tmpfs rw 0 0\n", 0)

    def test_empty_mount_list_is_not_proof(self):
        self.check_proof("", 5)

    def test_missing_root_is_not_proof(self):
        self.check_proof("proc /proc proc rw 0 0\n", 5)

    def test_truncated_record_is_rejected(self):
        self.check_proof(ROOT + "android /mnt/android", 4)

    def test_missing_final_newline_is_rejected(self):
        self.check_proof(ROOT.rstrip("\n"), 4)

    def test_missing_fields_are_rejected(self):
        self.check_proof(ROOT + "android /mnt/android virtiofs rw\n", 4)

    def test_invalid_numeric_fields_are_rejected(self):
        self.check_proof(ROOT.replace("0 0", "bad 0"), 4)

    def test_embedded_nul_is_rejected(self):
        self.check_proof(ROOT + "\0android /mnt/android virtiofs rw 0 0\n", 4)

    def test_extra_fields_are_rejected(self):
        self.check_proof(ROOT.rstrip("\n") + " extra\n", 4)

    def test_nonabsolute_mount_path_is_rejected(self):
        self.check_proof(ROOT + "tmpfs relative/path tmpfs rw 0 0\n", 4)


class ShutdownMarkerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="shutdown-markers-", dir=os.environ.get("TMPDIR"))
        cls.addClassCleanup(cls.temp.cleanup)
        source = Path(cls.temp.name) / "marker-test.c"
        cls.binary = Path(cls.temp.name) / "marker-test"
        # Replace every host-facing operation; mount parsing still exercises the
        # real mntent implementation. The fixture never opens the host console,
        # calls sync(), or inspects the host root filesystem.
        source.write_text(r'''
#define _GNU_SOURCE
#include <fcntl.h>
#include <mntent.h>
#include <stdio.h>
#include <string.h>
#include <sys/statvfs.h>
#include <unistd.h>
static const char *scenario;
static unsigned stat_calls, sync_calls;
static int fake_statvfs(const char *path, struct statvfs *info) {
    if (strcmp(path, "/") != 0) return -1;
    stat_calls++;
    if (strcmp(scenario, "stat-error") == 0) return -1;
    memset(info, 0, sizeof(*info));
    info->f_flag = ST_RDONLY;
    if (strcmp(scenario, "rw") == 0
            || (strcmp(scenario, "changed") == 0 && stat_calls > 1)) info->f_flag = 0;
    return 0;
}
static FILE *fake_setmntent(const char *path, const char *mode) {
    if (strcmp(path, "/proc/mounts") != 0 || strcmp(mode, "r") != 0) return NULL;
    if (strcmp(scenario, "mounts-error") == 0) return NULL;
    const char *fixture = "/dev/vda / ext4 ro 0 0\n";
    if (strcmp(scenario, "share") == 0)
        fixture = "/dev/vda / ext4 ro 0 0\nandroid /mnt/android virtiofs rw 0 0\n";
    if (strcmp(scenario, "parse-error") == 0)
        fixture = "/dev/vda / ext4 ro 0 0\nandroid /mnt/android";
    return fmemopen((void *)fixture, strlen(fixture), "r");
}
static int fake_open(const char *path, int flags) {
    if (strcmp(path, "/dev/hvc0") != 0
            || flags != (O_WRONLY | O_NOCTTY | O_CLOEXEC)) return -1;
    return strcmp(scenario, "console-error") == 0 ? -1 : 37;
}
static ssize_t fake_write(int fd, const void *message, size_t length) {
    return fd == 37 ? write(STDOUT_FILENO, message, length) : -1;
}
static int fake_close(int fd) { return fd == 37 ? 0 : -1; }
static void fake_sync(void) { sync_calls++; }
#define statvfs(...) fake_statvfs(__VA_ARGS__)
#define setmntent(...) fake_setmntent(__VA_ARGS__)
#define open(...) fake_open(__VA_ARGS__)
#define write(...) fake_write(__VA_ARGS__)
#define close(...) fake_close(__VA_ARGS__)
#define sync(...) fake_sync(__VA_ARGS__)
#define main shutdown_hook_main
#include "shutdown-check.c"
#undef main
int main(int argc, char **argv) {
    if (argc < 2) return 99;
    scenario = argv[1];
    int result = shutdown_hook_main(argc - 1, argv + 1);
    fprintf(stderr, "sync=%u stat=%u\n", sync_calls, stat_calls);
    return result;
}
''')
        subprocess.run(
            shlex.split(os.environ.get("CC", "cc"))
            + ["-std=c11", "-Wall", "-Wextra", "-Werror", "-I", str(HERE), str(source), "-o", str(cls.binary)],
            check=True,
        )

    def test_clean_markers_follow_sync_and_second_proof(self):
        for operation in ("poweroff", "halt", "reboot"):
            with self.subTest(operation=operation):
                result = subprocess.run([str(self.binary), "clean", operation], text=True, capture_output=True)
                self.assertEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "TERMUX_ARCH_SHARED_UNMOUNTED_V1\nTERMUX_ARCH_STOPPING_V2\n")
                self.assertEqual(result.stderr, "sync=1 stat=2\n")

    def test_failure_paths_never_emit_clean_markers(self):
        for scenario in ("rw", "changed", "share", "parse-error", "stat-error", "mounts-error", "console-error"):
            with self.subTest(scenario=scenario):
                result = subprocess.run([str(self.binary), scenario, "poweroff"], text=True, capture_output=True)
                self.assertEqual(result.returncode, 1)
                self.assertIn("TERMUX_ARCH_SHUTDOWN_FAILED:", result.stdout + result.stderr)
                self.assertNotIn("TERMUX_ARCH_STOPPING_V2", result.stdout + result.stderr)
                self.assertNotIn("TERMUX_ARCH_SHARED_UNMOUNTED_V1", result.stdout + result.stderr)
                expected_counts = "sync=1 stat=2" if scenario == "changed" else (
                    "sync=0 stat=0" if scenario == "console-error" else "sync=0 stat=1")
                self.assertIn(expected_counts, result.stderr)

    def test_unsupported_operation_fails_before_proof(self):
        result = subprocess.run([str(self.binary), "clean", "unknown"], text=True, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "TERMUX_ARCH_SHUTDOWN_FAILED: unsupported operation\n")
        self.assertEqual(result.stderr, "sync=0 stat=0\n")


if __name__ == "__main__":
    unittest.main()
