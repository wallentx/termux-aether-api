package com.termux.api.shizuku;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArchVmSharedStorageTest {
    @Test public void disabledByDefaultAndAllowsExplicitExternalFolders() {
        assertNull(ArchVmSharedStorage.parse(null));
        assertNull(ArchVmSharedStorage.parse(""));
        assertEquals(ArchVmSharedStorage.DEFAULT_PATH, ArchVmSharedStorage.parse(ArchVmSharedStorage.DEFAULT_PATH));
        assertEquals("/storage/emulated/0/Documents/my files", ArchVmSharedStorage.parse("/storage/emulated/0/Documents/my files"));
    }

    @Test public void rejectsPrivatePathsAndTraversal() {
        for (String value : new String[]{"/data/data/com.termux/files/home", "/storage/emulated/0/../1/Download",
                "/storage/emulated/0/Download/./folder", "/storage/emulated/0/Download//folder",
                "/storage/emulated/0/Download/", "/storage/emulated/0/Download\n", "relative"}) {
            try { ArchVmSharedStorage.parse(value); fail(value); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
