package com.termux.api.shizuku;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** One private pipe/vsock packet transport. Opens no Android listening ports. */
final class ArchVmNetwork implements Closeable {
    private volatile boolean closed, suspended;
    private volatile String state = "connecting";
    private volatile String error;
    private ParcelFileDescriptor guest;
    private java.lang.Process backend;

    ArchVmNetwork(ArchVmInstance vm, File base) {
        daemon(() -> run(vm, base), "arch-network-owner");
    }

    String state() { return suspended ? "suspended" : state; }
    synchronized void setSuspended(boolean value) throws Exception {
        if (backend != null) Os.kill(Math.toIntExact(backend.pid()), value ? OsConstants.SIGSTOP : OsConstants.SIGCONT);
        suspended = value;
    }
    String error() { return error; }

    private void run(ArchVmInstance vm, File base) {
        try {
            File executable = new File(base, "arch-network-host");
            ArchVmUserService.validateFile(executable, false, 4096, 128L * 1024 * 1024);
            if (!executable.canExecute()) throw new IllegalStateException("Network backend is not executable");
            ParcelFileDescriptor connection = null;
            for (int attempt = 0; attempt < 120 && !closed; attempt++) {
                try { connection = vm.connect(2223); break; }
                catch (Exception unavailable) { Thread.sleep(250); }
            }
            if (connection == null) {
                if (!closed) throw new IllegalStateException("Guest network listener did not become ready");
                return;
            }
            final ParcelFileDescriptor fd = connection;
            final java.lang.Process process;
            synchronized (this) {
                if (closed) { connection.close(); return; }
                guest = connection;
                ProcessBuilder builder = new ProcessBuilder(executable.getPath());
                // Android's libc resolver follows Android network/DNS policy.
                builder.environment().put("GODEBUG", "netdns=cgo");
                backend = process = builder.start();
                if (suspended) Os.kill(Math.toIntExact(process.pid()), OsConstants.SIGSTOP);
            }
            daemon(() -> diagnostics(process), "arch-network-log");
            daemon(() -> {
                try (OutputStream destination = process.getOutputStream()) {
                    byte[] bytes = new byte[32768];
                    int count;
                    while (!closed && (count = Os.read(fd.getFileDescriptor(), bytes, 0, bytes.length)) > 0) {
                        destination.write(bytes, 0, count);
                        // Process stdin is buffered. DHCP must not wait for a
                        // buffer to fill before the backend sees its first frame.
                        destination.flush();
                    }
                } catch (Exception failure) {
                    fail(failure);
                    close();
                }
            }, "arch-network-to-host");
            try (InputStream source = process.getInputStream()) {
                byte[] bytes = new byte[32768];
                int count;
                while (!closed && (count = source.read(bytes)) != -1) {
                    int offset = 0;
                    while (offset < count && !closed) {
                        int written = Os.write(fd.getFileDescriptor(), bytes, offset, count - offset);
                        if (written <= 0) throw new IllegalStateException("Network transport stopped writing");
                        offset += written;
                    }
                }
            }
            if (!closed) fail(new IllegalStateException("Network backend exited"));
        } catch (Exception failure) {
            fail(failure);
        } finally {
            close();
        }
    }

    private void diagnostics(java.lang.Process process) {
        try (InputStream stream = process.getErrorStream()) {
            byte[] bytes = new byte[1024];
            StringBuilder pending = new StringBuilder();
            int count;
            while ((count = stream.read(bytes)) != -1) {
                pending.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
                synchronized (this) {
                    if (!closed && error == null && pending.indexOf("TERMUX_ARCH_NETWORK_HOST_READY") >= 0)
                        state = "connected";
                }
                int end;
                while ((end = pending.indexOf("\n")) >= 0) {
                    android.util.Log.i("termux-arch-network", pending.substring(0, end));
                    pending.delete(0, end + 1);
                }
                if (pending.length() > 2048) pending.delete(0, pending.length() - 2048);
            }
        } catch (Exception ignored) { }
    }

    private synchronized void fail(Exception failure) {
        if (closed) return;
        error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        state = "error";
        android.util.Log.w("termux-arch-network", "Packet bridge stopped", failure);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!"error".equals(state)) state = "stopped";
        if (guest != null) {
            try { Os.shutdown(guest.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (Exception ignored) { }
            try { guest.close(); } catch (Exception ignored) { }
            guest = null;
        }
        if (backend != null) {
            final java.lang.Process process = backend;
            backend = null;
            // A stopped process cannot handle SIGTERM until it is resumed.
            if (suspended) try { Os.kill(Math.toIntExact(process.pid()), OsConstants.SIGCONT); }
            catch (Exception ignored) { }
            process.destroy();
            daemon(() -> {
                try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }, "arch-network-reap");
        }
    }

    private static void daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }
}
