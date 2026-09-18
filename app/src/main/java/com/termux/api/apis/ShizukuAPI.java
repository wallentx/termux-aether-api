package com.termux.api.apis;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import com.termux.api.BuildConfig;
import com.termux.api.TermuxApiReceiver;
import com.termux.api.activities.ShizukuAccessActivity;
import com.termux.api.shizuku.IThermalService;
import com.termux.api.shizuku.ThermalUserService;
import com.termux.api.util.ResultReturner;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public final class ShizukuAPI {
    private static final AtomicBoolean READING = new AtomicBoolean();

    private ShizukuAPI() {}

    public static void onReceive(TermuxApiReceiver receiver, Context context, Intent intent) {
        String operation = intent.getStringExtra("operation");
        if (operation == null) operation = "status";
        final String requested = operation;
        ResultReturner.returnData(receiver, intent, out -> {
            JSONObject report;
            if ("status".equals(requested)) {
                report = CapabilitiesAPI.probe(() -> status(context));
            } else if ("request-permission".equals(requested)) {
                try {
                    context.startActivity(new Intent(context, ShizukuAccessActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("request_permission", true));
                    report = state("pending", "permission_ui_requested")
                            .put("next", "Review Shizuku access in Termux:API, then run termux-shizuku --status");
                } catch (RuntimeException error) {
                    report = state("unavailable", "open_Shizuku_access_from_Termux_API_menu");
                }
            } else if ("thermal".equals(requested)) {
                report = CapabilitiesAPI.probe(() -> thermal(context));
            } else {
                report = state("unsupported", "unknown_operation");
            }
            report.put("schema_version", 1).put("operation", requested);
            out.println(report.toString(2));
        });
    }

    public static JSONObject status(Context context) throws JSONException {
        boolean installed;
        try {
            context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            installed = true;
        } catch (PackageManager.NameNotFoundException error) {
            installed = false;
        }
        JSONObject result = new JSONObject().put("manager_installed", installed);
        if (!Shizuku.pingBinder()) return result.put("status", "unavailable")
                .put("reason", "binder_not_connected").put("authorized", JSONObject.NULL);
        if (Shizuku.isPreV11()) return result.put("status", "unsupported").put("reason", "requires_shizuku_v11");
        boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        result.put("status", granted ? "ok" : "denied").put("authorized", granted);
        if (granted) result.put("service_uid", Shizuku.getUid());
        return result;
    }

    private static JSONObject state(String status, String reason) throws JSONException {
        return new JSONObject().put("status", status).put("reason", reason);
    }

    private static JSONObject thermal(Context context) throws JSONException {
        if (android.os.Build.VERSION.SDK_INT < 26) return state("unsupported", "thermal_reader_requires_api_26");
        JSONObject access = CapabilitiesAPI.probe(() -> status(context));
        if (!"ok".equals(access.optString("status"))) return access;
        if (Shizuku.getVersion() < 13) return state("unsupported", "thermal_reader_requires_shizuku_v13");
        if (!READING.compareAndSet(false, true)) return state("busy", "thermal_read_in_progress");
        CompletableFuture<IBinder> connected = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { connected.complete(binder); }
            @Override public void onServiceDisconnected(ComponentName name) {
                connected.completeExceptionally(new IllegalStateException("Service disconnected"));
            }
        };
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(context, ThermalUserService.class))
                .tag("termux-thermal-v1").daemon(false).processNameSuffix("termux_thermal")
                .version(BuildConfig.VERSION_NAME.hashCode() & Integer.MAX_VALUE);
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "thermal-binder");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Shizuku.bindUserService(args, connection);
            IBinder binder = connected.get(3, TimeUnit.SECONDS);
            String json = worker.submit(() -> IThermalService.Stub.asInterface(binder).readThermal())
                    .get(8, TimeUnit.SECONDS);
            return new JSONObject(json);
        } catch (TimeoutException error) {
            return state("timeout", "thermal_service_deadline");
        } catch (SecurityException error) {
            return state("denied", "shizuku_permission_denied");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return state("unavailable", "interrupted");
        } catch (Exception error) {
            return state("unavailable", "thermal_service_disconnected_or_failed");
        } finally {
            worker.shutdownNow();
            try { Shizuku.unbindUserService(args, connection, true); } catch (RuntimeException ignored) { }
            READING.set(false);
        }
    }
}
