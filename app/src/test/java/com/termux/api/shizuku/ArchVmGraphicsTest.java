package com.termux.api.shizuku;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArchVmGraphicsTest {
    @Test public void defaultLaunchAndKernelStayHeadless() {
        assertEquals(ArchVmGraphics.NONE, ArchVmGraphics.parse(null));
        assertEquals(new File("/stage/Image"), ArchVmGraphics.kernel(new File("/stage"), null));
        assertEquals(new File("/stage/Image-gfxstream"),
                ArchVmGraphics.kernel(new File("/stage"), "gfxstream"));
    }

    @Test public void headlessModeNeedsNoGraphicsFrameworkClasses() throws Exception {
        ArchVmGraphics.configure(null, null);
        ArchVmGraphics.configure(null, "none");
    }

    @Test public void modeCannotSupplyAnArbitraryKernelPath() {
        for (String mode : new String[]{"", "GFXSTREAM", "gfxstream\n", " gfxstream", "../Image", "/data/Image", "powervr"}) {
            try { ArchVmGraphics.parse(mode); fail(mode); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void legacyClientCanAttachButExplicitModeChangesRequireStop() {
        assertTrue(ArchVmGraphics.canReuse("gfxstream", null));
        assertTrue(ArchVmGraphics.canReuse("none", null));
        assertTrue(ArchVmGraphics.canReuse("gfxstream", "gfxstream"));
        assertFalse(ArchVmGraphics.canReuse("gfxstream", "none"));
        assertFalse(ArchVmGraphics.canReuse("none", "gfxstream"));
    }

    public static class PrimitiveBuilder {
        boolean value;
        public void option(boolean value) { this.value = value; }
    }
    public static class BoxedBuilder {
        boolean value;
        public void option(Boolean value) { this.value = value; }
    }
    public static class ThrowingBuilder {
        int calls;
        public void option(boolean value) { calls++; throw new IllegalStateException("rejected"); }
        public void option(Boolean value) { calls++; }
    }

    @Test public void handlesBothAndroidBooleanSignatures() throws Exception {
        PrimitiveBuilder primitive = new PrimitiveBuilder();
        BoxedBuilder boxed = new BoxedBuilder();
        ArchVmGraphics.invoke(primitive, "option", boolean.class, true);
        ArchVmGraphics.invoke(boxed, "option", boolean.class, true);
        assertTrue(primitive.value);
        assertTrue(boxed.value);
    }

    @Test public void missingFrameworkMethodsFailRatherThanDroppingConfiguration() throws Exception {
        try {
            ArchVmGraphics.invoke(new BoxedBuilder(), "absent", boolean.class, true);
            fail("missing graphics API");
        } catch (NoSuchMethodException expected) { }
    }

    @Test public void doesNotRetryASetterThatRejectsItsValue() throws Exception {
        ThrowingBuilder builder = new ThrowingBuilder();
        try {
            ArchVmGraphics.invoke(builder, "option", boolean.class, true);
            fail("driver/configuration error must propagate");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        assertEquals(1, builder.calls);
    }
}
