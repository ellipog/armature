package dev.ellipog.armature.client.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arrays a flushed run is copied into, lent for a frame and taken back at the next.
 *
 * <h2>What is held, and why these</h2>
 *
 * <p>An array lent twice in one frame is two elements drawing from one buffer: the earlier one draws the later
 * one's geometry, and nothing throws. So the first claim is that nothing is lent twice until the frame number
 * moves, over a few thousand mixed runs across several frames. The rest are what makes it a pool rather than a leak: the same arrays
 * come back on the next frame, a run never takes a bigger class's array, a busy frame is trimmed away, and a
 * copy reads exactly what the fresh copy it replaced read, which is kept here as the oracle.
 */
@DisplayName("Batch arrays")
class BatchArraysTest {

    private static float[] floats(int length, Random random) {
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            out[i] = random.nextFloat();
        }
        return out;
    }

    @Test
    @DisplayName("a lent copy reads exactly what the fresh copy it replaced read")
    void aLentCopyIsTheOldCopy() {
        Random random = new Random(36);
        GuiGraphicsRenderer.BatchArrays arrays = new GuiGraphicsRenderer.BatchArrays().forFrame(1);
        for (int trial = 0; trial < 300; trial++) {
            float[] source = floats(1 + random.nextInt(3000), random);
            int length = 1 + random.nextInt(source.length);
            // The oracle is what flushBatch did before: Arrays.copyOf to the run's length.
            float[] fresh = Arrays.copyOf(source, length);
            float[] lent = GuiGraphicsRenderer.BatchArrays.copy(arrays, source, length);
            assertTrue(lent.length >= length, "room for the run");
            assertArrayEquals(fresh, Arrays.copyOf(lent, length), "trial " + trial);

            int[] ints = new int[source.length];
            Arrays.setAll(ints, i -> random.nextInt());
            assertArrayEquals(Arrays.copyOf(ints, length),
                    Arrays.copyOf(GuiGraphicsRenderer.BatchArrays.copy(arrays, ints, length), length));
        }
    }

    @Test
    @DisplayName("with no frame to lend for, a copy is the old fresh copy, exactly")
    void noLenderIsTheFreshCopy() {
        float[] source = {1F, 2F, 3F, 4F, 5F};
        float[] copied = GuiGraphicsRenderer.BatchArrays.copy(null, source, 3);
        assertArrayEquals(new float[] {1F, 2F, 3F}, copied);
        assertArrayEquals(new int[] {7, 8}, GuiGraphicsRenderer.BatchArrays.copy(null, new int[] {7, 8, 9}, 2));
    }

    @Test
    @DisplayName("nothing is lent twice in one frame")
    void nothingIsLentTwiceInAFrame() {
        Random random = new Random(1392);
        GuiGraphicsRenderer.BatchArrays arrays = new GuiGraphicsRenderer.BatchArrays();
        for (long frame = 1; frame <= 5; frame++) {
            arrays.forFrame(frame);
            Set<Object> lent = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int run = 0; run < 600; run++) {
                int quads = 1 + random.nextInt(random.nextBoolean() ? 8 : 1000);
                // Asked again for the same frame partway through, as every flush of a frame does.
                if (run % 97 == 0) {
                    arrays.forFrame(frame);
                }
                assertTrue(lent.add(arrays.lend(new float[quads * 8], quads * 8)), "a float array lent twice");
                assertTrue(lent.add(arrays.lend(new int[quads], quads)), "an int array lent twice");
            }
        }
    }

    @Test
    @DisplayName("the next frame lends the same arrays again, in turn")
    void theNextFrameLendsThemAgain() {
        GuiGraphicsRenderer.BatchArrays arrays = new GuiGraphicsRenderer.BatchArrays().forFrame(7);
        float[] first = arrays.lend(new float[40], 40);
        float[] second = arrays.lend(new float[40], 40);
        assertNotSame(first, second);

        arrays.forFrame(8);
        assertSame(first, arrays.lend(new float[33], 33), "the first array of its class comes back first");
        assertSame(second, arrays.lend(new float[64], 64), "and the second after it");
        assertNotSame(first, arrays.lend(new float[40], 40), "and a third is new");
    }

    @Test
    @DisplayName("a run takes an array of its own size class, never a bigger one")
    void sizeClasses() {
        assertEquals(GuiGraphicsRenderer.BatchArrays.MIN_CLASS, GuiGraphicsRenderer.BatchArrays.sizeClass(1));
        assertEquals(4, GuiGraphicsRenderer.BatchArrays.sizeClass(16));
        assertEquals(5, GuiGraphicsRenderer.BatchArrays.sizeClass(17));
        assertEquals(20, GuiGraphicsRenderer.BatchArrays.sizeClass(1 << 20));
        assertEquals(21, GuiGraphicsRenderer.BatchArrays.sizeClass((1 << 20) + 1));

        // A frame of big runs, then a frame of small ones: the small ones are not served the big arrays.
        GuiGraphicsRenderer.BatchArrays arrays = new GuiGraphicsRenderer.BatchArrays().forFrame(1);
        for (int i = 0; i < 4; i++) {
            arrays.lend(new float[100_000], 100_000);
        }
        arrays.forFrame(2);
        for (int i = 0; i < 50; i++) {
            float[] small = arrays.lend(new float[12], 12);
            assertEquals(16, small.length, "a twelve-element run takes a sixteen-element array");
        }
        for (int length : new int[] {1, 16, 17, 1000, 4096, 4097, 99_999}) {
            assertEquals(1 << GuiGraphicsRenderer.BatchArrays.sizeClass(length),
                    arrays.lend(new float[length], length).length, "length " + length);
        }
    }

    @Test
    @DisplayName("a busy frame is trimmed away at the start of the next, fewer kept the bigger they are")
    void aBusyFrameIsNotKept() {
        GuiGraphicsRenderer.BatchArrays arrays = new GuiGraphicsRenderer.BatchArrays().forFrame(1);
        for (int i = 0; i < 1000; i++) {
            arrays.lend(new float[10], 10);
        }
        for (int i = 0; i < 5; i++) {
            arrays.lend(new float[200_000], 200_000);
        }
        int small = GuiGraphicsRenderer.BatchArrays.sizeClass(10);
        int big = GuiGraphicsRenderer.BatchArrays.sizeClass(200_000);
        assertEquals(1000, arrays.heldFloats(small), "every lent array is held while its frame is open");
        assertEquals(5, arrays.heldFloats(big));

        arrays.forFrame(2);
        assertEquals(GuiGraphicsRenderer.BatchArrays.retained(small), arrays.heldFloats(small));
        assertEquals(GuiGraphicsRenderer.BatchArrays.retained(big), arrays.heldFloats(big));
        assertTrue(GuiGraphicsRenderer.BatchArrays.retained(small) <= 256);
        assertEquals(2, GuiGraphicsRenderer.BatchArrays.retained(big), "two of the biggest at most");
        for (int c = GuiGraphicsRenderer.BatchArrays.MIN_CLASS; c <= GuiGraphicsRenderer.BatchArrays.MAX_CLASS; c++) {
            long bytes = (long) GuiGraphicsRenderer.BatchArrays.retained(c) * (4L << c);
            assertTrue(bytes <= 8L << 20, "class " + c + " keeps " + bytes + " bytes");
        }
    }

    @Test
    @DisplayName("a run too long for the biggest class is copied fresh and not kept")
    void aHugeRunIsCopiedFresh() {
        GuiGraphicsRenderer.BatchArrays arrays = new GuiGraphicsRenderer.BatchArrays().forFrame(1);
        int length = (1 << GuiGraphicsRenderer.BatchArrays.MAX_CLASS) + 1;
        float[] source = new float[length];
        source[length - 1] = 3F;
        float[] copied = arrays.lend(source, length);
        assertEquals(length, copied.length, "exactly the run, as the old copy was");
        assertEquals(3F, copied[length - 1]);
        assertNotSame(source, copied);
    }
}
