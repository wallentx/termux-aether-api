import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.*;
import java.util.*;

/** Disposable filesystem tests. No Android paths or guest disks are used. */
public final class KernelUpgradeTest {
    interface Checked { void run() throws Exception; }
    static int checks;
    static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
    static void rejects(Checked task) throws Exception {
        try { task.run(); } catch (IOException | OverlappingFileLockException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
    static void privateFile(Path path, byte[] content) throws Exception {
        Files.write(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
    static String id(String report) { return report.split("\"backup_id\":\"")[1].split("\"")[0]; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("kernel-upgrade-test-");
        Path base = Files.createDirectory(root.resolve("base"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path stage = Files.createDirectory(root.resolve("stage"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        int uid = ((Number) Files.getAttribute(base, "unix:uid")).intValue();
        try {
            byte[] old = new byte[4096];
            System.arraycopy(new byte[]{65, 82, 77, 100}, 0, old, 56, 4);
            byte[] next = old.clone(); next[123] = 99;
            privateFile(base.resolve("Image"), old);
            privateFile(base.resolve("owner.lock"), new byte[0]);
            byte[] disk = "disk must survive byte for byte".getBytes("UTF-8");
            privateFile(base.resolve("arch-rootfs.img"), disk);
            Object diskInode = Files.getAttribute(base.resolve("arch-rootfs.img"), "unix:ino");
            privateFile(stage.resolve("Image"), next);
            String before = KernelUpgrade.digest(base.resolve("Image"));
            String after = KernelUpgrade.digest(stage.resolve("Image"));
            String commit = "0123456789012345678901234567890123456789";
            Object imageInode = Files.getAttribute(base.resolve("Image"), "unix:ino");
            Object ownerInode = Files.getAttribute(base.resolve("owner.lock"), "unix:ino");
            Path selectedMode = base.resolve("init-mode");
            check(KernelUpgrade.readBootMode(base, uid).equals("systemd"));
            rejects(() -> KernelUpgrade.readBootMode(base, uid + 1));
            for (String value : new String[]{"legacy", "legacy\n", "systemd", "systemd\n"}) {
                privateFile(selectedMode, value.getBytes("US-ASCII"));
                check(KernelUpgrade.readBootMode(base, uid).equals(value.trim()));
            }
            for (String bad : new String[]{"", "legacy quiet", "init=/bin/sh", "LEGACY", " legacy",
                    "legacy\r\n", "legacy\n\n", "legacy\0", "legacy\u00e9"}) {
                privateFile(selectedMode, bad.getBytes("UTF-8"));
                rejects(() -> KernelUpgrade.readBootMode(base, uid));
                rejects(() -> KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "legacy", uid));
            }
            Files.delete(selectedMode);
            String recovery = KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "legacy", uid);
            check(recovery.contains("\"before_mode\":\"systemd\""));
            check(recovery.contains("\"boot_mode\":\"legacy\""));
            check(recovery.contains("/usr/local/sbin/termux-vm-legacy-init"));
            check(Files.getPosixFilePermissions(selectedMode).equals(PosixFilePermissions.fromString("rw-------")));
            check(KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "systemd", uid)
                    .contains("\"init_path\":\"/usr/local/sbin/termux-vm-init\""));
            for (String bad : new String[]{"", "legacy\n", "init=/bin/sh", "Legacy", "systemd quiet"})
                rejects(() -> KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", bad, uid));
            try (RandomAccessFile owner = new RandomAccessFile(base.resolve("owner.lock").toFile(), "rw");
                 FileLock lock = owner.getChannel().lock()) {
                rejects(() -> KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "legacy", uid));
            }
            check(KernelUpgrade.readBootMode(base, uid).equals("systemd"));
            Files.setPosixFilePermissions(selectedMode, PosixFilePermissions.fromString("rw-r--r--"));
            rejects(() -> KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "legacy", uid));
            Files.delete(selectedMode);
            Files.createSymbolicLink(selectedMode, base.resolve("missing"));
            rejects(() -> KernelUpgrade.readBootMode(base, uid));
            rejects(() -> KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "legacy", uid));
            Files.delete(selectedMode);
            privateFile(base.resolve("saved-init-mode"), "systemd\n".getBytes("US-ASCII"));
            Files.createSymbolicLink(selectedMode, base.resolve("saved-init-mode"));
            rejects(() -> KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "legacy", uid));
            Files.delete(selectedMode);
            try {
                Files.createLink(selectedMode, base.resolve("saved-init-mode"));
                rejects(() -> KernelUpgrade.readBootMode(base, uid));
                rejects(() -> KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "legacy", uid));
                Files.delete(selectedMode);
            } catch (AccessDeniedException | UnsupportedOperationException unavailable) {
                System.out.println("SKIP: host filesystem denies hardlink creation");
            }
            Files.createDirectory(selectedMode);
            rejects(() -> KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "legacy", uid));
            Files.delete(selectedMode);
            // Recovery selects init even when the artifacts cannot be opened.
            Files.move(base.resolve("Image"), base.resolve("Image.unavailable"));
            Files.move(base.resolve("arch-rootfs.img"), base.resolve("disk.unavailable"));
            KernelUpgrade.operate(base, stage, "boot-mode", "-", "-", "legacy", uid);
            Files.move(base.resolve("Image.unavailable"), base.resolve("Image"));
            Files.move(base.resolve("disk.unavailable"), base.resolve("arch-rootfs.img"));
            check(Arrays.equals(old, Files.readAllBytes(base.resolve("Image"))));
            check(Arrays.equals(disk, Files.readAllBytes(base.resolve("arch-rootfs.img"))));
            check(imageInode.equals(Files.getAttribute(base.resolve("Image"), "unix:ino")));
            check(diskInode.equals(Files.getAttribute(base.resolve("arch-rootfs.img"), "unix:ino")));
            check(ownerInode.equals(Files.getAttribute(base.resolve("owner.lock"), "unix:ino")));
            Checked install = () -> KernelUpgrade.operate(base, stage, "install", before, after, commit, uid);
            rejects(() -> KernelUpgrade.operate(base, stage, "install", after, after, commit, uid));
            rejects(() -> KernelUpgrade.operate(base, stage, "install", before, before, commit, uid));
            rejects(() -> KernelUpgrade.operate(base, stage, "rollback", before, "-", "../escape", uid));
            try (RandomAccessFile owner = new RandomAccessFile(base.resolve("owner.lock").toFile(), "rw");
                 FileLock lock = owner.getChannel().lock()) { rejects(install); }
            Files.setPosixFilePermissions(stage.resolve("Image"), PosixFilePermissions.fromString("rw-r--r--"));
            rejects(install);
            Files.setPosixFilePermissions(stage.resolve("Image"), PosixFilePermissions.fromString("rw-------"));
            Files.move(stage.resolve("Image"), stage.resolve("real"));
            Files.createSymbolicLink(stage.resolve("Image"), stage.resolve("real"));
            rejects(install);
            Files.delete(stage.resolve("Image"));
            Files.move(stage.resolve("real"), stage.resolve("Image"));
            check(Arrays.equals(old, Files.readAllBytes(base.resolve("Image"))));
            String installed = KernelUpgrade.operate(base, stage, "install", before, after, commit, uid);
            String backup = id(installed);
            check(backup.matches("[0-9a-f]{32}"));
            check(Arrays.equals(next, Files.readAllBytes(base.resolve("Image"))));
            check(Arrays.equals(old, Files.readAllBytes(base.resolve("kernel-backups").resolve(backup).resolve("Image"))));
            check(KernelUpgrade.operate(base, stage, "install", after, after, commit, uid).contains("unchanged"));
            String restored = KernelUpgrade.operate(base, stage, "rollback", after, "-", backup, uid);
            check(Arrays.equals(old, Files.readAllBytes(base.resolve("Image"))));
            check(Arrays.equals(next, Files.readAllBytes(base.resolve("kernel-backups").resolve(id(restored)).resolve("Image"))));
            // Corrupt backup must not be used, even with the correct saved checksum.
            Path saved = base.resolve("kernel-backups").resolve(id(restored)).resolve("Image");
            privateFile(saved, old);
            rejects(() -> KernelUpgrade.operate(base, stage, "rollback", before, "-", id(restored), uid));
            check(Arrays.equals(disk, Files.readAllBytes(base.resolve("arch-rootfs.img"))));
            check(diskInode.equals(Files.getAttribute(base.resolve("arch-rootfs.img"), "unix:ino")));
            // ARM64 Image header validation rejects a correctly hashed non-kernel.
            privateFile(stage.resolve("Image"), new byte[4096]);
            rejects(() -> KernelUpgrade.operate(base, stage, "install", before,
                    KernelUpgrade.digest(stage.resolve("Image")), commit, uid));
            check(Arrays.equals(old, Files.readAllBytes(base.resolve("Image"))));
            System.out.println("PASS: " + checks + " kernel maintenance assertions");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
