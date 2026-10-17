package com.termux.api.shizuku;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/** Host-owned, allowlisted init selection, read while holding the VM owner lock. */
final class ArchVmBootMode {
    static final String SYSTEMD_INIT = "/usr/local/sbin/termux-vm-init";
    static final String LEGACY_INIT = "/usr/local/sbin/termux-vm-legacy-init";
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    private ArchVmBootMode() { }

    static String initPath(File base) throws IOException {
        return initPath(base, 2000);
    }

    static String initPath(File base, int ownerUid) throws IOException {
        return "legacy".equals(readMode(base, ownerUid)) ? LEGACY_INIT : SYSTEMD_INIT;
    }

    static String readMode(File base, int ownerUid) throws IOException {
        validate(base.toPath(), true, ownerUid);
        Path file = base.toPath().resolve("init-mode");
        // NOFOLLOW also detects dangling links, which must not become defaults.
        if (Files.notExists(file, NOFOLLOW)) return "systemd";
        validate(file, false, ownerUid);
        byte[] content = new byte[9];
        int length = 0;
        try (InputStream input = Files.newInputStream(file, NOFOLLOW)) {
            int next;
            while (length < content.length && (next = input.read()) != -1)
                content[length++] = (byte) next;
        }
        if (length < 1 || length > 8) throw new IOException("Invalid init-mode size");
        String value = new String(content, 0, length, StandardCharsets.US_ASCII);
        if ("systemd".equals(value) || "systemd\n".equals(value)) return "systemd";
        if ("legacy".equals(value) || "legacy\n".equals(value)) return "legacy";
        throw new IOException("Invalid init-mode: expected systemd or legacy");
    }

    private static void validate(Path path, boolean directory, int uid) throws IOException {
        if (!path.toAbsolutePath().equals(path.toRealPath()))
            throw new IOException("Noncanonical init-mode path");
        Map<String, Object> stat = Files.readAttributes(path, "unix:uid,mode,nlink", NOFOLLOW);
        if (((Number) stat.get("uid")).intValue() != uid
                || (((Number) stat.get("mode")).intValue() & 0077) != 0
                || (directory ? !Files.isDirectory(path, NOFOLLOW)
                : !Files.isRegularFile(path, NOFOLLOW) || ((Number) stat.get("nlink")).intValue() != 1))
            throw new IOException("Unsafe init-mode owner, permissions or type");
    }
}
