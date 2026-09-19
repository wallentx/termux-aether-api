#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/landlock.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

/* Exercise actual enforcement, not just a kernel config or version string. */
int main(void) {
    int abi = syscall(SYS_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (abi < 1) { perror("Landlock ABI"); return 1; }
    char directory[] = "/tmp/termux-landlock.XXXXXX";
    if (!mkdtemp(directory)) { perror("mkdtemp"); return 1; }
    char allowed[256], permitted[256], forbidden[256];
    snprintf(allowed, sizeof(allowed), "%s/allowed", directory);
    snprintf(permitted, sizeof(permitted), "%s/allowed/file", directory);
    snprintf(forbidden, sizeof(forbidden), "%s/forbidden", directory);
    if (mkdir(allowed, 0700)) { perror("mkdir"); rmdir(directory); return 1; }
    fflush(NULL);
    pid_t child = fork();
    if (child == 0) {
        struct landlock_ruleset_attr rules = {
            .handled_access_fs = LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_MAKE_REG
        };
        int rules_fd = syscall(SYS_landlock_create_ruleset, &rules, sizeof(rules), 0);
        int path_fd = open(allowed, O_PATH | O_CLOEXEC);
        struct landlock_path_beneath_attr path = {
            .allowed_access = rules.handled_access_fs, .parent_fd = path_fd
        };
        if (rules_fd < 0 || path_fd < 0 ||
            syscall(SYS_landlock_add_rule, rules_fd, LANDLOCK_RULE_PATH_BENEATH, &path, 0) ||
            prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
            syscall(SYS_landlock_restrict_self, rules_fd, 0)) {
            perror("Landlock restrict"); _exit(1);
        }
        close(path_fd);
        close(rules_fd);
        int fd = open(forbidden, O_WRONLY | O_CREAT | O_EXCL, 0600);
        if (fd != -1 || errno != EACCES) _exit(2);
        fd = open(permitted, O_WRONLY | O_CREAT | O_EXCL, 0600);
        if (fd < 0) _exit(3);
        close(fd);
        _exit(0);
    }
    int status = 0;
    int ok = child > 0 && waitpid(child, &status, 0) == child &&
             WIFEXITED(status) && WEXITSTATUS(status) == 0;
    unlink(permitted);
    unlink(forbidden);
    rmdir(allowed);
    rmdir(directory);
    if (!ok) { fprintf(stderr, "Landlock enforcement test failed\n"); return 1; }
    printf("Landlock ABI %d: allowed write succeeded; forbidden write denied\n", abi);
    return 0;
}
