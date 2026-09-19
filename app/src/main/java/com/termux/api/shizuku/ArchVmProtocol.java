package com.termux.api.shizuku;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parsing of public keys and the owned launcher's console metadata. */
final class ArchVmProtocol {
    private static final Pattern CID = Pattern.compile("(?m)^Created VM from \"/data/local/tmp/termux-arch-v2/config\\.json\" with CID ([0-9]{1,10}), state is STARTING\\.\\r?$");
    private static final Pattern KEY = Pattern.compile("(?m)^TERMUX_ARCH_HOST_KEY_V2 (ssh-ed25519 [A-Za-z0-9+/]{68})\\r?$");

    static String publicKey(String value) {
        if (value == null || !value.matches("ssh-ed25519 [A-Za-z0-9+/]{68}"))
            throw new IllegalArgumentException("Run termux-arch to provision an Ed25519 key");
        byte[] bytes = Base64.getDecoder().decode(value.substring(12));
        byte[] header = {0, 0, 0, 11, 's', 's', 'h', '-', 'e', 'd', '2', '5', '5', '1', '9', 0, 0, 0, 32};
        if (bytes.length != 51) throw new IllegalArgumentException("Invalid Ed25519 key");
        for (int index = 0; index < header.length; index++)
            if (bytes[index] != header[index]) throw new IllegalArgumentException("Invalid Ed25519 key");
        return value;
    }

    static int cid(String console) {
        Matcher match = CID.matcher(console);
        if (!match.find()) return 0;
        long value = Long.parseLong(match.group(1));
        return value >= 3 && value <= Integer.MAX_VALUE ? (int)value : 0;
    }

    static String hostKey(String console) {
        Matcher match = KEY.matcher(console);
        if (!match.find()) return null;
        try { return publicKey(match.group(1)); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
