package com.termux.api.shizuku;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground-request discovery. A returned Binder still requires normal SDK attachment/consent. */
public final class ShizukuBinderDiscovery {
    private static final String REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER";
    private static final long TIMEOUT_MS = 15000;
    public interface Listener {
        void onBinderReceived(IBinder binder);
        void onUnavailable(String reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private int remaining;
    private boolean active;
    private Listener listener;
    private Runnable timeout;

    public boolean isActive() { return active; }

    /** Call on the main thread after an explicit user request. */
    public void start(Context context, Listener result) {
        cancel();
        final int request = generation;
        listener = result;
        active = true;
        List<Intent> requests = new ArrayList<>();
        try {
            for (String name : ShizukuManagers.installed(context.getPackageManager())) {
                final ApplicationInfo manager;
                try { manager = context.getPackageManager().getApplicationInfo(name, 0); }
                catch (PackageManager.NameNotFoundException removed) { continue; }
                final int managerUid = manager.uid;
                AtomicBoolean answered = new AtomicBoolean();
                Binder receiver = new Binder() {
                    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                            throws RemoteException {
                        if (code != IBinder.FIRST_CALL_TRANSACTION)
                            return super.onTransact(code, data, reply, flags);
                        if (Binder.getCallingUid() != managerUid) return false;
                        if (data.dataAvail() == 0) return false;
                        final IBinder binder;
                        try { binder = data.readStrongBinder(); }
                        catch (RuntimeException malformed) { return false; }
                        // ShizukuPlus appends its sourceDir; only the Binder is needed by the SDK.
                        if (!answered.compareAndSet(false, true)) return false;
                        main.post(() -> received(request, binder));
                        return true;
                    }
                };
                Bundle callback = new Bundle();
                callback.putBinder("binder", receiver);
                requests.add(new Intent(REQUEST_BINDER).setPackage(name)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        .putExtra("data", callback)
                        .putExtra("callingPackage", context.getPackageName())
                        .putExtra("callingUid", context.getApplicationInfo().uid));
            }
        } catch (RuntimeException failure) {
            unavailable("manager_lookup_failed");
            return;
        }
        remaining = requests.size();
        if (remaining == 0) {
            unavailable("manager_missing");
            return;
        }
        timeout = () -> {
            if (active && generation == request) unavailable("request_timeout");
        };
        main.postDelayed(timeout, TIMEOUT_MS);
        for (Intent intent : requests) {
            try { context.sendBroadcast(intent); }
            catch (RuntimeException failure) { received(request, null); }
        }
    }

    private void received(int request, IBinder binder) {
        if (!active || generation != request) return;
        if (binder == null || !binder.pingBinder()) {
            if (--remaining == 0) unavailable("service_not_running");
            return;
        }
        Listener result = listener;
        cancel();
        result.onBinderReceived(binder);
    }

    private void unavailable(String reason) {
        Listener result = listener;
        cancel();
        if (result != null) result.onUnavailable(reason);
    }

    /** Invalidates outstanding replies and releases any Activity listener. */
    public void cancel() {
        generation++;
        active = false;
        remaining = 0;
        listener = null;
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
    }
}
