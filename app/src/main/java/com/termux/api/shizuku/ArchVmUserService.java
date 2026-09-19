package com.termux.api.shizuku;

import android.content.Context;
import android.app.ActivityManager;
import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.annotation.Keep;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns one fixed writable guest. No arbitrary Android commands, paths or VM IDs. */
@Keep
public final class ArchVmUserService extends IArchVmService.Stub {
    private static final File BASE = new File("/data/local/tmp/termux-arch-v2");
    private final int ownerUid;
    private final Context context;
    private final Object guard = new Object();
    private final StringBuilder output = new StringBuilder();
    private ArchVmInstance child;
    private ArchVmBridge bridge;
    private ArchVmNetwork network;
    private RandomAccessFile lockFile;
    private FileLock lock;
    private boolean starting, ownerActive, stopping, ready, cleanShutdown;
    private int cid;
    private String hostKey, authorizedKey, failure;
    private int memoryMiB;
    private String memoryPreferenceError;
    private long startedAt;
    private Long readyAfterMs;
    private CountDownLatch ownerFinished = new CountDownLatch(0);

    @Keep public ArchVmUserService(Context context) {
        this.context = context;
        ownerUid = context.getApplicationInfo().uid;
    }

    private void authorize() {
        if (Binder.getCallingUid() != ownerUid) throw new SecurityException("Wrong UID");
        if (Process.myUid() != 2000) throw new SecurityException("Shell Shizuku required");
    }

    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != ownerUid && caller != Process.myUid() && caller != 0) throw new SecurityException("Wrong UID");
        stopOwned();
        synchronized (guard) { if (ownerActive) return; }
        System.exit(0);
    }

    @Override public String start(String publicKey) {
        return startWithMemory(publicKey, 0);
    }

    @Override public String startWithMemory(String publicKey, int requestedMemoryMiB) {
        authorize();
        if (requestedMemoryMiB < 0) return memoryError("invalid_memory_mib");
        CountDownLatch spawned = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        final long launchMemoryBytes;
        synchronized (guard) {
            if (ownerActive) {
                if (requestedMemoryMiB != 0 && requestedMemoryMiB != memoryMiB)
                    return memoryError("memory_change_requires_stop");
                if (publicKey != null && !publicKey.equals(authorizedKey))
                    return "{\"status\":\"error\",\"reason\":\"key_change_requires_stop\"}";
                return report();
            }
            try {
                int selected = requestedMemoryMiB == 0 ? savedMemoryMiB() : requestedMemoryMiB;
                ActivityManager.MemoryInfo host = new ActivityManager.MemoryInfo();
                ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(host);
                launchMemoryBytes = ArchVmMemory.bytes(selected, host.totalMem);
                memoryMiB = selected;
                memoryPreferenceError = null;
            } catch (Exception invalid) {
                memoryPreferenceError = invalid.getMessage();
                return memoryError("invalid_memory_mib");
            }
            try {
                validateFile(BASE, true, 0, 0);
                File owner = new File(BASE, "owner.lock");
                if (owner.exists()) validateFile(owner, false, 0, 0);
                lockFile = new RandomAccessFile(owner, "rw");
                Os.chmod(owner.getPath(), 0600);
                lock = lockFile.getChannel().tryLock();
                if (lock == null) throw new IllegalStateException("Guest is already owned");
                validateFile(new File(BASE, "Image"), false, 4096, 256L * 1024 * 1024);
                validateFile(new File(BASE, "arch-rootfs.img"), false, 1024 * 1024, 8L * 1024 * 1024 * 1024);
                File seed = new File(BASE, "authorized-key.bin");
                if (seed.exists()) validateFile(seed, false, 4096, 4096);
                if (publicKey == null && seed.exists()) {
                    publicKey = new String(java.nio.file.Files.readAllBytes(seed.toPath()),
                            StandardCharsets.US_ASCII).replace("\0", "").trim();
                }
                authorizedKey = ArchVmProtocol.publicKey(publicKey);
                byte[] bytes = new byte[4096];
                byte[] key = (authorizedKey + "\n").getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(key, 0, bytes, 0, key.length);
                try (FileOutputStream stream = new FileOutputStream(seed)) { stream.write(bytes); }
                Os.chmod(seed.getPath(), 0600);
                starting = ownerActive = true;
                stopping = ready = cleanShutdown = false;
                cid = 0;
                hostKey = failure = null;
                readyAfterMs = null;
                output.setLength(0);
                startedAt = SystemClock.elapsedRealtime();
                ownerFinished = finished;
            } catch (Exception error) {
                releaseLock();
                failure = "image_or_key_invalid: " + error.getClass().getSimpleName();
                return report();
            }
        }
        Thread owner = new Thread(() -> {
            ArchVmInstance running = null;
            boolean attemptedStart = false;
            try {
                running = new ArchVmInstance(context, BASE, launchMemoryBytes);
                synchronized (guard) { child = running; }
                final ArchVmInstance current = running;
                Thread drain = new Thread(() -> drain(current), "arch-vm-console");
                drain.setDaemon(true);
                drain.start();
                attemptedStart = true;
                running.start();
                synchronized (guard) { cid = running.cid(); starting = false; }
                spawned.countDown();
                while (running.isAlive()) Thread.sleep(200);
                drain.join(1000);
                synchronized (guard) {
                    if (!cleanShutdown && failure == null)
                        failure = ready ? "vm_exited_without_clean_shutdown" : "vm_exited_before_readiness";
                }
            } catch (Exception | LinkageError error) {
                android.util.Log.w("termux-arch-vm", "AVF owner failed", error);
                synchronized (guard) { failure = "avf_failed: " + error.getClass().getSimpleName(); }
            } finally {
                // Never drop the only reference to a writable VM unless it is known
                // stopped. A transient Binder error must not become a forced stop.
                boolean stopped = running == null || !attemptedStart;
                if (!stopped) try { stopped = !running.isAlive(); } catch (Exception ignored) { }
                synchronized (guard) {
                    starting = false;
                    if (stopped && (child == running || child == null)) stopped();
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

    private void drain(ArchVmInstance instance) {
        try (InputStream stream = instance.console) {
            byte[] bytes = new byte[2048];
            int count;
            while ((count = stream.read(bytes)) != -1) {
                synchronized (guard) {
                    if (child != instance) return;
                    output.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
                    if (hostKey == null) hostKey = ArchVmProtocol.hostKey(output.toString());
                    if (!ready && hostKey != null && output.indexOf("TERMUX_ARCH_READY_V2") >= 0) {
                        bridge = new ArchVmBridge(child);
                        network = new ArchVmNetwork(child, BASE);
                        ready = true;
                        readyAfterMs = SystemClock.elapsedRealtime() - startedAt;
                        try { saveMemoryMiB(); memoryPreferenceError = null; }
                        catch (Exception error) {
                            memoryPreferenceError = "Could not save RAM preference: " + error.getClass().getSimpleName();
                        }
                    }
                    if (output.indexOf("TERMUX_ARCH_STOPPING_V2") >= 0) cleanShutdown = true;
                    if (output.length() > 32768) output.delete(0, output.length() - 32768);
                }
            }
        } catch (Exception | LinkageError error) {
            synchronized (guard) {
                if (ownerActive && child == instance) {
                    android.util.Log.w("termux-arch-vm", "Guest console/bridge failed", error);
                    failure = "console_or_bridge_failed: " + error.getClass().getSimpleName();
                }
            }
        }
    }

    @Override public String status() { authorize(); synchronized (guard) { return report(); } }
    @Override public String stop() { authorize(); return stopOwned(); }

    private String stopOwned() {
        ArchVmInstance running;
        CountDownLatch finished;
        synchronized (guard) {
            if (starting) return "{\"status\":\"busy\",\"reason\":\"start_in_progress\"}";
            running = child;
            finished = ownerFinished;
            stopping = ownerActive;
        }
        if (running != null) {
            try {
                if (running.isAlive()) {
                    running.input.write("poweroff\n".getBytes(StandardCharsets.US_ASCII));
                    running.input.flush();
                    long deadline = SystemClock.elapsedRealtime() + 12000;
                    while (running.isAlive() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
                }
                if (running.isAlive()) {
                    synchronized (guard) { failure = "shutdown_timeout_guest_left_running"; }
                } else {
                    finished.await(1, TimeUnit.SECONDS);
                    synchronized (guard) { if (child == running) stopped(); }
                }
            } catch (Exception error) {
                synchronized (guard) { failure = "shutdown_failed_guest_left_running"; }
            }
        }
        synchronized (guard) { return report(); }
    }

    private void stopped() {
        ownerActive = stopping = false;
        if (bridge != null) bridge.close();
        bridge = null;
        if (network != null) network.close();
        network = null;
        if (child != null) child.closeConsole();
        child = null;
        releaseLock();
    }

    private void releaseLock() {
        if (lock != null) try { lock.release(); } catch (Exception ignored) { }
        if (lockFile != null) try { lockFile.close(); } catch (Exception ignored) { }
        lock = null;
        lockFile = null;
    }

    private String report() {
        try {
            Object nextMemory;
            try { nextMemory = savedMemoryMiB(); }
            catch (Exception invalid) {
                nextMemory = JSONObject.NULL;
                memoryPreferenceError = "Invalid saved RAM preference: " + invalid.getClass().getSimpleName();
            }
            return new JSONObject().put("status", failure != null ? "error" : starting ? "starting"
                            : ownerActive ? stopping ? "stopping" : ready ? "ready" : "booting" : "stopped")
                    .put("backend", "android_avf").put("service_uid", Process.myUid())
                    .put("vm_name", "termux-arch-v2").put("running", ownerActive)
                    .put("guest_boot", ready ? "verified" : "not_verified")
                    .put("ready_after_ms", readyAfterMs == null ? JSONObject.NULL : readyAfterMs)
                    .put("root_read_only", false).put("network_enabled", true)
                    .put("cpu_topology", "match_host")
                    .put("memory_mib", memoryMiB == 0 ? JSONObject.NULL : memoryMiB)
                    .put("memory_configurable", true)
                    .put("next_start_memory_mib", nextMemory)
                    .put("memory_preference_error", memoryPreferenceError == null ? JSONObject.NULL : memoryPreferenceError)
                    .put("native_network_enabled", false)
                    .put("network_backend", "vsock_userspace_ipv4")
                    .put("network_bridge_state", network == null ? "not_started" : network.state())
                    .put("network_error", network == null || network.error() == null ? JSONObject.NULL : network.error())
                    .put("native_network_reason", "host_crosvm_rejects_net_option")
                    // A configured NIC does not prove DHCP, DNS or internet reachability.
                    .put("network_connectivity", "not_probed")
                    .put("ssh_port", bridge == null ? JSONObject.NULL : bridge.port())
                    .put("ssh_host_key", hostKey == null ? JSONObject.NULL : hostKey)
                    .put("cid", cid == 0 ? JSONObject.NULL : cid)
                    .put("clean_shutdown", !ownerActive && cleanShutdown)
                    .put("reason", failure == null ? JSONObject.NULL : failure)
                    .put("console_tail", output.toString()).toString();
        } catch (Exception error) { return "{\"status\":\"error\",\"reason\":\"report_failed\"}"; }
    }

    private String memoryError(String reason) {
        synchronized (guard) {
            try { return new JSONObject(report()).put("status", "error").put("reason", reason).toString(); }
            catch (Exception ignored) { return "{\"status\":\"error\",\"reason\":\"invalid_memory_mib\"}"; }
        }
    }

    private int savedMemoryMiB() throws Exception {
        File file = new File(BASE, "memory-mib");
        // Check dangling links too; never silently follow or overwrite one.
        if (!file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath())) return ArchVmMemory.DEFAULT_MIB;
        validateFile(file, false, 1, 16);
        return ArchVmMemory.parseRequest(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                StandardCharsets.US_ASCII).trim());
    }

    private void saveMemoryMiB() throws Exception {
        validateFile(BASE, true, 0, 0);
        File file = new File(BASE, "memory-mib");
        if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) validateFile(file, false, 1, 16);
        File temporary = File.createTempFile("memory-mib-", ".tmp", BASE);
        try {
            Os.chmod(temporary.getPath(), 0600);
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write((memoryMiB + "\n").getBytes(StandardCharsets.US_ASCII));
                stream.getFD().sync();
            }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }

    static void validateFile(File file, boolean directory, long min, long max) throws Exception {
        StructStat stat = Os.lstat(file.getPath());
        if (!file.getCanonicalPath().equals(file.getAbsolutePath()) || stat.st_uid != 2000
                || (stat.st_mode & 0077) != 0
                || (directory ? !OsConstants.S_ISDIR(stat.st_mode)
                : !OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1 || stat.st_size < min || stat.st_size > max)) {
            throw new SecurityException("Invalid staged file");
        }
    }
}
