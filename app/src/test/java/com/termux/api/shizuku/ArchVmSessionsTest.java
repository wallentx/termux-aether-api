package com.termux.api.shizuku;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArchVmSessionsTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test public void waitsForLastSessionAndLastSshConnection() {
        ArchVmSessions sessions = new ArchVmSessions();
        assertFalse(sessions.shouldIdle(0));
        assertEquals("acquired", sessions.update("acquire", A, false, 0));
        sessions.update("acquire", B, false, 0);
        sessions.update("release", A, false, 1);
        assertFalse(sessions.shouldIdle(0));
        sessions.update("release", B, false, 2);
        assertFalse(sessions.shouldIdle(1));
        assertTrue(sessions.shouldIdle(0));
        assertFalse(sessions.keepMemory());
    }

    @Test public void retentionAppliesToBusyPeriodAndResetsForNextUse() {
        ArchVmSessions sessions = new ArchVmSessions();
        sessions.update("acquire", A, true, 0);
        sessions.update("acquire", B, false, 0);
        sessions.update("release", A, false, 1);
        sessions.update("release", B, false, 2);
        assertTrue(sessions.keepMemory());
        sessions.update("acquire", A, false, 3);
        sessions.update("release", A, false, 4);
        assertFalse(sessions.keepMemory());
    }

    @Test public void deadClientExpiresButActiveSshStillProtectsGuest() {
        ArchVmSessions sessions = new ArchVmSessions();
        sessions.update("acquire", A, false, 100);
        assertEquals("renewed", sessions.update("renew", A, false, 200));
        sessions.expire(100 + ArchVmSessions.LEASE_MILLIS);
        assertEquals(1, sessions.count());
        sessions.expire(200 + ArchVmSessions.LEASE_MILLIS);
        assertEquals(0, sessions.count());
        assertFalse(sessions.shouldIdle(1));
        assertTrue(sessions.shouldIdle(0));
        assertEquals("expired", sessions.update("renew", A, false, 100000));
    }

    @Test public void rejectsInvalidTokensAndBoundsActiveLeases() {
        ArchVmSessions sessions = new ArchVmSessions();
        for (String token : new String[]{null, "", "../file", A + "\n"})
            assertEquals("invalid_token", sessions.update("acquire", token, false, 0));
        for (int i = 0; i < 64; i++)
            assertEquals("acquired", sessions.update("acquire", String.format("%032x", i), false, 0));
        assertEquals("session_limit", sessions.update("acquire", A, false, 0));
        sessions.clear();
        assertEquals(0, sessions.count());
        assertFalse(sessions.shouldIdle(0));
    }
}
