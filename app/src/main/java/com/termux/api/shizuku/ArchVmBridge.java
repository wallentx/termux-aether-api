package com.termux.api.shizuku;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import androidx.annotation.Keep;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

/** Bounded loopback-to-vsock relay. SSH authenticates both ends, including local apps. */
@Keep
final class ArchVmBridge implements Closeable {
    private final ServerSocket listener;
    private final Set<Socket> clients = new HashSet<>();
    private final Set<ParcelFileDescriptor> guests = new HashSet<>();
    private boolean closed;

    ArchVmBridge(ArchVmInstance vm) throws Exception {
        listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        daemon(() -> accept(vm), "arch-ssh-listener");
    }

    int port() { return listener.getLocalPort(); }

    private void accept(ArchVmInstance vm) {
        try {
            while (!listener.isClosed()) {
                Socket socket = listener.accept();
                synchronized (this) {
                    if (closed || clients.size() >= 8) { socket.close(); continue; }
                    clients.add(socket);
                }
                daemon(() -> relay(socket, vm), "arch-ssh-relay");
            }
        } catch (Exception ignored) { close(); }
    }

    private void relay(Socket socket, ArchVmInstance vm) {
        ParcelFileDescriptor guest = null;
        try {
            guest = vm.connect();
            final ParcelFileDescriptor descriptor = guest;
            synchronized (this) {
                if (closed) return;
                guests.add(guest);
            }
            // Neither stream owns the fd. Close once in finally; half-closes preserve
            // command output after stdin EOF (pipes and redirected input).
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            daemon(() -> {
                try {
                    byte[] bytes = new byte[16384];
                    int count;
                    while ((count = input.read(bytes)) != -1) {
                        int offset = 0;
                        while (offset < count) offset += Os.write(descriptor.getFileDescriptor(), bytes, offset, count - offset);
                    }
                    Os.shutdown(descriptor.getFileDescriptor(), OsConstants.SHUT_WR);
                } catch (Exception ignored) { try { socket.close(); } catch (Exception ignoredAgain) { } }
            }, "arch-ssh-input");
            byte[] bytes = new byte[16384];
            int count;
            while ((count = Os.read(guest.getFileDescriptor(), bytes, 0, bytes.length)) > 0) {
                output.write(bytes, 0, count);
            }
            socket.shutdownOutput();
        } catch (Exception error) {
            android.util.Log.w("termux-arch-vm", "SSH relay closed", error);
        } finally {
            synchronized (this) { clients.remove(socket); guests.remove(guest); }
            try { socket.close(); } catch (Exception ignored) { }
            if (guest != null) closeGuest(guest);
        }
    }

    @Override public synchronized void close() {
        closed = true;
        try { listener.close(); } catch (Exception ignored) { }
        for (Socket client : clients) try { client.close(); } catch (Exception ignored) { }
        for (ParcelFileDescriptor guest : guests) closeGuest(guest);
        clients.clear();
        guests.clear();
    }

    private static void closeGuest(ParcelFileDescriptor guest) {
        try { Os.shutdown(guest.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (Exception ignored) { }
        try { guest.close(); } catch (Exception ignored) { }
    }

    private static void daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }
}
