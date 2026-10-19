package com.termux.api.shizuku;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/** Atomically creates and exclusively locks the fixed VM ownership file. */
final class ArchVmOwnerLock implements AutoCloseable {
    interface Validator { void validate(File file) throws Exception; }

    private final FileOutputStream stream;
    private final FileLock lock;

    private ArchVmOwnerLock(FileOutputStream stream, FileLock lock) {
        this.stream = stream;
        this.lock = lock;
    }

    static ArchVmOwnerLock acquire(File file, Validator validator) throws Exception {
        FileDescriptor descriptor;
        int flags = OsConstants.O_RDWR | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW;
        try {
            descriptor = Os.open(file.getPath(), flags | OsConstants.O_CREAT | OsConstants.O_EXCL, 0600);
        } catch (ErrnoException exists) {
            if (exists.errno != OsConstants.EEXIST) throw exists;
            // Keep the established inode: replacing it would split ownership locks.
            descriptor = Os.open(file.getPath(), flags, 0);
        }

        FileOutputStream stream;
        try { stream = new FileOutputStream(descriptor); }
        catch (Exception error) {
            Os.close(descriptor);
            throw error;
        }
        try {
            FileLock lock;
            try { lock = stream.getChannel().tryLock(); }
            catch (OverlappingFileLockException ownedHere) { lock = null; }
            if (lock == null) {
                stream.close();
                return null;
            }
            validator.validate(file);
            StructStat path = Os.lstat(file.getPath());
            StructStat opened = Os.fstat(descriptor);
            if (!ArchVmFileIdentity.same(path.st_dev, path.st_ino, opened.st_dev, opened.st_ino)
                    || opened.st_uid != 2000 || (opened.st_mode & 0077) != 0
                    || !OsConstants.S_ISREG(opened.st_mode) || opened.st_nlink != 1
                    || opened.st_size != 0) {
                throw new SecurityException("Invalid opened ownership file");
            }
            return new ArchVmOwnerLock(stream, lock);
        } catch (Exception error) {
            stream.close();
            throw error;
        }
    }

    @Override public void close() {
        try { lock.release(); } catch (Exception ignored) { }
        try { stream.close(); } catch (Exception ignored) { }
    }
}
