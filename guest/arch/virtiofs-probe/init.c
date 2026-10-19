#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/stat.h>
#include <unistd.h>

/* A static, diskless PID 1. Only the caller's disposable share is writable. */
static int mounted;

static _Noreturn void finish(int success) {
    if (mounted && umount("/share") != 0) {
        perror("unmount share");
        success = 0;
    }
    puts(success ? "AETHER_VIRTIOFS_PASS_V1" : "AETHER_VIRTIOFS_FAIL_V1");
    reboot(RB_POWER_OFF);
    perror("poweroff");
    /* Do not turn a successful I/O test into a PID 1 panic. Owner has a timeout. */
    for (;;) pause();
}

static _Noreturn void fail(const char *operation) {
    perror(operation);
    finish(0);
}

static void check_file(const char *path, const char *expected) {
    char data[128];
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) fail(path);
    size_t used = 0;
    for (;;) {
        ssize_t n = read(fd, data + used, sizeof(data) - used);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) fail("read sentinel");
        if (!n) break;
        used += (size_t)n;
        if (used == sizeof(data)) { errno = EFBIG; fail("sentinel too large"); }
    }
    if (close(fd)) fail("close sentinel");
    if (used != strlen(expected) || memcmp(data, expected, used)) {
        errno = EINVAL; fail("sentinel content mismatch");
    }
}

int main(void) {
    if (getpid() != 1) {
        fputs("Refusing: this probe must be guest PID 1\n", stderr);
        return 2;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    puts("AETHER_VIRTIOFS_BEGIN_V1");
    if (mount("aether_probe", "/share", "virtiofs", MS_NODEV | MS_NOSUID | MS_NOEXEC, NULL))
        fail("mount virtiofs");
    mounted = 1;
    check_file("/share/host.txt", "aether-host-v1\n");
    /* mkdir is exclusive: a reused or pre-populated probe directory fails closed. */
    if (mkdir("/share/guest", 0700)) fail("create guest directory");
    int fd = open("/share/guest/pending.txt", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    if (fd < 0) fail("create guest sentinel");
    const char content[] = "aether-guest-v1\n";
    size_t written = 0;
    while (written < sizeof(content) - 1) {
        ssize_t n = write(fd, content + written, sizeof(content) - 1 - written);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) fail("write guest sentinel");
        written += (size_t)n;
    }
    if (fsync(fd)) fail("fsync guest sentinel");
    if (close(fd)) fail("close guest sentinel");
    if (rename("/share/guest/pending.txt", "/share/guest/result.txt")) fail("rename guest sentinel");
    check_file("/share/guest/result.txt", content);
    puts("AETHER_VIRTIOFS_IO_OK_V1");
    finish(1);
}
