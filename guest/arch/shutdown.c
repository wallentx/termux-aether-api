#define _GNU_SOURCE
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/wait.h>
#include <unistd.h>

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
            if (mount(NULL, "/", NULL, MS_REMOUNT | MS_RDONLY, NULL) == 0) {
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
            perror("remount root read-only");
            puts("TERMUX_ARCH_SHUTDOWN_FAILED: root is still writable");
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
