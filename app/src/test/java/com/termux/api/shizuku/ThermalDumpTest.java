package com.termux.api.shizuku;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ThermalDumpTest {
    @Test public void ignoresStaleCacheAndCoolingSections() throws Exception {
        String dump = "Cached temperatures:\n\tTemperature{mValue=99.0, mType=0, mName=BIG, mStatus=3}\n"
                + "Current temperatures from HAL:\n\tTemperature{mValue=28.5, mType=0, mName=BIG, mStatus=0}\n"
                + "Current cooling devices from HAL:\n\tTemperature{mValue=88.0, mType=0, mName=wrong, mStatus=3}\n";
        JSONObject result = ThermalDump.parse(dump);
        assertEquals("ok", result.getString("status"));
        assertEquals(1, result.getJSONArray("temperatures").length());
        assertEquals(28.5, result.getJSONArray("temperatures").getJSONObject(0).getDouble("celsius"), 0.001);
    }

    @Test public void doesNotCallUnknownOrBatteryLimitReadingsTemperatures() throws Exception {
        String dump = "Current temperatures from HAL:\n"
                + "\tTemperature{mValue=-5700.0, mType=-1, mName=virtual, mStatus=0}\n"
                + "\tTemperature{mValue=4200.0, mType=6, mName=voltage, mStatus=0}\n"
                + "\tTemperature{mValue=30.0, mType=10, mName=TPU, mStatus=0}\n";
        JSONObject result = ThermalDump.parse(dump);
        assertEquals(2, result.getJSONArray("other_readings").length());
        assertFalse(result.getJSONArray("other_readings").getJSONObject(0).has("celsius"));
        assertEquals("tpu", result.getJSONArray("temperatures").getJSONObject(0).getString("type"));
    }

    @Test public void nonfiniteAndUnknownSeverityRemainUnknown() throws Exception {
        JSONObject result = ThermalDump.parse("Current temperatures from HAL:\n"
                + "\tTemperature{mValue=NaN, mType=1, mName=GPU, mStatus=99}\n");
        JSONObject sensor = result.getJSONArray("temperatures").getJSONObject(0);
        assertEquals("unavailable", sensor.getString("status"));
        assertTrue(sensor.isNull("celsius"));
        assertTrue(sensor.isNull("throttling_severity"));
    }

    @Test public void changedFormatOrMissingSectionIsNotAHealthyReading() throws Exception {
        assertEquals("unavailable", ThermalDump.parse("Permission Denial").getString("status"));
        JSONObject result = ThermalDump.parse("Current temperatures from HAL:\n\tunrecognized format\n");
        assertEquals("partial", result.getString("status"));
        assertEquals(1, result.getInt("unparsed_lines"));
    }

    @Test public void overflowingTypeDoesNotCrashTheSnapshot() throws Exception {
        JSONObject result = ThermalDump.parse("Current temperatures from HAL:\n"
                + "\tTemperature{mValue=10.0, mType=99999999999999999, mName=GPU, mStatus=0}\n");
        assertEquals("partial", result.getString("status"));
    }
}
