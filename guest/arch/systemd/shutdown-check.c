#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <mntent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/statvfs.h>
#include <unistd.h>

/* A static system-shutdown hook: systemd has already attempted filesystem
 * cleanup. Prove its result; never claim that an attempted cleanup succeeded.
 * This executable must not mount, unmount, kill processes, or reboot. */
enum proof_result {
    PROOF_OK,
    ROOT_STAT_FAILED,
    ROOT_WRITABLE,
    MOUNTS_UNREADABLE,
    MOUNT_PARSE_FAILED,
    ROOT_NOT_LISTED,
    SHARE_MOUNTED
};

static int is_shared_mount(const char *path) {
    const char base[] = "/mnt/android";
    size_t length = sizeof(base) - 1;
    return strncmp(path, base, length) == 0
        && (path[length] == '\0' || path[length] == '/');
}

/* getmntent skips malformed records and accepts missing numeric fields. Check
 * each complete kernel-format record first, so a truncated/unreadable listing
 * cannot be mistaken for proof that the share is absent. */
static int valid_mount_record(const char *line, size_t length) {
    if (length == 0 || line[length - 1] != '\n'
            || memchr(line, '\0', length) != NULL) return 0;
    const char *cursor = line;
    for (int field = 0; field < 6; field++) {
        while (*cursor == ' ' || *cursor == '\t') cursor++;
        const char *start = cursor;
        while (*cursor != '\0' && !isspace((unsigned char)*cursor)) {
            if (field >= 4 && !isdigit((unsigned char)*cursor)) return 0;
            cursor++;
        }
        if (cursor == start) return 0;
    }
    while (*cursor == ' ' || *cursor == '\t') cursor++;
    return cursor[0] == '\n' && cursor[1] == '\0';
}

/* Only supplied filesystem flags and supplied mount records are inspected.
 * Keeping this proof separate lets host tests exercise it without shutdown. */
static enum proof_result shutdown_proof(unsigned long root_flags, FILE *mounts) {
    if (!(root_flags & ST_RDONLY)) return ROOT_WRITABLE;
    if (mounts == NULL) return MOUNTS_UNREADABLE;
    char *line = NULL;
    size_t capacity = 0;
    ssize_t length;
    int saw_root = 0;
    enum proof_result result = PROOF_OK;
    while ((length = getline(&line, &capacity, mounts)) >= 0) {
        if (length >= INT_MAX || !valid_mount_record(line, (size_t)length)) {
            result = MOUNT_PARSE_FAILED;
            break;
        }
        FILE *record = fmemopen(line, (size_t)length, "r");
        char *buffer = malloc((size_t)length + 1);
        struct mntent entry;
        if (record == NULL || buffer == NULL
                || getmntent_r(record, &entry, buffer, (int)length + 1) == NULL) {
            result = MOUNT_PARSE_FAILED;
        } else if (entry.mnt_dir[0] != '/') {
            result = MOUNT_PARSE_FAILED;
        } else if (is_shared_mount(entry.mnt_dir)) {
            result = SHARE_MOUNTED;
        } else if (strcmp(entry.mnt_dir, "/") == 0) {
            saw_root = 1;
        }
        free(buffer);
        if (record != NULL) fclose(record);
        if (result != PROOF_OK) break;
    }
    if (result == PROOF_OK && (!feof(mounts) || ferror(mounts))) {
        result = MOUNTS_UNREADABLE;
    }
    free(line);
    if (result == PROOF_OK && !saw_root) return ROOT_NOT_LISTED;
    return result;
}

static enum proof_result current_shutdown_proof(void) {
    struct statvfs root;
    if (statvfs("/", &root) != 0) return ROOT_STAT_FAILED;
    FILE *mounts = setmntent("/proc/mounts", "r");
    enum proof_result result = shutdown_proof(root.f_flag, mounts);
    if (mounts != NULL) endmntent(mounts);
    return result;
}

static int write_all(int fd, const char *message) {
    size_t remaining = strlen(message);
    while (remaining > 0) {
        ssize_t written = write(fd, message, remaining);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return -1;
        message += written;
        remaining -= (size_t)written;
    }
    return 0;
}

int main(int argc, char **argv) {
    int console = open("/dev/hvc0", O_WRONLY | O_NOCTTY | O_CLOEXEC);
    if (console < 0) {
        fputs("TERMUX_ARCH_SHUTDOWN_FAILED: cannot open console\n", stderr);
        return 1;
    }
    /* systemd v262's executor rename clears the saved argv storage backing
     * its shutdown verb. Accept that observed empty verb only at the normal
     * arity; the filesystem proof below remains mandatory for every case. */
    if (argc != 2 || (argv[1][0] != '\0' && strcmp(argv[1], "poweroff") != 0
            && strcmp(argv[1], "halt") != 0 && strcmp(argv[1], "reboot") != 0)) {
        write_all(console, "TERMUX_ARCH_SHUTDOWN_FAILED: unsupported operation\n");
        close(console);
        return 1;
    }
    enum proof_result proof = current_shutdown_proof();
    if (proof == PROOF_OK) {
        sync();
        /* Check again after flushing, immediately before declaring success. */
        proof = current_shutdown_proof();
    }
    int result;
    if (proof == PROOF_OK) {
        result = write_all(console, "TERMUX_ARCH_SHARED_UNMOUNTED_V1\n"
                                   "TERMUX_ARCH_STOPPING_V2\n");
    } else {
        write_all(console, "TERMUX_ARCH_SHUTDOWN_FAILED: filesystem proof incomplete\n");
        result = -1;
    }
    close(console);
    return result == 0 ? 0 : 1;
}
