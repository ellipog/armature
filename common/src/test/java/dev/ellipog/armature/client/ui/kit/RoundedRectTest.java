package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rounded-corner geometry, and the three ways it can be wrong at a small size.
 *
 * <h2>Why this is tested rather than looked at</h2>
 *
 * <p>Because every failure mode here produces a shape that is <i>nearly</i> right on a large node and
 * visibly broken on a small one, and the large case is the one a screenshot shows. A corner rounded
 * only at the top, a span that runs backwards at twelve pixels, a radius larger than half the shape —
 * all three are invisible at 48 pixels and all three are a node that looks wrong in a dense chapter.
 */
@DisplayName("Rounded rectangles")
class RoundedRectTest {

    @Test
    @DisplayName("the mid rows are full width and the corner rows are inset")
    void theShapeNarrowsAtItsEnds() {
        // The basic property: a rounded rectangle is widest in the middle and narrows at both ends,
        // symmetrically. The symmetry is worth asserting on its own because the first implementation
        // of this measured depth from the top only — which rounds the top two corners and leaves the
        // bottom two square, a mistake that reads as a shape drawn wrong rather than as arithmetic.
        int size = 24;
        int radius = 6;

        int[] top = RoundedRect.span(0, size, radius);
        int[] middle = RoundedRect.span(size / 2, size, radius);
        int[] bottom = RoundedRect.span(size - 1, size, radius);

        assertNotNull(top);
        assertNotNull(middle);
        assertNotNull(bottom);

        assertEquals(0, middle[0], "the middle row is not full width");
        assertEquals(size, middle[1]);
        assertTrue(top[0] > 0, "the top row was not inset, so the corners are square");

        // Symmetry, which is the whole of the depth calculation: the top and bottom rows inset by the
        // same amount, and every row is inset equally on both sides.
        assertEquals(top[0], bottom[0], "the top and bottom rows inset differently, so one end is square");
        assertEquals(size - top[1], top[0], "the row is not inset equally on both sides");
    }

    @Test
    @DisplayName("a radius of zero or more is a rectangle, and too much is a circle rather than an error")
    void radiusIsClampedToHalfTheSize() {
        // A radius larger than half the shape is what a caller produces when a size shrinks under a
        // radius that did not — a window dragged small, a theme with a fixed 8-pixel radius on a
        // 6-pixel control. Refusing it would throw during a resize; treating it as half is a circle,
        // which is the closest sensible shape and cannot invert.
        int[] noRadius = RoundedRect.span(0, 20, 0);
        assertNotNull(noRadius);
        assertEquals(0, noRadius[0], "a zero radius should be a plain rectangle");
        assertEquals(20, noRadius[1]);

        int[] excessive = RoundedRect.span(0, 20, 999);
        assertNotNull(excessive);
        assertTrue(excessive[0] >= 0, "an excessive radius produced a negative inset");

        // Half the size rounds the ends to a single pixel, which is a circle's top row.
        int[] half = RoundedRect.span(0, 20, 10);
        assertNotNull(half);
        assertTrue(half[1] - half[0] <= 2,
                "at radius = size/2 the top row should be about one pixel, and it is "
                        + (half[1] - half[0]));
    }

    @Test
    @DisplayName("no row is ever empty, at any size or radius")
    void noRowIsEverEmpty() {
        // The clamp that matters, and the one a screenshot cannot show: a zero-width row is a *hole* in
        // a drawn outline, because the four fills of a border are derived from these spans. At a size
        // of 12 a radius of 3 computes a top row of zero pixels, and the node's outline would have a
        // gap at its top edge.
        for (int size = 1; size <= 64; size++) {
            for (int radius = 0; radius <= size; radius++) {
                for (int row = 0; row < size; row++) {
                    int[] span = RoundedRect.span(row, size, radius);
                    assertNotNull(span, "a row inside the shape returned nothing: size " + size
                            + ", radius " + radius + ", row " + row);
                    assertTrue(span[1] > span[0],
                            "an empty span at size " + size + ", radius " + radius + ", row " + row);
                    assertTrue(span[0] >= 0 && span[1] <= size,
                            "a span left the square at size " + size + ", radius " + radius
                                    + ", row " + row + ": " + span[0] + " to " + span[1]);
                }
            }
        }
    }

    @Test
    @DisplayName("a row outside the shape returns nothing rather than clamping into it")
    void rowsOutsideTheShapeAreNull() {
        // Different from an empty span, and the difference is load-bearing: a caller walking rows past
        // the bottom needs to know it has finished, and a clamped span would draw the last row twice.
        assertNull(RoundedRect.span(-1, 20, 4));
        assertNull(RoundedRect.span(20, 20, 4));
        assertNull(RoundedRect.span(0, 0, 4));
        assertNull(RoundedRect.span(0, -5, 4));
    }

    @Test
    @DisplayName("the corner cut is symmetric top and bottom, and zero past the radius")
    void cornerCutIsSymmetric() {
        int size = 40;
        int radius = 8;

        for (int row = 0; row < radius; row++) {
            assertEquals(RoundedRect.cornerCut(row, size, radius),
                    RoundedRect.cornerCut(size - 1 - row, size, radius),
                    "the corner cut differs at row " + row + " and its mirror");
        }

        // Past the radius the corner has no effect at all, which is what makes the middle of the shape
        // a rectangle.
        assertEquals(0, RoundedRect.cornerCut(radius, size, radius));
        assertEquals(0, RoundedRect.cornerCut(radius + 5, size, radius));
    }

    @Test
    @DisplayName("the corner cut is monotonic, so no row is wider than the one inside it")
    void cornerCutNarrowsTowardsTheEdge() {
        // What makes a rounded rectangle convex, and therefore what makes the hit test and the drawing
        // agree without a second piece of arithmetic. A non-monotone cut would produce a shape that
        // bulges, and a click near the bulge would land outside the outline.
        int size = 48;
        int radius = 12;

        // Starts below every possible cut rather than at MAX_VALUE, which is what the first version did
        // — and the first assertion then compared the very first cut against it and failed, reporting
        // "the cut shrank at row 11" about a row that had nothing to shrank from. A monotonicity check
        // needs a sentinel that cannot exceed its first element, and getting that backwards produces a
        // failure message about the wrong thing entirely.
        int previous = Integer.MIN_VALUE;

        for (int row = radius - 1; row >= 0; row--) {
            int cut = RoundedRect.cornerCut(row, size, radius);
            assertTrue(cut >= previous,
                    "the cut shrank at row " + row + ", so the shape bulges outwards: " + cut
                            + " after " + previous);
            previous = cut;
        }

        // And the last row of the corner is the deepest cut of all, which is what makes the shape
        // recognisably round rather than nearly square.
        assertEquals(radius, RoundedRect.cornerCut(0, size, radius),
                "the topmost row should be inset by the full radius");
    }

    @Test
    @DisplayName("a quarter-radius cut matches the radius's own circle, by hand")
    void cornerCutMatchesTheCircleEquation() {
        // One row worked out longhand, so the arithmetic is checked against a person's answer rather
        // than against itself. At the very top row (depth 0) the cut is the full radius minus zero,
        // because the circle's widest point at that depth is exactly at the corner.
        //
        // At radius r and depth 0: cut = r - sqrt(r² - r²) = r - 0 = r. So the top row of a rounded
        // rectangle with radius 8 is inset by 8 — which is the sanity check that the shape is
        // recognisably round rather than nearly square.
        assertEquals(8, RoundedRect.cornerCut(0, 40, 8));
    }

    @Test
    @DisplayName("width() agrees with span() and reads zero outside the shape")
    void widthAgreesWithSpan() {
        // A second accessor for the same fact, so it must not be able to disagree — which it cannot,
        // because it is derived. Asserted anyway: it is the form most call sites want, and a "width"
        // that meant something else is exactly the kind of near-miss this codebase has paid for.
        for (int row = 0; row < 30; row += 3) {
            int[] span = RoundedRect.span(row, 30, 7);
            assertEquals(span[1] - span[0], RoundedRect.width(row, 30, 7));
        }
        assertEquals(0, RoundedRect.width(-1, 30, 7));
        assertEquals(0, RoundedRect.width(30, 30, 7));
    }
}
