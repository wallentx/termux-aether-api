package com.termux.api.shizuku;

/** Units and validation shared by the API and VM owner. Zero means saved/default. */
public final class ArchVmMemory {
    public static final int DEFAULT_MIB = 8192;
    private ArchVmMemory() {}

    public static int parseRequest(String value) {
        if (value == null) return 0;
        if (!value.matches("[0-9]{1,10}")) throw new IllegalArgumentException("Expected positive MiB");
        int mib = Integer.parseInt(value);
        if (mib <= 0) throw new IllegalArgumentException("Expected positive MiB");
        return mib;
    }

    public static long bytes(int mib, long hostBytes) {
        long bytes = (long) mib * 1024 * 1024;
        if (mib <= 0 || bytes > hostBytes)
            throw new IllegalArgumentException("Memory must be positive and no larger than host physical RAM");
        return bytes;
    }
}
