package com.termux.api.shizuku;

import static org.junit.Assert.*;

import org.junit.Test;

public class ArchVmShutdownAttemptTest {
    @Test public void idleClaimStartsExactlyOneAttempt() {
        ArchVmShutdownAttempt state = new ArchVmShutdownAttempt();
        assertFalse(state.blocksDuplicateStop(true));
        long claimed = state.begin();
        assertTrue(state.pending());
        assertTrue(state.blocksDuplicateStop(true));
        assertEquals(claimed, state.begin());
    }

    @Test public void currentFailureMarkerCanSpanConsoleReads() {
        ArchVmShutdownAttempt state = new ArchVmShutdownAttempt();
        long attempt = state.begin();
        assertFalse(state.append("TERMUX_ARCH_SHUT"));
        assertTrue(state.append("DOWN_FAILED: filesystem shutdown incomplete\n"));
        assertTrue(state.failed(attempt));
    }

    @Test public void staleMarkerDoesNotFailRetry() {
        ArchVmShutdownAttempt state = new ArchVmShutdownAttempt();
        long first = state.begin();
        assertTrue(state.append("TERMUX_ARCH_SHUTDOWN_FAILED: first\n"));
        assertTrue(state.failed(first));
        assertTrue(state.blocksIdle());
        assertFalse(state.pending());

        long retry = state.begin();
        assertFalse(state.failed(retry));
        assertFalse(state.blocksIdle());
        assertTrue(state.pending());
        assertFalse(state.append("retry is still shutting down\n"));
        assertFalse(state.failed(retry));
    }

    @Test public void duplicateBeginKeepsPendingAttempt() {
        ArchVmShutdownAttempt state = new ArchVmShutdownAttempt();
        long first = state.begin();
        assertEquals(first, state.begin());
        assertTrue(state.append("TERMUX_ARCH_SHUTDOWN_FAILED: first\n"));
        assertTrue(state.failed(first));
        assertNotEquals(first, state.begin());
    }

    @Test public void hostFailureAllowsFreshRetry() {
        ArchVmShutdownAttempt state = new ArchVmShutdownAttempt();
        long first = state.begin();
        state.fail(first);
        assertTrue(state.failed(first));
        assertTrue(state.blocksIdle());
        assertNotEquals(first, state.begin());
        assertFalse(state.blocksIdle());
    }

    @Test public void slowShutdownWithoutFailureRemainsPending() {
        ArchVmShutdownAttempt state = new ArchVmShutdownAttempt();
        long attempt = state.begin();
        assertFalse(state.append("Stopping services...\n"));
        assertFalse(state.failed(attempt));
    }

    @Test public void cleanMarkerDoesNotBecomeFailure() {
        ArchVmShutdownAttempt state = new ArchVmShutdownAttempt();
        long attempt = state.begin();
        assertFalse(state.append("TERMUX_ARCH_STOPPING_V2\n"));
        assertFalse(state.failed(attempt));
    }

    @Test public void processExitClearsAttemptState() {
        ArchVmShutdownAttempt state = new ArchVmShutdownAttempt();
        long attempt = state.begin();
        state.finish();
        assertFalse(state.failed(attempt));
        assertFalse(state.blocksIdle());
        assertNotEquals(attempt, state.begin());
    }
}
