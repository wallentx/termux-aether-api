package com.termux.api.shizuku;

import java.io.File;
import java.io.RandomAccessFile;

/** Sparse growth only; callers must validate ownership and hold the VM owner lock. */
final class ArchVmDisk {
    static void grow(File image, long bytes) throws Exception {
        try (RandomAccessFile disk = new RandomAccessFile(image, "rw")) {
            if (bytes < disk.length() || bytes <= 0 || bytes % (1024 * 1024) != 0)
                throw new IllegalArgumentException("disk_size_must_grow_in_whole_mib");
            disk.setLength(bytes);
            disk.getFD().sync();
        }
    }
}
