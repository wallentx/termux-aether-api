#define _GNU_SOURCE
#include <signal.h>
#include <mntent.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/wait.h>
#include <unistd.h>

/* Flush happens before this call. Never detach a busy share lazily. */
static int unmount_shared_storage(void) {
    FILE *mounts = setmntent("/proc/mounts", "r");
    if (mounts == NULL) return -1;
    struct mntent *entry;
    int mounted = 0;
    while ((entry = getmntent(mounts)) != NULL) {
        if (strcmp(entry->mnt_dir, "/mnt/android") == 0
                && strcmp(entry->mnt_type, "virtiofs") == 0) mounted = 1;
    }
    endmntent(mounts);
    if (!mounted) return 0;
    if (umount("/mnt/android") != 0) return -1;
    puts("TERMUX_ARCH_SHARED_UNMOUNTED_V1");
    return 0;
}

/* Static PID 1, executed from tmpfs: release the old shell/script/library inodes
 * before remounting root. Package updates may have unlinked any of them. */
int main(void) {
    if (getpid() != 1) {
        fputs("Only guest PID 1 may shut down the workspace\n", stderr);
        return 1;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    close_range(3, ~0U, 0);
    for (;;) {
        kill(-1, SIGTERM);
        sleep(1);
        kill(-1, SIGKILL);
        sync();
        int mounted = 0;
        for (int attempt = 0; attempt < 20; attempt++) {
            while (waitpid(-1, NULL, WNOHANG) > 0) { }
            if (unmount_shared_storage() == 0
                    && mount(NULL, "/", NULL, MS_REMOUNT | MS_RDONLY, NULL) == 0) {
                mounted = 1;
                break;
            }
            usleep(100000);
        }
        if (mounted) {
            puts("TERMUX_ARCH_STOPPING_V2");
            reboot(RB_POWER_OFF);
            perror("poweroff");
        } else {
            perror("unmount share or remount root read-only");
            puts("TERMUX_ARCH_SHUTDOWN_FAILED: filesystem shutdown incomplete");
        }
        /* Never panic PID 1 or force poweroff after a failed remount. Allow the
         * existing fixed console operation to retry, without a host shell. */
        char command[64];
        for (;;) {
            if (fgets(command, sizeof(command), stdin)) {
                if (strcmp(command, "poweroff\n") == 0) break;
            } else {
                clearerr(stdin);
                sleep(1);
            }
        }
    }
}
