package com.termux.api.apis;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.termux.api.BuildConfig;
import com.termux.api.shizuku.ArchVmUserService;
import com.termux.api.shizuku.ArchVmMemory;
import com.termux.api.shizuku.IArchVmService;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** Client for a fixed Shizuku-owned VM. Direct-app AVF preflight stays separate. */
public final class ArchVmAPI {
    private ArchVmAPI() {}

    public static JSONObject call(Context context, String operation, String publicKey, String memory, String token, boolean keepMemory, String diskBytes) throws org.json.JSONException {
        if (!operation.equals("arch-start") && !operation.equals("arch-status") && !operation.equals("arch-stop")
                && !operation.equals("arch-session-acquire") && !operation.equals("arch-session-renew")
                && !operation.equals("arch-session-release") && !operation.equals("arch-memory-live")
                && !operation.equals("arch-disk-grow")) {
            return new JSONObject().put("status", "unsupported").put("reason", "unknown_operation");
        }
        final int memoryMiB;
        try {
            memoryMiB = ArchVmMemory.parseRequest(memory);
            if (memory != null && !operation.equals("arch-start") && !operation.equals("arch-memory-live")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            return new JSONObject().put("status", "error").put("reason", "invalid_memory_mib");
        }
        final long diskSize;
        try {
            diskSize = diskBytes == null ? 0 : Long.parseLong(diskBytes);
            if (operation.equals("arch-disk-grow") && diskSize <= 0) throw new IllegalArgumentException();
            if (diskBytes != null && !operation.equals("arch-disk-grow")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            return new JSONObject().put("status", "error").put("reason", "invalid_disk_bytes");
        }
        JSONObject access = ShizukuAPI.status(context);
        if (!"ok".equals(access.optString("status"))) return access;
        if (Shizuku.getUid() != 2000 || Shizuku.getVersion() < 13) {
            return new JSONObject().put("status", "unsupported").put("reason", "requires_shell_shizuku_v13");
        }
        CompletableFuture<IBinder> connected = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { connected.complete(binder); }
            @Override public void onServiceDisconnected(ComponentName name) {
                connected.completeExceptionally(new IllegalStateException("VM service disconnected"));
            }
        };
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(context, ArchVmUserService.class))
                .tag("termux-arch-v2").daemon(true).processNameSuffix("termux_arch_vm")
                .version(BuildConfig.VERSION_NAME.hashCode() & Integer.MAX_VALUE);
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "arch-vm-binder");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Shizuku.bindUserService(args, connection);
            IArchVmService service = IArchVmService.Stub.asInterface(connected.get(3, TimeUnit.SECONDS));
            String json = worker.submit(() -> {
                switch (operation) {
                    case "arch-start": return memoryMiB == 0 ? service.start(publicKey)
                            : service.startWithMemory(publicKey, memoryMiB);
                    case "arch-memory-live": return service.resizeMemory(memoryMiB);
                    case "arch-disk-grow": return service.growDisk(diskSize);
                    case "arch-stop": return service.stop();
                    case "arch-session-acquire": return service.session("acquire", token, keepMemory);
                    case "arch-session-renew": return service.session("renew", token, false);
                    case "arch-session-release": return service.session("release", token, false);
                    default: return service.status();
                }
            }).get(16, TimeUnit.SECONDS);
            return new JSONObject(json);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new JSONObject().put("status", "unavailable").put("reason", "interrupted");
        } catch (Exception error) {
            android.util.Log.w("termux-arch-vm", "VM service call failed", error);
            return new JSONObject().put("status", "unavailable").put("reason", error.getClass().getSimpleName());
        } finally {
            worker.shutdownNow();
            // Detach only. Never remove a service whose writable guest may still be shutting down.
            try { Shizuku.unbindUserService(args, connection, false); }
            catch (RuntimeException ignored) { }
        }
    }
}
