package com.termux.api.shizuku;

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
    private static final String AIDL = "android.system.virtualizationservice.";
    // Keep the platform service's bootstrap socket alive for this VM's lifetime.
    private final Object virtualizationService;
    private final Object vm;
    private final Class<?> vmInterface;
    private final int deadState;
    private final int notStartedState;
    final InputStream console;
    final OutputStream input;

    ArchVmInstance(File base) throws Exception {
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
            Object raw = emptyArrays(type("VirtualMachineRawConfig").getConstructor().newInstance());
            set(raw, "name", "termux-arch-v2");
            set(raw, "instanceId", new byte[64]);
            set(raw, "kernel", open(base, "Image", false, files));
            set(raw, "params", "console=hvc0 root=/dev/vda ro rootwait init=/usr/local/sbin/termux-vm-init panic=-1");
            set(raw, "protectedVm", false);
            set(raw, "memoryMib", 1024);
            set(raw, "platformVersion", "~1.0");
            set(raw, "consoleInputDevice", "hvc0");
            set(raw, "networkSupported", false);
            Object cpu = type("CpuOptions").getConstructor().newInstance();
            Class<?> topology = type("CpuOptions$CpuTopology");
            set(cpu, "cpuTopology", topology.getMethod("cpuCount", int.class).invoke(null, 1));
            set(raw, "cpuOptions", cpu);
            set(raw, "devices", type("AssignedDevices").getMethod("devices", String[].class)
                    .invoke(null, (Object)new String[0]));
            Class<?> diskType = type("DiskImage");
            Object disks = Array.newInstance(diskType, 2);
            String[] names = {"arch-rootfs.img", "authorized-key.bin"};
            for (int i = 0; i < names.length; i++) {
                Object disk = emptyArrays(diskType.getConstructor().newInstance());
                set(disk, "image", open(base, names[i], i == 0, files));
                set(disk, "writable", i == 0);
                Array.set(disks, i, disk);
            }
            set(raw, "disks", disks);
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
        return (ParcelFileDescriptor)vmInterface.getMethod("connectVsock", int.class).invoke(vm, 2222);
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
    private static ParcelFileDescriptor open(File base, String name, boolean write,
                                             List<ParcelFileDescriptor> files) throws Exception {
        ParcelFileDescriptor file = ParcelFileDescriptor.open(new File(base, name),
                write ? ParcelFileDescriptor.MODE_READ_WRITE : ParcelFileDescriptor.MODE_READ_ONLY);
        files.add(file);
        return file;
    }
}
