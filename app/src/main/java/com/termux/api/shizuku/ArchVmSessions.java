package com.termux.api.shizuku;

import java.util.HashMap;
import java.util.Map;

/** Session leases use elapsed time and are always accessed under the VM owner's guard. */
final class ArchVmSessions {
    static final long LEASE_MILLIS = 60000;
    private final Map<String, Long> leases = new HashMap<>();
    private boolean managed, keepMemory;

    String update(String operation, String token, boolean retain, long now) {
        if (token == null || !token.matches("[0-9a-f]{32}")) return "invalid_token";
        expire(now);
        switch (operation) {
            case "acquire":
                if (!leases.containsKey(token) && leases.size() >= 64) return "session_limit";
                if (leases.isEmpty()) keepMemory = false;
                managed = true;
                keepMemory |= retain;
                leases.put(token, now + LEASE_MILLIS);
                return "acquired";
            case "renew":
                if (!leases.containsKey(token)) return "expired";
                leases.put(token, now + LEASE_MILLIS);
                return "renewed";
            case "release":
                leases.remove(token);
                return "released";
            default: return "invalid_operation";
        }
    }

    void expire(long now) { leases.entrySet().removeIf(entry -> entry.getValue() <= now); }
    int count() { return leases.size(); }
    boolean isManaged() { return managed; }
    boolean keepMemory() { return keepMemory; }
    boolean shouldIdle(int connections) { return managed && leases.isEmpty() && connections == 0; }
    void clear() { leases.clear(); managed = keepMemory = false; }
}
