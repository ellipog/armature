package dev.ellipog.armature.client.render;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-pixel line rule, and the GPU's copy of it: the integer rounding the shader does, the band its quad
 * covers, and the pixels it keeps, each held to the walk the CPU draws.
 */
class HairlinesTest {

    @Test
    void theIntegerRoundingIsTheFloatRoundingForEveryShortSegment() {
        for (int c = 1; c <= 160; c++) {
            for (int d = -c; d <= c; d++) {
                for (int i = 0; i <= c; i++) {
                    assertEquals(Hairlines.step(d, i, c), Hairlines.exactStep(d, i, c),
                            "d=" + d + " i=" + i + " c=" + c);
                }
            }
        }
    }

    @Test
    void theIntegerRoundingHoldsUpToTheLimit() {
        Random random = new Random(20261031L);
        for (int n = 0; n < 400_000; n++) {
            int c = 1 + random.nextInt(Hairlines.EXACT_STEPS - 1);
            int d = random.nextInt(2 * c + 1) - c;
            int i = random.nextInt(c + 1);
            assertEquals(Hairlines.step(d, i, c), Hairlines.exactStep(d, i, c),
                    "d=" + d + " i=" + i + " c=" + c);
        }
        // The longest exact segment, at every step, for the slopes most likely to sit near a half.
        int c = Hairlines.EXACT_STEPS - 1;
        for (int d : new int[] {1, 2, c / 2, c / 2 + 1, c - 1, c, -1, -(c / 2), -(c - 1), -c}) {
            for (int i = 0; i <= c; i++) {
                assertEquals(Hairlines.step(d, i, c), Hairlines.exactStep(d, i, c), "d=" + d + " i=" + i);
            }
        }
    }

    @Test
    void theShadersTestNamesExactlyTheWalksPixels() {
        for (int dx = -14; dx <= 14; dx++) {
            for (int dy = -14; dy <= 14; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                Set<Long> walk = walk(dx, dy, 0);
                for (int x = -16; x <= 16; x++) {
                    for (int y = -16; y <= 16; y++) {
                        assertEquals(walk.contains(key(x, y)), Hairlines.onSegment(x, y, dx, dy),
                                "pixel " + x + "," + y + " of " + dx + "," + dy);
                    }
                }
            }
        }
    }

    @Test
    void theBandCoversEveryPixelItShouldAndTheShaderKeepsNoOther() {
        float[] band = new float[8];
        for (int dx = -14; dx <= 14; dx++) {
            for (int dy = -14; dy <= 14; dy++) {
                if (dx == 0 || dy == 0) {
                    continue;
                }
                for (int first = 0; first <= 1; first++) {
                    Hairlines.band(dx, dy, first, band);
                    Set<Long> expected = walk(dx, dy, first);
                    Set<Long> drawn = new HashSet<>();
                    for (int x = -18; x <= 18; x++) {
                        for (int y = -18; y <= 18; y++) {
                            int inside = samplesInside(band, x, y);
                            if (expected.contains(key(x, y))) {
                                // Whole, so the pixel draws the same at any zoom as the fill it replaces.
                                assertEquals(SAMPLES * SAMPLES, inside,
                                        "pixel " + x + "," + y + " of " + dx + "," + dy + " from " + first);
                            }
                            if (inside > 0 && Hairlines.onSegment(x, y, dx, dy)) {
                                drawn.add(key(x, y));
                            }
                        }
                    }
                    assertEquals(expected, drawn, dx + "," + dy + " from " + first);
                }
            }
        }
    }

    @Test
    void aRouteDrawsEachPixelOnceAndJointsOnlyOnce() {
        RecordingRenderer r = RecordingRenderer.create();
        int[] route = {0, 0, 9, 4, 9, 12, 2, 15, 2, 15, -6, 15};
        Hairlines.fill(r, 100, 50, route, 0xFF112233);
        Set<Long> seen = new HashSet<>();
        int area = 0;
        for (RecordingRenderer.Call fill : r.fills()) {
            for (int x = fill.x(); x < fill.x2(); x++) {
                for (int y = fill.y(); y < fill.y2(); y++) {
                    assertTrue(seen.add(key(x, y)), "pixel " + x + "," + y + " drawn twice");
                    area++;
                }
            }
        }
        // One pixel for the start, then one per step of each segment; the zero-length one adds none.
        assertEquals(1 + 9 + 8 + 7 + 0 + 8, area);
        assertTrue(seen.contains(key(100, 50)));
        assertTrue(seen.contains(key(94, 65)));
    }

    @Test
    void aLoneZeroLengthSegmentIsItsPixel() {
        RecordingRenderer r = RecordingRenderer.create();
        Hairlines.fill(r, 4, 7, new int[] {3, 3, 3, 3}, 0xFFFFFFFF);
        assertEquals(1, r.fills().size());
        assertTrue(r.covered(7, 10));
    }

    @Test
    void fillsAreRunsAlongTheLongerAxis() {
        RecordingRenderer r = RecordingRenderer.create();
        Hairlines.fill(r, 0, 0, new int[] {0, 0, 10, 2}, 0xFFFFFFFF);
        for (RecordingRenderer.Call fill : r.fills()) {
            assertEquals(1, fill.y2() - fill.y(), fill.toString());
        }
        assertEquals(3, r.fills().size());
        r.reset();
        Hairlines.fill(r, 0, 0, new int[] {0, 0, 2, -10}, 0xFFFFFFFF);
        for (RecordingRenderer.Call fill : r.fills()) {
            assertEquals(1, fill.x2() - fill.x(), fill.toString());
        }
        assertEquals(3, r.fills().size());
    }

    // ------------------------------------------------------------------

    private static final int SAMPLES = 4;

    /** The walk's pixels from step {@code first}, straight from the rule. */
    private static Set<Long> walk(int dx, int dy, int first) {
        int c = Hairlines.steps(dx, dy);
        Set<Long> out = new HashSet<>();
        for (int i = first; i <= c; i++) {
            out.add(key(Hairlines.step(dx, i, c), Hairlines.step(dy, i, c)));
        }
        return out;
    }

    /** How many of a pixel's sample points the band's quad holds, as a rasteriser would test them. */
    private static int samplesInside(float[] band, int x, int y) {
        int inside = 0;
        for (int sx = 0; sx < SAMPLES; sx++) {
            for (int sy = 0; sy < SAMPLES; sy++) {
                float px = x + (sx + 0.5F) / SAMPLES;
                float py = y + (sy + 0.5F) / SAMPLES;
                if (inConvexQuad(band, px, py)) {
                    inside++;
                }
            }
        }
        return inside;
    }

    private static boolean inConvexQuad(float[] q, float px, float py) {
        int sign = 0;
        for (int k = 0; k < 4; k++) {
            float ax = q[2 * k];
            float ay = q[2 * k + 1];
            float bx = q[(2 * k + 2) % 8];
            float by = q[(2 * k + 3) % 8];
            float cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
            int s = cross > 0 ? 1 : cross < 0 ? -1 : 0;
            if (s == 0) {
                return false;
            }
            if (sign == 0) {
                sign = s;
            }
            else if (s != sign) {
                return false;
            }
        }
        return true;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
    }
}
