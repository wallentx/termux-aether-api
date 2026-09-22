import java.io.*;
import java.lang.reflect.*;

/** Disposable, diskless AVF host-path probe. Never opens the Arch root disk. */
public class VirtioFsProbe {
    static Class<?> type(String name) throws Exception { return Class.forName(name); }
    static void set(Object obj, String name, Object value) throws Exception {
        obj.getClass().getField(name).set(obj, value);
    }
    static Object fillArrays(Object obj) throws Exception {
        for (Field f: obj.getClass().getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType().isArray() && f.get(obj) == null)
                f.set(obj, Array.newInstance(f.getType().getComponentType(), 0));
        }
        return obj;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("--diskless-probe"))
            throw new IllegalArgumentException("--diskless-probe STAGE SHARE");
        Thread watchdog = new Thread(new Runnable() { public void run() {
            try { Thread.sleep(30000); } catch (InterruptedException ignored) { return; }
            System.err.println("WATCHDOG: exiting probe owner"); System.exit(124);
        }});
        watchdog.setDaemon(true); watchdog.start();
        File base = new File(args[1]).getCanonicalFile();
        File shared = new File(args[2]).getCanonicalFile();
        if (!base.getPath().matches("/data/local/tmp/aether-vufs-[0-9a-f]{32}") ||
            !shared.getPath().matches("/storage/emulated/0/Download/aether-vufs-[0-9a-f]{32}/share"))
            throw new IllegalArgumentException("Only isolated probe staging directories are allowed");
        Object vm = null, owner = null, kernel = null, initrd = null, console = null;
        int result = 1;
        Class<?> iface = type("android.system.virtualizationservice.IVirtualMachine");
        try {
            System.out.println("PROBE UID=" + type("android.os.Process").getMethod("myUid").invoke(null));
            type("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
            Class<?> at = type("android.app.ActivityThread");
            Method main = at.getDeclaredMethod("systemMain"); main.setAccessible(true);
            Object thread = main.invoke(null);
            Method getContext = at.getDeclaredMethod("getSystemContext"); getContext.setAccessible(true);
            Object context = getContext.invoke(thread);
            Class<?> platform = type("android.system.virtualmachine.VirtualizationService");
            Method getInstance = platform.getDeclaredMethod("getInstance"); getInstance.setAccessible(true);
            owner = getInstance.invoke(null);
            Method getBinder = platform.getDeclaredMethod("getBinder"); getBinder.setAccessible(true);
            Object service = getBinder.invoke(owner);
            Class<?> customType = type("android.system.virtualmachine.VirtualMachineCustomImageConfig");
            Class<?> cb = type(customType.getName()+"$Builder");
            Object custom = cb.getConstructor().newInstance();
            cb.getMethod("setName", String.class).invoke(custom, "aether-vufs-mount-probe");
            cb.getMethod("setKernelPath", String.class).invoke(custom, new File(base,"Image").getPath());
            cb.getMethod("setInitrdPath", String.class).invoke(custom, new File(base,"probe-initramfs.cpio.gz").getPath());
            cb.getMethod("addParam", String.class).invoke(custom,
                "console=hvc0 rdinit=/init panic=-1");
            cb.getMethod("useNetwork", boolean.class).invoke(custom, false);
            Class<?> sp = type(customType.getName()+"$SharedPath");
            Object share = sp.getConstructor(String.class,int.class,int.class,int.class,int.class,
                int.class,String.class,String.class,String.class).newInstance(
                    shared.getPath(),2000,2000,0,0,0,"aether_probe","fs.sock",new File(base,"unused.sock").getPath());
            cb.getMethod("addSharedPath", sp).invoke(custom, share);
            Object image = cb.getMethod("build").invoke(custom);
            Class<?> bc = type("android.system.virtualmachine.VirtualMachineConfig$Builder");
            Object builder = bc.getConstructor(type("android.content.Context")).newInstance(context);
            bc.getMethod("setCustomImageConfig", customType).invoke(builder, image);
            bc.getMethod("setProtectedVm",boolean.class).invoke(builder,false);
            bc.getMethod("setMemoryBytes",long.class).invoke(builder,256L*1024*1024);
            Object cfg = bc.getMethod("build").invoke(builder);
            Method convert = cfg.getClass().getDeclaredMethod("toVsRawConfig");convert.setAccessible(true);
            Object raw = fillArrays(convert.invoke(cfg));set(raw,"networkSupported",false);
            Object rawShare = Array.get(raw.getClass().getField("sharedPaths").get(raw),0);
            System.out.println("RAW_SOCKET="+rawShare.getClass().getField("socketPath").get(rawShare));
            kernel = raw.getClass().getField("kernel").get(raw);
            initrd = raw.getClass().getField("initrd").get(raw);
            if (initrd == null) throw new IllegalStateException("Initramfs is mandatory");
            int disks = Array.getLength(raw.getClass().getField("disks").get(raw));
            if (disks != 0) throw new IllegalStateException("Refusing probe with disks: "+disks);
            System.out.println("DISKS=0 MEMORY_MIB=256 SHARE="+shared);
            Class<?> fd = type("android.os.ParcelFileDescriptor");
            console = fd.getMethod("open",File.class,int.class).invoke(null,
                new File(base,"console.txt"),0x38000000);
            Class<?> vc = type("android.system.virtualizationservice.VirtualMachineConfig");
            Object wrapped = vc.getMethod("rawConfig",raw.getClass()).invoke(null,raw);
            Class<?> svc = type("android.system.virtualizationservice.IVirtualizationService");
            vm = svc.getMethod("createVm",vc,fd,fd,fd,fd).invoke(service,wrapped,console,null,null,null);
            System.out.println("CREATED CID="+iface.getMethod("getCid").invoke(vm));
            iface.getMethod("start").invoke(vm);
            System.out.println("START_RETURNED");
            int dead = type("android.system.virtualizationservice.VirtualMachineState").getField("DEAD").getInt(null);
            long deadline = System.nanoTime() + 25000000000L;
            while ((Integer)iface.getMethod("getState").invoke(vm) != dead) {
                if (System.nanoTime() > deadline) throw new IOException("Guest did not stop within 25 seconds");
                Thread.sleep(100);
            }
            System.out.println("GUEST_EXITED");
            result = 0; // Host runner must also verify console markers and file contents.
        } catch (Throwable e) {
            Throwable cause = e;
            while (cause instanceof InvocationTargetException && cause.getCause()!=null) cause=cause.getCause();
            cause.printStackTrace(System.out);
        } finally {
            if(vm!=null) try {
                int dead = type("android.system.virtualizationservice.VirtualMachineState").getField("DEAD").getInt(null);
                int notStarted = type("android.system.virtualizationservice.VirtualMachineState").getField("NOT_STARTED").getInt(null);
                int state = (Integer)iface.getMethod("getState").invoke(vm);
                if(state != dead && state != notStarted) iface.getMethod("stop").invoke(vm);
            }
                catch(Throwable e) {System.out.println("STOP: "+e);}
            for(Object fd:new Object[]{kernel,initrd,console}) if(fd!=null) try {
                fd.getClass().getMethod("close").invoke(fd);
            } catch(Exception ignored) {}
            watchdog.interrupt();System.out.println("PROBE_DONE");
            // Keep the service owner reachable until cleanup is complete.
            if(owner!=null) System.out.println("OWNER_RELEASED");
        }
        System.exit(result);
    }
}
