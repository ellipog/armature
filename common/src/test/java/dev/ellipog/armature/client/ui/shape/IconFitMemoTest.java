package dev.ellipog.armature.client.ui.shape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The remembered icon fit: that it answers what the interface's arithmetic answers, at every size and
 * scale, in any order.
 *
 * <h2>Two references, and why both</h2>
 *
 * <p>The built-ins keep {@code maxInset} and {@code iconBox} per size, and the danger in a memo is the
 * one {@code SpanTableCacheTest} names: fast and wrong, where wrong here is an item drawn through its own
 * outline. So every answer is checked twice over:
 *
 * <ul>
 *   <li>Against <b>the interface's own default</b>, reached through a shape that forwards the rows and the
 *       anchor and overrides nothing else. That is the equivalence the memo has to keep, and it is what
 *       would notice the default starting to read the scale anywhere but the box it asks for, the one
 *       assumption the memo's key rests on.</li>
 *   <li>Against <b>a copy of the arithmetic as it was</b>, kept below as an oracle, so a later change to
 *       the default cannot quietly carry the memo along with it.</li>
 * </ul>
 *
 * <p>And the order matters as much as the values: a memo that is right on its first answer and wrong on
 * its second is the usual failure, so every case is asked twice in a row, scales that round to one box are
 * asked back to back, and a long run of mixed sizes forces the bounded memo to empty itself over and over.
 */
@DisplayName("the remembered icon fit")
class IconFitMemoTest {

    /** Every built-in by the name a file uses, so one cannot be left out of the sweep by not being listed. */
    private static final List<String> BUILT_INS = List.of(
            "rounded", "rect", "circle", "diamond", "hexagon",
            "octagon", "pentagon", "gear", "heart", "tome", "star");

    /** Angles a node can actually be drawn at, including ones that shrink the shape and ones that do not. */
    private static final double[] ANGLES = {20.0, 45.0, 137.0};

    /**
     * Scales in range, out of it, and pairs that round to one box at most sizes (0.5 and 0.5004), so a
     * shared entry is read back for a scale it was not computed from.
     */
    private static final double[] SCALES = {
            Shape.MIN_ICON_SCALE, 0.4, 0.5, 0.5004, 0.75, 0.9, Shape.MAX_ICON_SCALE,
            -3.0, 0.0, 4.0, Double.NaN, Double.POSITIVE_INFINITY};

    /** The sweep's largest size, as the change asked. */
    private static final int LARGEST = 128;

    /**
     * Every kind of shape the memo lives on: the unit shapes, turned ones (which carry the anchor round
     * with them), a shape from a function behind {@link Shapes#cached}, and a layer, which is one of those
     * too. A plain {@link Shapes#ofSpans} shape goes in as well, as the control that has no memo at all.
     */
    private static List<Named> shapes() {
        List<Named> out = new ArrayList<>();
        for (String name : BUILT_INS) {
            out.add(new Named(name, Shapes.byName(name, null)));
        }
        for (double angle : ANGLES) {
            for (String name : List.of("pentagon", "heart", "tome", "rect")) {
                Shape turned = Shapes.rotated(Shapes.byName(name, null), angle);
                out.add(new Named(name + " turned " + angle, turned));
            }
        }
        Shape cachedHeart = Shapes.cached(Shapes.ofSpans(Shapes.HEART::spansOf));
        out.add(new Named("a heart from a function, cached", cachedHeart));
        out.add(new Named("the circle's fill", Shapes.CIRCLE.inner()));
        out.add(new Named("a tome from a function, uncached", Shapes.ofSpans(Shapes.TOME::spansOf)));
        return out;
    }

    /** A shape and what to call it when it fails. */
    private record Named(String name, Shape shape) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The same rows and the same anchor, with every other method left to the interface, so its
     * {@code maxInset} and {@code iconBox} are the defaults, unremembered.
     */
    private static Shape defaults(Shape shape) {
        return new Shape() {
            @Override
            public int[] spansOf(int row, int size) {
                return shape.spansOf(row, size);
            }

            @Override
            public int[] spans(int row, int size) {
                return shape.spans(row, size);
            }

            @Override
            public int[] iconAnchor(int size) {
                return shape.iconAnchor(size);
            }
        };
    }

    // ------------------------------------------------------------------
    // The arithmetic as it was, kept as the oracle
    // ------------------------------------------------------------------

    /** {@code Shape.maxInset} as it stood before the memo. */
    private static int oldMaxInset(Shape shape, int size) {
        if (size <= 2) {
            return 0;
        }
        int deepest = (size - 1) / 2;
        int needed = 0;
        int answer = Math.max(0, size / 2 - 1);
        for (int inset = deepest; inset >= 0; inset--) {
            needed = Math.max(needed, oldNeed(shape, inset, size));
            needed = Math.max(needed, oldNeed(shape, size - 1 - inset, size));
            if (needed <= inset) {
                answer = inset;
            }
        }
        return answer;
    }

    private static int oldNeed(Shape shape, int row, int size) {
        int[] spans = shape.spans(row, size);
        if (spans == null) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < spans.length; i += 2) {
            best = Math.min(best, Math.max(spans[i], size - spans[i + 1]));
        }
        return best;
    }

    /** {@code Shape.iconBox} as it stood before the memo. */
    private static int[] oldIconBox(Shape shape, int nodeX, int nodeY, int size, double scale) {
        double clamped = Math.min(Math.max(scale, Shape.MIN_ICON_SCALE), Shape.MAX_ICON_SCALE);
        int wanted = Math.max(0, (int) Math.round(size * clamped));
        int largest = Math.max(0, size - oldMaxInset(shape, size) * 2);

        int[] anchor = shape.iconAnchor(size);
        int box = oldAnchoredBox(shape, Math.min(wanted, largest), size, anchor);
        if (box == 0 && (anchor[0] != 0 || anchor[1] != 0)) {
            anchor = new int[] {0, 0};
            box = Math.min(wanted, largest);
        }

        int left = oldPosition((size - box) / 2 + anchor[0], size, box);
        int top = oldPosition((size - box) / 2 + anchor[1], size, box);
        return new int[] {nodeX + left, nodeY + top, box};
    }

    private static int oldAnchoredBox(Shape shape, int start, int size, int[] anchor) {
        if (start <= 0) {
            return 0;
        }
        if (anchor[0] == 0 && anchor[1] == 0) {
            return start;
        }
        for (int box = start; box > 0; box--) {
            int left = oldPosition((size - box) / 2 + anchor[0], size, box);
            int top = oldPosition((size - box) / 2 + anchor[1], size, box);
            if (oldHosts(shape, box, left, top, size)) {
                return box;
            }
        }
        return 0;
    }

    private static boolean oldHosts(Shape shape, int box, int left, int top, int size) {
        for (int row = top; row < top + box; row++) {
            if (!oldCovers(shape.spans(row, size), left, left + box)) {
                return false;
            }
        }
        return true;
    }

    private static boolean oldCovers(int[] spans, int from, int to) {
        if (spans == null) {
            return false;
        }
        for (int i = 0; i < spans.length; i += 2) {
            if (spans[i] <= from && spans[i + 1] >= to) {
                return true;
            }
        }
        return false;
    }

    private static int oldPosition(int wanted, int size, int box) {
        return Math.max(0, Math.min(Math.max(0, size - box), wanted));
    }

    /** One case, asked of the memo twice and held to both references each time. */
    private static void assertSameFit(Named named, int nodeX, int nodeY, int size, double scale) {
        Shape shape = named.shape();
        String where = named + " at " + size + "px, scale " + scale + ", node " + nodeX + "," + nodeY;
        int[] expected = oldIconBox(shape, nodeX, nodeY, size, scale);
        assertArrayEquals(expected, defaults(shape).iconBox(nodeX, nodeY, size, scale),
                where + ": the interface's default no longer agrees with the arithmetic it replaced");
        assertArrayEquals(expected, shape.iconBox(nodeX, nodeY, size, scale), where + ", first ask");
        assertArrayEquals(expected, shape.iconBox(nodeX, nodeY, size, scale), where + ", remembered");
    }

    // ------------------------------------------------------------------
    // The sweeps
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the inset is the search's answer at every size, asked once and asked again")
    void theInsetIsTheSearch() {
        for (Named named : shapes()) {
            Shape shape = named.shape();
            for (int size = -1; size <= LARGEST; size++) {
                int expected = oldMaxInset(shape, size);
                assertEquals(expected, defaults(shape).maxInset(size), named + " at " + size + ", default");
                assertEquals(expected, shape.maxInset(size), named + " at " + size + ", first ask");
                assertEquals(expected, shape.maxInset(size), named + " at " + size + ", remembered");
            }
        }
    }

    @Test
    @DisplayName("the box is the interface's answer at every size from 0 to 128, and every scale")
    void theBoxIsTheInterfacesAnswer() {
        for (Named named : shapes()) {
            Shape shape = named.shape();
            for (int size = 0; size <= LARGEST; size++) {
                for (double scale : SCALES) {
                    assertSameFit(named, 0, 0, size, scale);
                }
                // The three-argument form is full scale through the same memo, and a node somewhere other
                // than the origin is the corner added to a remembered answer, not a second answer.
                assertArrayEquals(oldIconBox(shape, 312, -40, size, Shape.MAX_ICON_SCALE),
                        shape.iconBox(312, -40, size), named + " at " + size + ", full scale at a corner");
                assertSameFit(named, 1000 + size, 7 - size, size, 0.75);
            }
        }
    }

    @Test
    @DisplayName("asking in an order that keeps emptying the memo changes no answer")
    void evictionChangesNothing() {
        // Twelve sizes against a memo of eight, picked at random: entries are made, read back, dropped
        // when the memo empties itself, and made again, with every scale mixed in. Seeded, so a failure
        // is the same failure on the next run. The fresh shapes matter -- a memo that has never emptied
        // is the case the sweeps above already covered.
        int[] pool = {3, 12, 17, 24, 31, 32, 33, 48, 64, 97, 127, 128};
        Random random = new Random(0x5EED_F17L);
        Shape diamond = Shapes.unit((u, v, size) -> Math.abs(u) + Math.abs(v) <= 0.5);
        Shape cachedGear = Shapes.cached(Shapes.ofSpans(Shapes.GEAR::spansOf));
        List<Named> shapes = List.of(
                new Named("a unit diamond", diamond),
                new Named("pentagon turned 211", Shapes.rotated(Shapes.PENTAGON, 211.0)),
                new Named("heart turned 33", Shapes.rotated(Shapes.HEART, 33.0)),
                new Named("a gear from a function, cached", cachedGear));
        for (Named named : shapes) {
            for (int ask = 0; ask < 600; ask++) {
                int size = pool[random.nextInt(pool.length)];
                double scale = SCALES[random.nextInt(SCALES.length)];
                if (random.nextBoolean()) {
                    assertEquals(oldMaxInset(named.shape(), size), named.shape().maxInset(size),
                            named + " at " + size + ", ask " + ask);
                }
                assertSameFit(named, random.nextInt(2000) - 1000, random.nextInt(2000) - 1000, size, scale);
            }
        }
    }

    @Test
    @DisplayName("every answer is a fresh array, so a caller that changes one changes nobody else's")
    void everyAnswerIsFresh() {
        for (Shape shape : List.of(Shapes.CIRCLE, Shapes.HEART, Shapes.rotated(Shapes.TOME, 45.0))) {
            int[] first = shape.iconBox(10, 20, 48, 0.75);
            int[] second = shape.iconBox(10, 20, 48, 0.75);
            assertNotSame(first, second, shape + ": two asks handed out one array");

            int[] expected = oldIconBox(shape, 10, 20, 48, 0.75);
            first[0] = -999;
            first[1] = -999;
            first[2] = -1;
            assertArrayEquals(expected, shape.iconBox(10, 20, 48, 0.75),
                    shape + ": a caller's change to its own answer reached the memo");
            assertArrayEquals(expected, second, shape + ": or reached another caller's answer");
        }
    }
}
