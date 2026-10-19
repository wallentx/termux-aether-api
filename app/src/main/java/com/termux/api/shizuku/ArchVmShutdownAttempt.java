package com.termux.api.shizuku;

/** Tracks failure markers emitted after one specific host shutdown request. */
final class ArchVmShutdownAttempt {
    static final String FAILURE_MARKER = "TERMUX_ARCH_SHUTDOWN_FAILED:";

    private long sequence;
    private long active;
    private long failed;
    private String carry = "";

    long begin() {
        if (active != 0 && failed != active) return active;
        active = ++sequence;
        failed = 0;
        carry = "";
        return active;
    }

    boolean append(String text) {
        if (active == 0 || text.isEmpty()) return false;
        String scan = carry + text;
        if (scan.contains(FAILURE_MARKER)) {
            failed = active;
            carry = "";
            return true;
        }
        int retained = Math.min(scan.length(), FAILURE_MARKER.length() - 1);
        carry = scan.substring(scan.length() - retained);
        return false;
    }

    boolean failed(long attempt) { return attempt != 0 && failed == attempt; }

    boolean pending() { return active != 0 && failed != active; }

    boolean blocksDuplicateStop(boolean stopping) { return stopping && pending(); }

    boolean blocksIdle() { return active != 0 && failed == active; }

    void fail(long attempt) {
        if (attempt != 0 && active == attempt) failed = attempt;
    }

    void finish() {
        active = 0;
        failed = 0;
        carry = "";
    }
}
