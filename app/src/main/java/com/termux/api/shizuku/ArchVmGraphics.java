package com.termux.api.shizuku;

import java.io.File;
import java.lang.reflect.Method;

/** Explicit per-boot graphics configuration. Does not install or replace kernels. */
public final class ArchVmGraphics {
    public static final String NONE = "none";
    public static final String GFXSTREAM = "gfxstream";
    private static final String CUSTOM = "android.system.virtualmachine.VirtualMachineCustomImageConfig";

    private ArchVmGraphics() {}

    public static String parse(String mode) {
        if (mode == null || NONE.equals(mode)) return NONE;
        if (GFXSTREAM.equals(mode)) return GFXSTREAM;
        throw new IllegalArgumentException("Graphics mode must be none or gfxstream");
    }

    /** An ordinary existing client may attach without changing the running VM's mode. */
    public static boolean canReuse(String running, String requested) {
        return requested == null || running.equals(parse(requested));
    }

    public static File kernel(File base, String mode) {
        return new File(base, GFXSTREAM.equals(parse(mode)) ? "Image-gfxstream" : "Image");
    }

    static Object invoke(Object target, String name, Class<?> argument, Object value) throws Exception {
        Method method;
        try { method = target.getClass().getMethod(name, argument); }
        catch (NoSuchMethodException missing) {
            // Android 17 release source uses boolean; current Pixel preview uses Boolean.
            // Resolve the signature before invocation so a real driver/configuration error
            // never causes an invocation to be retried with a different overload.
            if (argument != boolean.class) throw missing;
            method = target.getClass().getMethod(name, Boolean.class);
        }
        return method.invoke(target, value);
    }

    static Object buildGpu(Class<?> builderType) throws Exception {
        Object gpu = builderType.getConstructor().newInstance();
        invoke(gpu, "setBackend", String.class, GFXSTREAM);
        invoke(gpu, "setRendererUseEgl", boolean.class, false);
        invoke(gpu, "setRendererUseGles", boolean.class, false);
        invoke(gpu, "setRendererUseGlx", boolean.class, false);
        invoke(gpu, "setRendererUseSurfaceless", boolean.class, true);
        invoke(gpu, "setRendererUseVulkan", boolean.class, true);
        invoke(gpu, "setContextTypes", String[].class, new String[]{"gfxstream-vulkan", "gfxstream-composer"});
        invoke(gpu, "setRendererFeatures", String.class,
                "VulkanDisableCoherentMemoryAndEmulate:enabled;VulkanAllocateHostVisibleAsUdmabuf:enabled;ExternalBlob:enabled");
        return builderType.getMethod("build").invoke(gpu);
    }

    static void configure(Object customBuilder, String mode) throws Exception {
        if (NONE.equals(parse(mode))) return;
        Class<?> displayType = Class.forName(CUSTOM + "$DisplayConfig");
        Class<?> displayBuilder = Class.forName(CUSTOM + "$DisplayConfig$Builder");
        Object display = displayBuilder.getConstructor().newInstance();
        invoke(display, "setWidth", int.class, 1280);
        invoke(display, "setHeight", int.class, 720);
        invoke(customBuilder, "setDisplayConfig", displayType, displayBuilder.getMethod("build").invoke(display));
        // Gfxstream needs a placeholder display even before an Android Surface is attached.
        Class<?> gpuType = Class.forName(CUSTOM + "$GpuConfig");
        Object gpu = buildGpu(Class.forName(CUSTOM + "$GpuConfig$Builder"));
        invoke(customBuilder, "setGpuConfig", gpuType, gpu);
    }
}
