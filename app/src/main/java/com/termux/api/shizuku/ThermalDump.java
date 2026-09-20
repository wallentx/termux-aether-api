package com.termux.api.shizuku;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse only live HAL values; cached/event values can be stale. */
public final class ThermalDump {
    private static final Pattern SENSOR = Pattern.compile(
            "Temperature\\{mValue=([^,]+), mType=(-?\\d+), mName=(.*), mStatus=(-?\\d+)\\}");
    private static final String[] TYPES = {"cpu", "gpu", "battery", "skin", "usb_port", "power_amplifier",
            "bcl_voltage", "bcl_current", "bcl_percentage", "npu", "tpu", "display", "modem", "soc", "wifi",
            "camera", "flashlight", "speaker", "ambient", "pogo"};

    private ThermalDump() {}

    public static JSONObject parse(String text) throws JSONException {
        JSONArray temperatures = new JSONArray();
        JSONArray other = new JSONArray();
        boolean found = false;
        int malformed = 0;
        for (String line : text.split("\\r?\\n")) {
            if (line.trim().equals("Current temperatures from HAL:")) {
                found = true;
                continue;
            }
            if (!found) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // The next dump section starts at the left margin.
            if (!Character.isWhitespace(line.charAt(0))) break;
            Matcher match = SENSOR.matcher(trimmed);
            if (!match.matches()) {
                malformed++;
                continue;
            }
            try {
                double value = Double.parseDouble(match.group(1));
                int type = Integer.parseInt(match.group(2));
                int severity = Integer.parseInt(match.group(4));
                boolean celsius = (type >= 0 && type <= 5) || (type >= 9 && type < TYPES.length);
                boolean finite = !Double.isNaN(value) && !Double.isInfinite(value);
                JSONObject sensor = new JSONObject().put("name", match.group(3)).put("type_id", type)
                        .put("type", type >= 0 && type < TYPES.length ? TYPES[type] : "unknown")
                        .put("status", finite ? "ok" : "unavailable")
                        .put("throttling_severity", severity >= 0 && severity <= 6 ? severity : JSONObject.NULL);
                sensor.put(celsius ? "celsius" : "value", finite ? value : JSONObject.NULL);
                if (celsius) temperatures.put(sensor); else other.put(sensor);
            } catch (NumberFormatException error) {
                malformed++;
            }
        }
        String status = !found ? "unavailable" : malformed > 0 ? "partial" : "ok";
        return new JSONObject().put("status", status).put("source", "dumpsys thermalservice: current HAL section")
                .put("temperatures", temperatures).put("other_readings", other).put("unparsed_lines", malformed)
                .put("reason", found ? JSONObject.NULL : "current_hal_section_missing");
    }
}
