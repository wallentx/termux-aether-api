package com.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

/** App-UID preflight plus explicitly selected, bounded Shizuku Arch operations. */
public final class VirtualizationAPI {
    private static final String PREFIX = "android.system.virtualmachine.";
    private static final String MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE";
    private static final String CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE";

    private VirtualizationAPI() {}

    public static void onReceive(TermuxApiReceiver receiver, Context context, Intent intent) {
        String argument = intent.getStringExtra("operation");
        final String operation = argument == null ? "status" : argument;
        String memoryArgument = intent.getStringExtra("memory_mib");
        final String memory = memoryArgument == null && intent.hasExtra("memory_mib") ? "" : memoryArgument;
        ResultReturner.returnData(receiver, intent, out -> {
            JSONObject report = "status".equals(operation)
                    ? CapabilitiesAPI.probe(() -> collect(context))
                    : CapabilitiesAPI.probe(() -> ArchVmAPI.call(context, operation,
                            intent.getStringExtra("ssh_public_key"), memory, intent.getStringExtra("session_token"),
                            intent.getBooleanExtra("keep_memory", false), intent.getStringExtra("disk_bytes"),
                            intent.getStringExtra("shared_path")));
            report.put("schema_version", 1).put("operation", operation);
            out.println(report.toString(2));
        });
    }

    public static JSONObject collect(Context context) throws JSONException {
        boolean feature = context.getPackageManager().hasSystemFeature("android.software.virtualization_framework");
        boolean manage = context.checkSelfPermission(MANAGE) == PackageManager.PERMISSION_GRANTED;
        boolean custom = context.checkSelfPermission(CUSTOM) == PackageManager.PERMISSION_GRANTED;
        ClassLoader loader = context.getClassLoader();
        Integer capabilities = null;
        JSONObject manager;
        try {
            Class<?> type = Class.forName(PREFIX + "VirtualMachineManager", false, loader);
            Object instance = context.getSystemService(type);
            if (instance == null) {
                manager = new JSONObject().put("status", "unavailable").put("reason", "manager_service_missing");
            } else {
                Object value = type.getMethod("getCapabilities").invoke(instance);
                if (!(value instanceof Integer)) throw new IllegalStateException("Unexpected capabilities type");
                capabilities = (Integer) value;
                manager = new JSONObject().put("status", "ok");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            manager = failure(error);
        }
        manager.put("capability_bits", capabilities == null ? JSONObject.NULL : capabilities)
                .put("protected_vm", capabilities == null ? JSONObject.NULL : (capabilities & 1) != 0)
                .put("non_protected_vm", capabilities == null ? JSONObject.NULL : (capabilities & 2) != 0);

        JSONObject imageApi;
        boolean imageAvailable = false;
        try {
            Class<?> image = Class.forName(PREFIX + "VirtualMachineCustomImageConfig", false, loader);
            Class<?> imageBuilder = Class.forName(PREFIX + "VirtualMachineCustomImageConfig$Builder", false, loader);
            imageBuilder.getMethod("setKernelPath", String.class);
            imageBuilder.getMethod("setInitrdPath", String.class);
            Class.forName(PREFIX + "VirtualMachineConfig$Builder", false, loader)
                    .getMethod("setCustomImageConfig", image);
            imageApi = new JSONObject().put("status", "ok");
            imageAvailable = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            imageApi = failure(error);
        }

        JSONObject readiness = VirtualizationState.readiness(feature, manage, custom, capabilities, imageAvailable);
        return new JSONObject().put("status", readiness.getString("status"))
                .put("schema_version", 1).put("timestamp_unix_ms", System.currentTimeMillis())
                .put("source", "Termux:API app context").put("uid", Process.myUid())
                .put("feature_advertised", feature)
                .put("apex_visible", new File("/apex/com.android.virt").isDirectory())
                .put("permissions", new JSONObject().put(MANAGE, manage ? "granted" : "denied")
                        .put(CUSTOM, custom ? "granted" : "denied"))
                .put("manager", manager).put("custom_image_api", imageApi).put("readiness", readiness)
                .put("note", "Preflight only; this command never creates, starts, stops, or enumerates VMs.");
    }

    private static JSONObject failure(Throwable error) throws JSONException {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
        return new JSONObject().put("status", cause instanceof SecurityException ? "denied" : "unavailable")
                .put("reason", cause.getClass().getSimpleName())
                .put("note", "Missing and restricted framework APIs are both possible; no bypass was attempted.");
    }
}
