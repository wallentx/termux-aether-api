package com.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;

import com.termux.api.BuildConfig;
import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;


/** Read-only snapshot. No permission prompts, privileged operations, or network requests. */
public final class CapabilitiesAPI {
    private CapabilitiesAPI() {}

    public static void onReceive(TermuxApiReceiver receiver, Context context, Intent intent) {
        ResultReturner.returnData(receiver, intent, out -> out.println(collect(context).toString(2)));
    }

    static JSONObject collect(Context context) throws JSONException {
        JSONObject report = new JSONObject().put("schema_version", 1)
                .put("timestamp_unix_ms", System.currentTimeMillis());
        report.put("device", probe(() -> device(context)));
        report.put("cpu", probe(CapabilitiesCpu::collect));
        report.put("permissions", probe(() -> permissions(context)));
        report.put("battery", probe(() -> battery(context)));
        report.put("thermal", probe(() -> thermal(context)));
        report.put("shizuku", probe(() -> ShizukuAPI.status(context)));
        report.put("virtualization", probe(() -> VirtualizationAPI.collect(context)));
        report.put("storage", probe(() -> new JSONObject().put("status", "ok")
                .put("all_files_access", Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : JSONObject.NULL)
                .put("scope", "Termux:API process; content URI grants are not enumerated")));
        report.put("transport", new JSONObject().put("status", "ok")
                .put("protocol", "existing Termux:API request/result transport")
                .put("authorization", "non-exported receiver and same-UID socket peers")
                .put("shared_uid", "com.termux")
                .put("signing", "debug builds retain the public upstream test key for compatibility"));
        return report;
    }

    interface Probe { JSONObject read() throws JSONException; }

    static JSONObject probe(Probe probe) throws JSONException {
        try {
            return probe.read();
        } catch (SecurityException error) {
            return state("denied", error.getClass().getSimpleName());
        } catch (UnsupportedOperationException error) {
            return state("unsupported", error.getClass().getSimpleName());
        } catch (RuntimeException error) {
            return state("unavailable", error.getClass().getSimpleName());
        }
    }

    private static JSONObject state(String status, String reason) throws JSONException {
        return new JSONObject().put("status", status).put("reason", reason);
    }

    private static JSONObject device(Context context) throws JSONException {
        return new JSONObject().put("status", "ok").put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL).put("android_release", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT).put("supported_abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)))
                .put("api_app_version", BuildConfig.VERSION_NAME)
                .put("api_app_target_sdk", context.getApplicationInfo().targetSdkVersion)
                .put("uid", Process.myUid());
    }

    private static JSONObject permissions(Context context) throws JSONException {
        PackageManager manager = context.getPackageManager();
        List<String> declared;
        try {
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            declared = info.requestedPermissions == null ? Collections.emptyList() : Arrays.asList(info.requestedPermissions);
        } catch (PackageManager.NameNotFoundException error) {
            return state("unavailable", "own_package_not_found");
        }
        JSONObject entries = new JSONObject();
        String[] names = {"android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK",
                "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
                "android.permission.MANAGE_VIRTUAL_MACHINE", "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"};
        for (String name : names) {
            String status;
            try {
                manager.getPermissionInfo(name, 0);
                // Grants are checked first: another package in the shared UID may declare it.
                status = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED ? "granted" :
                        declared.contains(name) ? "denied" : "not_requested";
            } catch (PackageManager.NameNotFoundException error) {
                status = "unsupported";
            }
            entries.put(name, new JSONObject().put("status", status).put("declared_by_api_app", declared.contains(name)));
        }
        return new JSONObject().put("status", "ok").put("scope", "effective API app UID grants; not a guarantee an operation will succeed")
                .put("entries", entries);
    }

    private static JSONObject battery(Context context) throws JSONException {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return state("unavailable", "no_battery_broadcast");
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return new JSONObject().put("status", "ok").put("source", "ACTION_BATTERY_CHANGED")
                .put("percentage", percentage(level, scale))
                .put("temperature_celsius", temperature == Integer.MIN_VALUE ? JSONObject.NULL : temperature / 10.0)
                .put("charging_status", extraOrNull(battery, BatteryManager.EXTRA_STATUS))
                .put("plugged_bitmask", extraOrNull(battery, BatteryManager.EXTRA_PLUGGED))
                .put("note", "Battery temperature, not CPU or GPU temperature");
    }

    static Object percentage(int level, int scale) {
        return scale > 0 && level >= 0 && level <= scale ? 100.0 * level / scale : JSONObject.NULL;
    }

    private static Object extraOrNull(Intent intent, String key) {
        return intent.hasExtra(key) ? intent.getIntExtra(key, -1) : JSONObject.NULL;
    }

    private static JSONObject thermal(Context context) throws JSONException {
        if (Build.VERSION.SDK_INT < 29) return state("unsupported", "requires_api_29");
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (manager == null) return state("unavailable", "no_power_service");
        JSONObject result = new JSONObject().put("status", "ok")
                .put("source", "PowerManager").put("power_save_mode", manager.isPowerSaveMode());
        result.put("throttling", probe(() -> {
            int status = manager.getCurrentThermalStatus();
            return new JSONObject().put("status", "ok").put("value", status).put("label", thermalLabel(status));
        }));
        result.put("headroom", Build.VERSION.SDK_INT < 30 ? state("unsupported", "requires_api_30") : probe(() -> {
            float value = manager.getThermalHeadroom(0);
            return new JSONObject().put("status", Float.isNaN(value) || Float.isInfinite(value) ? "unavailable" : "ok")
                    .put("value", Float.isNaN(value) || Float.isInfinite(value) ? JSONObject.NULL : value)
                    .put("forecast_seconds", 0)
                    .put("note", "1.0 is the severe-throttling threshold; NaN may mean unsupported or rate-limited");
        }));
        return result;
    }

    static String thermalLabel(int value) {
        String[] labels = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};
        return value >= 0 && value < labels.length ? labels[value] : "unknown";
    }

}
