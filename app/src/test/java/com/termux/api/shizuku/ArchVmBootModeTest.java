package com.termux.api.shizuku;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import static org.junit.Assert.*;

public class ArchVmBootModeTest {
    private interface Check { void run(Path base, int uid) throws Exception; }

    private void withBase(Check check) throws Exception {
        Path base = Files.createTempDirectory("arch-boot-mode-");
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
        int uid = ((Number) Files.getAttribute(base, "unix:uid")).intValue();
        try {
            check.run(base, uid);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(base)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.delete(path);
            }
        }
    }

    private void mode(Path base, String text) throws IOException {
        Files.write(base.resolve("init-mode"), text.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(base.resolve("init-mode"), PosixFilePermissions.fromString("rw-------"));
    }

    private void reject(Path base, int uid) throws Exception {
        try {
            ArchVmBootMode.readMode(base.toFile(), uid);
            fail("Accepted unsafe boot mode");
        } catch (IOException expected) { }
    }

    @Test public void missingModeAndAllowlistedValues() throws Exception {
        withBase((base, uid) -> {
            assertEquals("systemd", ArchVmBootMode.readMode(base.toFile(), uid));
            assertEquals(ArchVmBootMode.SYSTEMD_INIT, ArchVmBootMode.initPath(base.toFile(), uid));
            for (String value : new String[]{"legacy", "legacy\n", "systemd", "systemd\n"}) {
                mode(base, value);
                assertEquals(value.trim(), ArchVmBootMode.readMode(base.toFile(), uid));
                assertEquals(value.startsWith("legacy") ? ArchVmBootMode.LEGACY_INIT : ArchVmBootMode.SYSTEMD_INIT,
                        ArchVmBootMode.initPath(base.toFile(), uid));
            }
            assertEquals("/usr/local/sbin/termux-vm-init", ArchVmBootMode.SYSTEMD_INIT);
            assertEquals("/usr/local/sbin/termux-vm-legacy-init", ArchVmBootMode.LEGACY_INIT);
        });
    }

    @Test public void rejectsCorruptionAndKernelArguments() throws Exception {
        withBase((base, uid) -> {
            for (String value : new String[]{"", "init=/bin/sh", "legacy quiet", "legacy\r\n",
                    "LEGACY", " legacy", "legacy\n\n", "legacy\0", "legacy\u00e9"}) {
                mode(base, value);
                reject(base, uid);
            }
        });
    }

    @Test public void rejectsOwnerPermissionsAndFileType() throws Exception {
        withBase((base, uid) -> {
            reject(base, uid + 1);
            mode(base, "legacy");
            reject(base, uid + 1);
            Files.setPosixFilePermissions(base.resolve("init-mode"), PosixFilePermissions.fromString("rw-r--r--"));
            reject(base, uid);
            Files.delete(base.resolve("init-mode"));
            Files.createDirectory(base.resolve("init-mode"));
            reject(base, uid);
            Files.delete(base.resolve("init-mode"));
            Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwxr-xr-x"));
            reject(base, uid);
        });
    }

    @Test public void rejectsSymlinks() throws Exception {
        withBase((base, uid) -> {
            Path selected = base.resolve("init-mode");
            Files.createSymbolicLink(selected, base.resolve("missing"));
            reject(base, uid);
            Files.delete(selected);
            mode(base, "legacy");
            Files.move(selected, base.resolve("target"));
            Files.createSymbolicLink(selected, base.resolve("target"));
            reject(base, uid);
            Path baseAlias = base.resolve("base-alias");
            Files.createSymbolicLink(baseAlias, base);
            reject(baseAlias, uid);
        });
    }

    @Test public void rejectsHardlinks() throws Exception {
        withBase((base, uid) -> {
            mode(base, "legacy");
            try {
                Files.createLink(base.resolve("alias"), base.resolve("init-mode"));
            } catch (java.nio.file.AccessDeniedException | UnsupportedOperationException unavailable) {
                org.junit.Assume.assumeNoException("Host filesystem denies hardlink creation", unavailable);
            }
            reject(base, uid);
        });
    }
}
