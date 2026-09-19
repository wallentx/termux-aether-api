package com.termux.api.shizuku;

import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Uses AVF's owned Binder handle: SELinux forbids raw vsock creation by shell. */
final class ArchVmInstance {
    static final long MEMORY_BYTES = 4L * 1024 * 1024 * 1024;
    private static final String AIDL = "android.system.virtualizationservice.";
    // Keep the platform service's bootstrap socket alive for this VM's lifetime.
    private final Object virtualizationService;
    private final Object vm;
    private final Class<?> vmInterface;
    private final int deadState;
    private final int notStartedState;
    final InputStream console;
    final OutputStream input;

    ArchVmInstance(Context context, File base) throws Exception {
        Class<?> platform = Class.forName("android.system.virtualmachine.VirtualizationService");
        Method instance = platform.getDeclaredMethod("getInstance");
        instance.setAccessible(true);
        virtualizationService = instance.invoke(null);
        Method getBinder = platform.getDeclaredMethod("getBinder");
        getBinder.setAccessible(true);
        Object service = getBinder.invoke(virtualizationService);
        Class<?> serviceInterface = type("IVirtualizationService");
        vmInterface = type("IVirtualMachine");
        deadState = type("VirtualMachineState").getField("DEAD").getInt(null);
        notStartedState = type("VirtualMachineState").getField("NOT_STARTED").getInt(null);
        List<ParcelFileDescriptor> files = new ArrayList<>();
        ParcelFileDescriptor[] outputPipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] inputPipe = ParcelFileDescriptor.createPipe();
        files.add(outputPipe[1]);
        files.add(inputPipe[0]);
        boolean success = false;
        try {
            // Let the installed framework populate its version-specific defaults.
            // Android 17 preview's device-assignment schema differs from AOSP main.
            Class<?> customType = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig");
            Class<?> customBuilderType = Class.forName(customType.getName() + "$Builder");
            Class<?> diskType = Class.forName(customType.getName() + "$Disk");
            Object custom = customBuilderType.getConstructor().newInstance();
            customBuilderType.getMethod("setName", String.class).invoke(custom, "termux-arch-v2");
            customBuilderType.getMethod("setKernelPath", String.class).invoke(custom, new File(base, "Image").getPath());
            customBuilderType.getMethod("addParam", String.class).invoke(custom,
                    "console=hvc0 root=/dev/vda ro rootwait init=/usr/local/sbin/termux-vm-init panic=-1 termux_epoch="
                            + (System.currentTimeMillis() / 1000));
            // Pixel's current preview virtmgr emits --net, but its crosvm rejects
            // that option before boot. Keep native NIC off until the host is fixed.
            customBuilderType.getMethod("useNetwork", boolean.class).invoke(custom, false);
            customBuilderType.getMethod("addDisk", diskType).invoke(custom,
                    diskType.getMethod("RWDisk", String.class).invoke(null, new File(base, "arch-rootfs.img").getPath()));
            customBuilderType.getMethod("addDisk", diskType).invoke(custom,
                    diskType.getMethod("RODisk", String.class).invoke(null, new File(base, "authorized-key.bin").getPath()));
            Object image = customBuilderType.getMethod("build").invoke(custom);
            Class<?> builderType = Class.forName("android.system.virtualmachine.VirtualMachineConfig$Builder");
            Object builder = builderType.getConstructor(Context.class).newInstance(context);
            builderType.getMethod("setCustomImageConfig", customType).invoke(builder, image);
            builderType.getMethod("setProtectedVm", boolean.class).invoke(builder, false);
            builderType.getMethod("setMemoryBytes", long.class).invoke(builder, MEMORY_BYTES);
            int matchHost = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
                    .getField("CPU_TOPOLOGY_MATCH_HOST").getInt(null);
            builderType.getMethod("setCpuTopology", int.class).invoke(builder, matchHost);
            builderType.getMethod("setConsoleInputDevice", String.class).invoke(builder, "hvc0");
            Object frameworkConfig = builderType.getMethod("build").invoke(builder);
            Method rawMethod = frameworkConfig.getClass().getDeclaredMethod("toVsRawConfig");
            rawMethod.setAccessible(true);
            Object raw = emptyArrays(rawMethod.invoke(frameworkConfig));
            set(raw, "networkSupported", false);
            files.add((ParcelFileDescriptor)raw.getClass().getField("kernel").get(raw));
            Object disks = raw.getClass().getField("disks").get(raw);
            for (int i = 0; i < Array.getLength(disks); i++) {
                Object disk = Array.get(disks, i);
                files.add((ParcelFileDescriptor)disk.getClass().getField("image").get(disk));
            }
            Class<?> configType = type("VirtualMachineConfig");
            Object config = configType.getMethod("rawConfig", raw.getClass()).invoke(null, raw);
            vm = serviceInterface.getMethod("createVm", configType, ParcelFileDescriptor.class,
                    ParcelFileDescriptor.class, ParcelFileDescriptor.class, ParcelFileDescriptor.class)
                    .invoke(service, config, outputPipe[1], inputPipe[0], null, null);
            console = new ParcelFileDescriptor.AutoCloseInputStream(outputPipe[0]);
            input = new ParcelFileDescriptor.AutoCloseOutputStream(inputPipe[1]);
            success = true;
        } finally {
            for (ParcelFileDescriptor file : files) try { file.close(); } catch (Exception ignored) { }
            if (!success) {
                outputPipe[0].close();
                inputPipe[1].close();
            }
        }
    }

    void start() throws Exception { vmInterface.getMethod("start").invoke(vm); }
    int cid() throws Exception { return (Integer)vmInterface.getMethod("getCid").invoke(vm); }
    boolean isAlive() throws Exception {
        IBinder binder = (IBinder)vmInterface.getMethod("asBinder").invoke(vm);
        if (!binder.isBinderAlive()) return false;
        int state = (Integer)vmInterface.getMethod("getState").invoke(vm);
        return state != deadState && state != notStartedState;
    }
    ParcelFileDescriptor connect() throws Exception {
        return connect(2222);
    }
    ParcelFileDescriptor connect(int port) throws Exception {
        if (port != 2222 && port != 2223) throw new IllegalArgumentException("Unsupported guest port");
        return (ParcelFileDescriptor)vmInterface.getMethod("connectVsock", int.class).invoke(vm, port);
    }
    void closeConsole() {
        try { input.close(); } catch (Exception ignored) { }
        try { console.close(); } catch (Exception ignored) { }
    }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(AIDL + name); }
    private static void set(Object target, String field, Object value) throws Exception {
        target.getClass().getField(field).set(target, value);
    }
    private static Object emptyArrays(Object object) throws Exception {
        for (Field field : object.getClass().getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType().isArray()
                    && field.get(object) == null) field.set(object, Array.newInstance(field.getType().getComponentType(), 0));
        }
        return object;
    }
}
