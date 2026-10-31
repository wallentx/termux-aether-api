package com.termux.api.shizuku;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArchVmMemoryTest {
    @Test public void absentRequestUsesSavedPreference() {
        assertEquals(0, ArchVmMemory.parseRequest(null));
        assertEquals(8192, ArchVmMemory.DEFAULT_MIB);
        assertEquals(6144, ArchVmMemory.parseRequest("6144"));
    }

    @Test public void rejectsInvalidAndOverflowingRequests() {
        for (String value : new String[]{"", "0", "-1", "1.5", "8G", " 8192", "8192\n", "2147483648", "99999999999"}) {
            try { ArchVmMemory.parseRequest(value); fail("Accepted " + value); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void convertsAboveFourGiBWithoutIntegerOverflow() {
        assertEquals(8589934592L, ArchVmMemory.bytes(8192, 16L << 30));
        assertEquals(12L << 30, ArchVmMemory.bytes(12288, 12L << 30));
    }

    @Test public void rejectsNonpositiveAndAboveHostCapacity() {
        for (int value : new int[]{-1, 0, 16385, Integer.MAX_VALUE}) {
            try { ArchVmMemory.bytes(value, 16L << 30); fail("Accepted " + value); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
