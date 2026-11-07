package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.ui.kit.Measure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text epoch's two parts that can be held without a client: the serial a font object is known by,
 * and the identity a renderer is known by.
 *
 * <h2>Why the serial is asked through reflection</h2>
 *
 * <p>Because it is private, and the property is about it alone: the same font object reads the same serial
 * every time, a different one reads its own, and remembering the last one asked about does not keep a
 * replaced font alive. {@link TextEpoch#now} cannot show any of that headless: there is no client font.
 */
@DisplayName("the text epoch")
class TextEpochTest {

    private static long serialOf(Object font) throws ReflectiveOperationException {
        Method serialOf = TextEpoch.class.getDeclaredMethod("serialOf", Object.class);
        serialOf.setAccessible(true);
        return (long) serialOf.invoke(null, font);
    }

    // ------------------------------------------------------------------
    // The font's serial
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same font reads the same serial, however often it is asked")
    void theSameFontReadsTheSameSerial() throws ReflectiveOperationException {
        Object font = new Object();
        long first = serialOf(font);
        for (int i = 0; i < 5; i++) {
            assertEquals(first, serialOf(font));
        }
    }

    @Test
    @DisplayName("two fonts asked about in turn each keep their own serial")
    void fontsInTurnKeepTheirSerials() throws ReflectiveOperationException {
        // Alternating is the case the remembered last font cannot answer on its own: every ask is the other
        // font, so every ask goes to the map and replaces what is remembered.
        Object one = new Object();
        Object two = new Object();
        long first = serialOf(one);
        long second = serialOf(two);
        assertNotEquals(first, second, "two faces are two serials");
        for (int i = 0; i < 4; i++) {
            assertEquals(first, serialOf(one));
            assertEquals(second, serialOf(two));
        }
        assertEquals(second, serialOf(two), "and asking the remembered one again is still its own");
    }

    @Test
    @DisplayName("a replacement font gets a serial never handed out before")
    void aReplacementGetsANewSerial() throws ReflectiveOperationException {
        Object old = new Object();
        long before = serialOf(old);
        long after = serialOf(new Object());
        assertTrue(after > before, "serials only climb, so a reload never reads as the face it replaced");
    }

    @Test
    @DisplayName("remembering the last font does not keep it alive")
    void theRememberedFontCanBeCollected() throws ReflectiveOperationException, InterruptedException {
        // The font asked about last is remembered weakly, as the map holds it: a font a reload replaced must
        // still be collectable, or every reload would leave the old face's glyphs behind.
        Object font = new Object();
        serialOf(font);
        WeakReference<Object> watch = new WeakReference<>(font);
        font = null;
        for (int i = 0; i < 100 && watch.get() != null; i++) {
            System.gc();
            Thread.sleep(5);
        }
        assertNull(watch.get(), "the font was collected although it was the last one asked about");
    }

    @Test
    @DisplayName("a font's equality is its identity, so remembering it by identity answers as the map does")
    void aFontsEqualityIsItsIdentity() throws ReflectiveOperationException {
        // The map is a WeakHashMap, which compares keys with equals; the remembered font is compared with ==.
        // The two agree for a class that does not override equals, which is what the game's font is.
        Class<?> font = Class.forName("net.minecraft.client.gui.Font", false, TextEpochTest.class.getClassLoader());
        assertEquals(Object.class, font.getMethod("equals", Object.class).getDeclaringClass());
        assertEquals(Object.class, font.getMethod("hashCode").getDeclaringClass());
    }

    // ------------------------------------------------------------------
    // The renderer's identity
    // ------------------------------------------------------------------

    /**
     * A game renderer without a frame to wrap.
     *
     * <p>The record's constructor wants a live {@code GuiGraphics}, which needs a client; the rule
     * under test reads only the renderer's type, so an instance made without the constructor is enough.
     */
    private static GuiGraphicsRenderer gameRenderer() throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return (GuiGraphicsRenderer) allocate.invoke(unsafe, GuiGraphicsRenderer.class);
    }

    @Test
    @DisplayName("every game renderer is one renderer to the epoch, so a memo survives the new one each frame")
    void gameRenderersShareAnIdentity() throws ReflectiveOperationException {
        GuiGraphicsRenderer thisFrame = gameRenderer();
        GuiGraphicsRenderer nextFrame = gameRenderer();
        assertNotSame(thisFrame, nextFrame);
        assertEquals(TextEpoch.identityOf(thisFrame), TextEpoch.identityOf(nextFrame));
        assertEquals(TextEpoch.measuredThrough(thisFrame), TextEpoch.measuredThrough(nextFrame),
                "the epoch did not move between two frames' renderers");
    }

    @Test
    @DisplayName("any other renderer is known by its own identity, as every renderer was")
    void otherRenderersKeepTheirIdentity() {
        RecordingRenderer one = RecordingRenderer.create();
        RecordingRenderer two = RecordingRenderer.withCharWidth(3);
        assertEquals(System.identityHashCode(one), TextEpoch.identityOf(one));
        assertEquals(System.identityHashCode(two), TextEpoch.identityOf(two));
        assertEquals(TextEpoch.measuredThrough(one), TextEpoch.measuredThrough(one), "stable for one renderer");
    }

    @Test
    @DisplayName("the game renderers' identity is one no other renderer's identity hash can be")
    void theSharedIdentityCannotCollide() throws ReflectiveOperationException {
        int shared = TextEpoch.identityOf(gameRenderer());
        assertTrue(shared < 0, "the shared identity is negative");
        // And identity hashes on this JVM are not: a sample, since the property is the JVM's.
        for (int i = 0; i < 10_000; i++) {
            assertTrue(System.identityHashCode(new Object()) >= 0);
        }
    }

    @Test
    @DisplayName("a game renderer measures only through static state: it is a record, so nothing overrides it")
    void aGameRendererCannotMeasureDifferently() {
        // The shared identity rests on this: a subclass could measure differently from its parent, and a
        // record cannot have one. That its widths and line height read only the client's font and the text
        // scale is in its source; this pins the half that a later edit could quietly undo.
        assertTrue(GuiGraphicsRenderer.class.isRecord());
        assertTrue(java.lang.reflect.Modifier.isFinal(GuiGraphicsRenderer.class.getModifiers()));
    }

    @Test
    @DisplayName("a memo over the epoch still forgets when a different recording renderer measures")
    void aMemoStillFollowsOtherRenderers() {
        // MeasureCacheTest.cachedOverFollowsTheRenderer, through the helper the controls now use: widths one
        // renderer measured are never served for another that measures differently.
        RecordingRenderer[] inForce = {RecordingRenderer.withCharWidth(5)};
        Measure measure = Measure.cachedOver(text -> inForce[0].textWidth(text),
                () -> inForce[0].lineHeight(),
                () -> TextEpoch.measuredThrough(inForce[0]));

        assertEquals(10, measure.width("ab"));
        inForce[0] = RecordingRenderer.withCharWidth(3);
        assertEquals(6, measure.width("ab"), "the renderer in force measured, not the memo of the last one");
        inForce[0] = RecordingRenderer.withCharWidth(5);
        assertEquals(10, measure.width("ab"));
    }
}
