package com.termux.api.shizuku;

import java.io.File;
import java.io.RandomAccessFile;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArchVmDiskTest {
    @Test public void sparseGrowthPreservesBytesAndCanBeRetried() throws Exception {
        File image = File.createTempFile("arch-disk", ".img");
        try {
            try (RandomAccessFile file = new RandomAccessFile(image, "rw")) {
                file.setLength(1024 * 1024);
                file.writeUTF("existing-rootfs");
                file.seek(1024 * 1024 - 1);
                file.write(42);
            }
            ArchVmDisk.grow(image, 2 * 1024 * 1024);
            ArchVmDisk.grow(image, 2 * 1024 * 1024);
            try (RandomAccessFile file = new RandomAccessFile(image, "r")) {
                assertEquals(2 * 1024 * 1024, file.length());
                assertEquals("existing-rootfs", file.readUTF());
                file.seek(1024 * 1024 - 1);
                assertEquals(42, file.read());
                assertEquals(0, file.read());
            }
            for (long invalid : new long[]{0, -1, 1024 * 1024, 2 * 1024 * 1024 + 1}) {
                try { ArchVmDisk.grow(image, invalid); fail("Accepted invalid growth"); }
                catch (IllegalArgumentException expected) { }
                assertEquals(2 * 1024 * 1024, image.length());
            }
        } finally { image.delete(); }
    }
}
