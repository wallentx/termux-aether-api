package com.termux.api.apis;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualizationStateTest {
    @Test public void readyIsNotAClaimOfBootOrArchCompatibility() throws Exception {
        JSONObject result = VirtualizationState.readiness(true, true, true, 3, true);
        assertEquals("ok", result.getString("status"));
        assertTrue(result.getBoolean("can_attempt_custom_vm"));
        assertEquals("not_tested", result.getString("guest_boot"));
        assertEquals("not_tested", result.getString("arch_compatibility"));
        assertTrue(result.isNull("backend_running"));
    }
    @Test public void missingPermissionsDoNotBecomeHardwareFailure() throws Exception {
        JSONObject result = VirtualizationState.readiness(true, false, false, 3, true);
        assertEquals("denied", result.getString("status"));
        assertEquals(2, result.getJSONArray("blockers").length());
        assertFalse(result.getBoolean("can_attempt_custom_vm"));
    }
    @Test public void unknownCapabilitiesRemainUnknown() throws Exception {
        JSONObject result = VirtualizationState.readiness(true, true, true, null, true);
        assertEquals("unavailable", result.getString("status"));
        assertFalse(result.getBoolean("can_attempt_custom_vm"));
    }
    @Test public void protectedOnlyAndUnknownBitsDoNotPermitCustomLinux() throws Exception {
        for (int caps : new int[]{0, 1, 8}) {
            JSONObject result = VirtualizationState.readiness(true, true, true, caps, true);
            assertEquals("unsupported", result.getString("status"));
            assertFalse(result.getBoolean("can_attempt_custom_vm"));
        }
    }
    @Test public void frameworkAccessAndFeatureAreIndependentGates() throws Exception {
        assertEquals("unsupported", VirtualizationState.readiness(false, true, true, 3, true).getString("status"));
        assertEquals("unavailable", VirtualizationState.readiness(true, true, true, 3, false).getString("status"));
    }
}
