package com.termux.api.shizuku;

import java.io.File;

/** Explicit shared-storage export. Private Termux directories use SSHFS. */
public final class ArchVmSharedStorage {
    public static final String DEFAULT_PATH = "/storage/emulated/0/Download/AetherShared";
    public static final String GUEST_PATH = "/mnt/android";
    static final String TAG = "aether_shared";
    static final String MOUNTED = "TERMUX_ARCH_SHARED_MOUNTED_V1";
    static final String UNMOUNTED = "TERMUX_ARCH_SHARED_UNMOUNTED_V1";

    private ArchVmSharedStorage() {}

    /** Empty means disabled. Do not normalize traversal into an allowed path. */
    public static String parse(String value) {
        if (value == null || value.isEmpty()) return null;
        if (!value.startsWith("/storage/emulated/0/") || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096
                || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Select an Android shared-storage folder");
        for (String part : value.substring(1).split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Use an absolute canonical folder path");
        return value;
    }

    static void validateDirectory(String path) throws Exception {
        if (path == null) return;
        File directory = new File(parse(path));
        if (!directory.isDirectory() || !directory.getCanonicalPath().equals(path))
            throw new IllegalArgumentException("Shared folder must exist and contain no symlink components");
    }
}
