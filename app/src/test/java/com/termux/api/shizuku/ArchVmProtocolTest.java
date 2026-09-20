package com.termux.api.shizuku;

import org.junit.Test;
import java.util.Base64;
import static org.junit.Assert.*;

public class ArchVmProtocolTest {
    private String key() {
        byte[] bytes = new byte[51];
        byte[] header = {0, 0, 0, 11, 's', 's', 'h', '-', 'e', 'd', '2', '5', '5', '1', '9', 0, 0, 0, 32};
        System.arraycopy(header, 0, bytes, 0, header.length);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(bytes);
    }

    @Test public void acceptsOnlyAnEd25519WireKey() {
        assertEquals(key(), ArchVmProtocol.publicKey(key()));
        for (String invalid : new String[]{null, "", key() + "\ncommand", "ssh-ed25519 " + "A".repeat(68), key().replace(' ', '\n')}) {
            try { ArchVmProtocol.publicKey(invalid); fail("Accepted invalid key"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void consoleKeyNeedsACompleteValidLine() {
        assertEquals(key(), ArchVmProtocol.hostKey("logs\r\nTERMUX_ARCH_HOST_KEY_V2 " + key() + "\r\nmore"));
        assertNull(ArchVmProtocol.hostKey("TERMUX_ARCH_HOST_KEY_V2 " + key().substring(0, 40)));
        assertNull(ArchVmProtocol.hostKey("TERMUX_ARCH_HOST_KEY_V2 ssh-ed25519 " + "A".repeat(68)));
    }
}
