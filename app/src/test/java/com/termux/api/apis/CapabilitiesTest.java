package com.termux.api.apis;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class CapabilitiesTest {
    @Test public void capabilityErrorsRemainUnknownInsteadOfFalse() throws Exception {
        JSONObject result = CapabilitiesCpu.decode(new long[] {0, 0, 2, 2, -1, 0, -1, 0, 4096});
        assertEquals("partial", result.getString("status"));
        assertTrue(result.getJSONObject("features").isNull("sve2"));
        assertEquals("unknown", result.getJSONObject("sve").getString("status"));
    }

    @Test public void highFeatureBitsAndVectorFlagsAreDecoded() throws Exception {
        JSONObject result = CapabilitiesCpu.decode(new long[] {
                1L << 22, (1L << 37) | (1L << 23), 0, 0, 0x10010, 0, 64, 0, 4096});
        assertTrue(result.getJSONObject("features").getBoolean("sme2"));
        assertFalse(result.getJSONObject("features").getBoolean("sve2"));
        assertEquals(16, result.getJSONObject("sve").getInt("vector_length_bytes"));
        assertEquals(64, result.getJSONObject("sme").getInt("vector_length_bytes"));
    }

    @Test public void vectorQueryFailureDoesNotEraseAdvertisedSupport() throws Exception {
        JSONObject result = CapabilitiesCpu.decode(new long[] {1L << 22, 0, 0, 0, -1, 22, -1, 0, 4096});
        assertTrue(result.getJSONObject("features").getBoolean("sve"));
        assertEquals("unavailable", result.getJSONObject("sve").getString("status"));
        assertEquals(22, result.getJSONObject("sve").getInt("errno"));
        assertTrue(result.getJSONObject("sve").isNull("vector_length_bytes"));
        assertEquals("unsupported", result.getJSONObject("sme").getString("status"));
    }

    @Test public void nonArmProbeDoesNotInventCpuFeatures() throws Exception {
        assertEquals("unsupported", CapabilitiesCpu.decode(null).getString("status"));
    }

    @Test public void batteryPercentagesValidateMissingAndOutOfRangeValues() {
        assertEquals(50.0, CapabilitiesAPI.percentage(1, 2));
        assertEquals(0.0, CapabilitiesAPI.percentage(0, 100));
        assertSame(JSONObject.NULL, CapabilitiesAPI.percentage(-1, 100));
        assertSame(JSONObject.NULL, CapabilitiesAPI.percentage(100, 0));
        assertSame(JSONObject.NULL, CapabilitiesAPI.percentage(101, 100));
    }

    @Test public void unavailableServicesAndDeniedPermissionsAreDistinct() throws Exception {
        assertEquals("denied", CapabilitiesAPI.probe(() -> { throw new SecurityException(); }).getString("status"));
        assertEquals("unsupported", CapabilitiesAPI.probe(() -> { throw new UnsupportedOperationException(); }).getString("status"));
        assertEquals("unavailable", CapabilitiesAPI.probe(() -> { throw new IllegalStateException(); }).getString("status"));
    }

    @Test public void thermalLabelsDoNotTurnUnknownIntoHealthy() {
        assertEquals("none", CapabilitiesAPI.thermalLabel(0));
        assertEquals("severe", CapabilitiesAPI.thermalLabel(3));
        assertEquals("unknown", CapabilitiesAPI.thermalLabel(-1));
        assertEquals("unknown", CapabilitiesAPI.thermalLabel(7));
    }
}
