package com.termux.api.shizuku;

import static org.junit.Assert.*;

import org.junit.Test;

public class ArchVmFileIdentityTest {
    @Test public void acceptsOpenedDescriptorForCurrentPath() {
        assertTrue(ArchVmFileIdentity.same(17, 42, 17, 42));
    }

    @Test public void rejectsReplacementInode() {
        assertFalse(ArchVmFileIdentity.same(17, 43, 17, 42));
    }

    @Test public void rejectsDifferentFilesystem() {
        assertFalse(ArchVmFileIdentity.same(18, 42, 17, 42));
    }
}
