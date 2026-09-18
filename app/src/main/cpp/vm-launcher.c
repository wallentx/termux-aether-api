// Fixed AVF launcher. The VM dies with its owning Java thread/process.
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char *end = NULL;
    long expected = argc == 2 ? strtol(argv[1], &end, 10) : 0;
    if (expected <= 1 || !end || *end || expected != getppid()) return 2;
    if (getuid() != 2000) return 3; // This backend intentionally requires shell Shizuku.
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != expected) return 4;
    umask(077);
    int lock = open("/data/local/tmp/termux-arch-v1/owner.lock", O_CREAT | O_RDWR | O_NOFOLLOW, 0600);
    struct stat st;
    if (lock < 0 || fstat(lock, &st) || !S_ISREG(st.st_mode) || st.st_uid != 2000 ||
        st.st_nlink != 1 || (st.st_mode & 077) || flock(lock, LOCK_EX | LOCK_NB)) {
        fputs("VM ownership lock unavailable\n", stderr);
        return 5;
    }
    // Keep the lock open through exec. Never accept user paths, flags, or shell text.
    execl("/apex/com.android.virt/bin/vm", "vm", "run",
          "/data/local/tmp/termux-arch-v1/config.json", (char *)NULL);
    perror("exec AVF vm");
    return 6;
}
