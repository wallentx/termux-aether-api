import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Kernel-only maintenance under the same POSIX record lock as ArchVmUserService. */
public final class KernelUpgrade {
    static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    static final Path BASE = Paths.get("/data/local/tmp/termux-arch-v2");

    static void validate(Path path, boolean directory, int uid) throws Exception {
        if (!path.toAbsolutePath().equals(path.toRealPath()))
            throw new IOException("Symlink or noncanonical path: " + path);
        Map<String, Object> st = Files.readAttributes(path, "unix:uid,mode,nlink", NOFOLLOW);
        if (((Number) st.get("uid")).intValue() != uid
                || (((Number) st.get("mode")).intValue() & 0077) != 0
                || (directory ? !Files.isDirectory(path, NOFOLLOW)
                : !Files.isRegularFile(path, NOFOLLOW) || ((Number) st.get("nlink")).intValue() != 1))
            throw new IOException("Unsafe owner, permissions or file type: " + path);
    }

    static String digest(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) != -1) md.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : md.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    static void image(Path path, String expected, int uid) throws Exception {
        validate(path, false, uid);
        long size = Files.size(path);
        if (size < 4096 || size > 256L * 1024 * 1024)
            throw new IOException("Invalid Image size");
        try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
            input.seek(56);
            if (input.readInt() != 0x41524d64) throw new IOException("Not an ARM64 Linux Image");
        }
        if (!expected.matches("[0-9a-f]{64}") || !digest(path).equals(expected))
            throw new IOException("Image checksum mismatch");
    }

    static void syncDirectory(Path path) throws Exception {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
    }

    static void copyDurable(Path source, Path destination) throws Exception {
        // CREATE_NEW: a crash can leave a partial snapshot but cannot overwrite one.
        try (InputStream input = Files.newInputStream(source);
             FileChannel out = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rw-------"));
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                while (bytes.hasRemaining()) out.write(bytes);
            }
            out.force(true);
        }
    }

    static void writeDurable(Path destination, String text) throws Exception {
        try (FileChannel out = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rw-------"));
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes("UTF-8"));
            while (bytes.hasRemaining()) out.write(bytes);
            out.force(true);
        }
    }

    static String operate(Path base, Path stage, String mode, String expectedCurrent,
                          String expectedCandidate, String source, int uid) throws Exception {
        validate(base, true, uid);
        validate(stage, true, uid);
        Path owner = base.resolve("owner.lock");
        // Never create or replace the service lock: both owners must lock the same inode.
        validate(owner, false, uid);
        try (RandomAccessFile file = new RandomAccessFile(owner.toFile(), "rw");
             FileLock lock = file.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Arch is owned; stop it before kernel maintenance");
            Path current = base.resolve("Image");
            validate(current, false, uid);
            String before = digest(current);
            image(current, before, uid);
            // Only stat the disk. Never open, copy, truncate or replace it.
            Path disk = base.resolve("arch-rootfs.img");
            validate(disk, false, uid);
            Map<String, Object> diskStat = Files.readAttributes(disk, "unix:ino,dev,size", NOFOLLOW);
            if (mode.equals("inspect")) return report(mode, before, before, "", diskStat);
            if (!mode.equals("install") && !mode.equals("rollback")) throw new IOException("Invalid operation");
            if (!before.equals(expectedCurrent)) throw new IOException("Installed kernel changed; inspect again");
            Path backups = base.resolve("kernel-backups");
            if (!Files.exists(backups, NOFOLLOW))
                Files.createDirectory(backups, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            validate(backups, true, uid);
            Path candidate;
            String wanted;
            if (mode.equals("install")) {
                if (!source.matches("[0-9a-f]{40}")) throw new IOException("Full source commit required");
                candidate = stage.resolve("Image");
                wanted = expectedCandidate;
            } else {
                if (!source.matches("[0-9a-f]{32}")) throw new IOException("Invalid backup ID");
                Path saved = backups.resolve(source);
                validate(saved, true, uid);
                Path checksum = saved.resolve("Image.sha256");
                validate(checksum, false, uid);
                if (Files.size(checksum) != 65) throw new IOException("Invalid backup checksum");
                wanted = new String(Files.readAllBytes(checksum), "US-ASCII").trim();
                candidate = saved.resolve("Image");
            }
            image(candidate, wanted, uid);
            if (wanted.equals(before)) return report("unchanged", before, before, "", diskStat);
            String id = UUID.randomUUID().toString().replace("-", "");
            Path backup = backups.resolve(id);
            Files.createDirectory(backup, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            copyDurable(current, backup.resolve("Image"));
            image(backup.resolve("Image"), before, uid);
            writeDurable(backup.resolve("Image.sha256"), before + "\n");
            writeDurable(backup.resolve("transaction.txt"), "operation=" + mode + "\nsource=" + source
                    + "\nbefore=" + before + "\nafter=" + wanted + "\n");
            syncDirectory(backup);
            syncDirectory(backups);
            // Persist snapshot and its directory before making the candidate visible.
            syncDirectory(base);
            Path pending = base.resolve("Image.pending-" + id);
            copyDurable(candidate, pending);
            image(pending, wanted, uid);
            Files.move(pending, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(base);
            if (!digest(current).equals(wanted)) throw new IOException("Post-install verification failed");
            return report(mode, before, wanted, id, diskStat);
        }
    }

    static String report(String mode, String before, String after, String backup, Map<String, Object> disk) {
        return "{\"operation\":\"" + mode + "\",\"before_sha256\":\"" + before
                + "\",\"image_sha256\":\"" + after + "\",\"backup_id\":\"" + backup
                + "\",\"disk_inode\":" + disk.get("ino") + ",\"disk_device\":" + disk.get("dev")
                + ",\"disk_bytes\":" + disk.get("size") + "}";
    }

    public static void main(String[] args) {
        try { run(args); }
        catch (Exception error) {
            // app_process sends uncaught exceptions to logcat and may only print
            // "Killed" to the caller. Keep the actionable failure in our log.
            error.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("MODE STAGE CURRENT_SHA CANDIDATE_SHA SOURCE");
        int uid = (Integer) Class.forName("android.os.Process").getMethod("myUid").invoke(null);
        if (uid != 2000) throw new SecurityException("Shell Shizuku required");
        if (!args[1].matches("/data/local/tmp/aether-kernel-[0-9a-f]{32}"))
            throw new SecurityException("Invalid maintenance staging directory");
        Path stage = Paths.get(args[1]);
        String result = operate(BASE, stage, args[0], args[2], args[3], args[4], uid);
        writeDurable(stage.resolve("result.json"), result + "\n");
    }
}
