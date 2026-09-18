package com.termux.api.shizuku;

import android.content.Context;
import android.os.Binder;
import android.os.Process;

import androidx.annotation.Keep;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Shizuku launches this Binder object as shell/root, not as an Android Service. */
@Keep
public final class ThermalUserService extends IThermalService.Stub {
    private final int ownerUid;
    private final Semaphore running = new Semaphore(1);

    @Keep
    public ThermalUserService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
    }

    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != ownerUid && caller != Process.myUid() && caller != 0) throw new SecurityException("Wrong UID");
        System.exit(0);
    }

    @Override public String readThermal() {
        if (Binder.getCallingUid() != ownerUid) throw new SecurityException("Wrong UID");
        if (!running.tryAcquire()) return "{\"status\":\"busy\"}";
        java.lang.Process child = null;
        FutureTask<byte[]> reader = null;
        try {
            child = new ProcessBuilder("/system/bin/dumpsys", "-t", "5", "thermalservice")
                    .redirectErrorStream(true).start();
            final InputStream input = child.getInputStream();
            reader = new FutureTask<>(() -> {
                try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[2048];
                    int count;
                    // Fail closed on excess output instead of returning an apparently complete snapshot.
                    while ((count = stream.read(buffer)) != -1) {
                        if (bytes.size() + count > 65536) throw new IllegalStateException("Output limit exceeded");
                        bytes.write(buffer, 0, count);
                    }
                    return bytes.toByteArray();
                }
            });
            Thread thread = new Thread(reader, "thermal-output");
            thread.setDaemon(true);
            thread.start();
            if (!child.waitFor(6, TimeUnit.SECONDS)) return "{\"status\":\"timeout\"}";
            byte[] data = reader.get(1, TimeUnit.SECONDS);
            if (child.exitValue() != 0) {
                return new JSONObject().put("status", "unavailable").put("reason", "dumpsys_failed")
                        .put("exit_code", child.exitValue()).toString();
            }
            JSONObject report = ThermalDump.parse(new String(data, StandardCharsets.UTF_8));
            report.put("service_uid", Process.myUid()).put("timestamp_unix_ms", System.currentTimeMillis());
            return report.toString();
        } catch (Exception error) {
            return "{\"status\":\"unavailable\",\"reason\":\"thermal_read_failed\"}";
        } finally {
            if (child != null) child.destroyForcibly();
            if (reader != null) reader.cancel(true);
            running.release();
        }
    }
}
