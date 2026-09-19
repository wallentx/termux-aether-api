package com.termux.api.shizuku;

import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.annotation.Keep;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One fixed, writable Arch VM with an authenticated SSH channel. No arbitrary host commands or image paths. */
@Keep
public final class ArchVmUserService extends IArchVmService.Stub {
    private static final File BASE = new File("/data/local/tmp/termux-arch-v2");
    private static final String READY = "TERMUX_ARCH_READY_V2";
    private final int ownerUid;
    private final String launcher;
    private final String vsockLibrary;
    private ArchVmBridge bridge;
    private int cid;
    private String hostKey;
    private String authorizedKey;
    private boolean stopping;
    private final Object guard = new Object();
    private final StringBuilder output = new StringBuilder();
    private java.lang.Process child;
    private boolean starting;
    private boolean ownerActive;
    private CountDownLatch ownerFinished = new CountDownLatch(0);
    private boolean ready;
    private Integer exitCode;
    private String failure;
    private long startedAt;
    private Long readyAfterMs;

    @Keep public ArchVmUserService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        launcher = context.getApplicationInfo().nativeLibraryDir + "/libtermux-vm-launcher.so";
        vsockLibrary = context.getApplicationInfo().nativeLibraryDir + "/libtermux-arch-vsock.so";
    }

    private void authorize() {
        if (Binder.getCallingUid() != ownerUid) throw new SecurityException("Wrong UID");
        if (Process.myUid() != 2000) throw new SecurityException("Shell Shizuku required");
    }

    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != ownerUid && caller != Process.myUid() && caller != 0) throw new SecurityException("Wrong UID");
        stopOwned();
        synchronized (guard) {
            // Refuse a normal service removal while a writable guest is still alive.
            if (ownerActive) return;
            if (bridge != null) bridge.close();
        }
        System.exit(0); // Unexpected owner death is still a guest power loss.
    }

    @Override public String start(String publicKey) {
        authorize();
        CountDownLatch spawned = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        synchronized (guard) {
            if (ownerActive) {
                if (publicKey != null && !publicKey.equals(authorizedKey))
                    return "{\"status\":\"error\",\"reason\":\"key_change_requires_stop\"}";
                return report();
            }
            try {
                validateFile(BASE, true, 0, 0);
                File seed = new File(BASE, "authorized-key.bin");
                if (seed.exists()) validateFile(seed, false, 4096, 4096);
                if (publicKey == null && seed.exists()) {
                    publicKey = new String(java.nio.file.Files.readAllBytes(seed.toPath()),
                            StandardCharsets.US_ASCII).replace("\0", "").trim();
                }
                authorizedKey = ArchVmProtocol.publicKey(publicKey);
                byte[] seedBytes = new byte[4096];
                byte[] keyBytes = (authorizedKey + "\n").getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(keyBytes, 0, seedBytes, 0, keyBytes.length);
                try (FileOutputStream stream = new FileOutputStream(seed)) { stream.write(seedBytes); }
                Os.chmod(seed.getPath(), 0600);
                validateFile(new File(BASE, "Image"), false, 4096, 256L * 1024 * 1024);
                validateFile(new File(BASE, "arch-rootfs.img"), false, 1024 * 1024, 8L * 1024 * 1024 * 1024);
                File config = new File(BASE, "config.json");
                if (config.exists()) validateFile(config, false, 0, 16384);
                JSONObject value = new JSONObject().put("name", "termux-arch-v2")
                        .put("kernel", new File(BASE, "Image").getPath())
                        .put("params", "console=hvc0 root=/dev/vda ro rootwait init=/usr/local/sbin/termux-vm-init panic=-1")
                        .put("protected", false).put("memory_mib", 1024).put("cpu_topology", "one_cpu")
                        .put("platform_version", "~1.0").put("console_input_device", "hvc0")
                        .put("disks", new JSONArray().put(new JSONObject()
                                .put("image", new File(BASE, "arch-rootfs.img").getPath()).put("writable", true))
                                .put(new JSONObject().put("image", seed.getPath()).put("writable", false)));
                try (FileOutputStream stream = new FileOutputStream(config)) {
                    stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
                }
                Os.chmod(config.getPath(), 0600);
                starting = true;
                ownerActive = true;
                ownerFinished = finished;
                ready = false;
                stopping = false;
                cid = 0;
                hostKey = null;
                if (bridge != null) bridge.close();
                bridge = null;
                exitCode = null;
                failure = null;
                readyAfterMs = null;
                output.setLength(0);
                startedAt = SystemClock.elapsedRealtime();
            } catch (Exception error) {
                failure = "image_staging_invalid: " + error.getClass().getSimpleName();
                return report();
            }
        }
        // Keep the thread that forks alive until the child exits: PDEATHSIG is
        // tied to that Linux thread, not merely the Java process's thread group.
        Thread owner = new Thread(() -> {
            java.lang.Process running = null;
            try {
                running = new ProcessBuilder(launcher, Integer.toString(Process.myPid()))
                        .redirectErrorStream(true).start();
                synchronized (guard) { child = running; starting = false; }
                spawned.countDown();
                final InputStream stream = running.getInputStream();
                Thread drain = new Thread(() -> drain(stream), "arch-vm-console");
                drain.setDaemon(true);
                drain.start();
                int code = running.waitFor();
                drain.join(1000);
                synchronized (guard) { exitCode = code; }
            } catch (Exception error) {
                synchronized (guard) { failure = "launcher_failed: " + error.getClass().getSimpleName(); }
            } finally {
                if (running != null && running.isAlive()) running.destroyForcibly();
                synchronized (guard) {
                    starting = false; ownerActive = false; stopping = false;
                    if (bridge != null) bridge.close();
                    bridge = null;
                }
                finished.countDown();
                spawned.countDown();
            }
        }, "arch-vm-owner");
        owner.setDaemon(true);
        owner.start();
        try { spawned.await(2, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        synchronized (guard) { return report(); }
    }

    private void drain(InputStream input) {
        try (InputStream stream = input) {
            byte[] bytes = new byte[2048];
            int count;
            while ((count = stream.read(bytes)) != -1) {
                synchronized (guard) {
                    output.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
                    if (cid == 0) cid = ArchVmProtocol.cid(output.toString());
                    if (hostKey == null) hostKey = ArchVmProtocol.hostKey(output.toString());
                    if (bridge == null && cid > 0 && hostKey != null && output.indexOf(READY) >= 0) {
                        bridge = new ArchVmBridge(vsockLibrary, cid);
                    }
                    if (!ready && bridge != null && output.indexOf(READY) >= 0) {
                        ready = true;
                        readyAfterMs = SystemClock.elapsedRealtime() - startedAt;
                    }
                    if (output.length() > 32768) output.delete(0, output.length() - 32768);
                }
            }
        } catch (Exception | LinkageError error) {
            android.util.Log.w("termux-arch-vm", "Guest console/bridge failed", error);
            synchronized (guard) { failure = "console_or_bridge_failed: " + error.getClass().getSimpleName(); }
        }
    }

    @Override public String status() {
        authorize();
        synchronized (guard) { return report(); }
    }

    @Override public String stop() {
        authorize();
        return stopOwned();
    }

    private String stopOwned() {
        java.lang.Process running;
        CountDownLatch finished;
        synchronized (guard) {
            if (starting) return "{\"status\":\"busy\",\"reason\":\"start_in_progress\"}";
            running = child;
            finished = ownerFinished;
            stopping = running != null && running.isAlive();
        }
        if (running != null && running.isAlive()) {
            try {
                running.getOutputStream().write("poweroff\n".getBytes(StandardCharsets.US_ASCII));
                running.getOutputStream().flush();
                if (!running.waitFor(12, TimeUnit.SECONDS)) {
                    synchronized (guard) { failure = "shutdown_timeout_guest_left_running"; }
                }
            } catch (Exception error) {
                synchronized (guard) { failure = "shutdown_failed_guest_left_running"; }
            }
        }
        // The owner also drains the final console bytes before publishing exitCode.
        try { finished.await(2, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        synchronized (guard) { return report(); }
    }

    private String report() {
        try {
            boolean alive = child != null && child.isAlive();
            return new JSONObject().put("status", failure != null ? "error" : starting ? "starting"
                            : alive ? stopping ? "stopping" : ready ? "ready" : "booting" : "stopped")
                    .put("backend", "android_avf").put("service_uid", Process.myUid())
                    .put("vm_name", "termux-arch-v2").put("running", alive)
                    .put("guest_boot", ready ? "verified" : "not_verified")
                    .put("ready_after_ms", readyAfterMs == null ? JSONObject.NULL : readyAfterMs)
                    .put("root_read_only", false).put("network_enabled", false)
                    .put("ssh_port", bridge == null ? JSONObject.NULL : bridge.port())
                    .put("ssh_host_key", hostKey == null ? JSONObject.NULL : hostKey)
                    .put("cid", cid == 0 ? JSONObject.NULL : cid)
                    .put("exit_code", exitCode == null ? JSONObject.NULL : exitCode)
                    .put("reason", failure == null ? JSONObject.NULL : failure)
                    .put("console_tail", output.toString()).toString();
        } catch (Exception error) { return "{\"status\":\"error\",\"reason\":\"report_failed\"}"; }
    }

    private static void validateFile(File file, boolean directory, long min, long max) throws Exception {
        StructStat stat = Os.lstat(file.getPath());
        if (!file.getCanonicalPath().equals(file.getAbsolutePath()) || stat.st_uid != 2000
                || (stat.st_mode & 0077) != 0
                || (directory ? !OsConstants.S_ISDIR(stat.st_mode)
                : !OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1 || stat.st_size < min || stat.st_size > max)) {
            throw new SecurityException("Invalid staged file");
        }
    }
}
