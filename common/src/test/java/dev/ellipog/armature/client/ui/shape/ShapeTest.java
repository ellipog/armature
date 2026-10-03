package dev.ellipog.armature.client.ui.shape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape geometry, asserted by invariant rather than by number.
 *
 * <h2>Why invariants, and why a sweep</h2>
 *
 * <p>Pinning the span table row by row would be a test of the arithmetic against itself — it would pass
 * for any arithmetic that produced the same numbers, including a wrong one that had been copied into
 * the expectation. What matters is the set of properties every <i>caller</i> relies on:
 *
 * <ul>
 *   <li>Every span is non-empty, inside the square, sorted and disjoint, at <b>every</b> size. A
 *       zero-width row is a visible gap in an outline; a span past the edge is a node drawn over its
 *       neighbour; two overlapping spans are one piece of material drawn twice.</li>
 *   <li>A point is inside exactly when it is in a span for its row, asserted by walking every pixel —
 *       which is the whole reason the hit test and the drawing cannot disagree.</li>
 *   <li>The icon's square is the <b>largest</b> one that fits, checked by walking every row it covers —
 *       and one pixel less must not fit, which is what stops a search returning a comfortable zero.</li>
 * </ul>
 *
 * <p>The properties this file used to assert — top-to-bottom symmetry, and every row at least as wide
 * as the one nearer the edge — were true of the four shapes that existed and are false of the ones
 * added since. A heart is not symmetric about its middle, a pentagon's widest row is a third of the way
 * down, and a gear is wider at a tooth than at the gap below it. Those were never what the callers
 * relied on; they were what the old, cheaper icon search relied on, and that search is gone. What each
 * shape actually looks like is asserted per shape, below, where it can be stated rather than assumed.
 *
 * <p>So the sweep runs <b>every shape at every size from 1 to 80</b> plus some large odd ones. The
 * awkward cases are the small ones and the odd ones: at 12 pixels a circle's top row computes to
 * nothing, and at an odd size a symmetric shape can come out one pixel lopsided. A test at 48 pixels
 * only would pass on both.
 */
@DisplayName("Shape geometry")
class ShapeTest {

    /** Every built-in, so one cannot be forgotten in a sweep by not being listed. */
    private static final List<Shape> ALL = List.of(
            Shapes.ROUNDED, Shapes.RECT, Shapes.CIRCLE, Shapes.DIAMOND, Shapes.HEXAGON,
            Shapes.OCTAGON, Shapes.PENTAGON, Shapes.GEAR, Shapes.HEART, Shapes.TOME);

    private static List<Integer> sizes() {
        List<Integer> out = new ArrayList<>();
        for (int size = 1; size <= 80; size++) {
            out.add(size);
        }
        out.addAll(List.of(97, 128, 199, 512));
        return out;
    }

    /** The first span of a row, for the shapes whose rows have exactly one. */
    private static int[] first(Shape shape, int row, int size) {
        int[] spans = shape.spans(row, size);
        assertNotNull(spans, shape + " " + size + " row " + row + " covers nothing");
        return new int[] {spans[0], spans[1]};
    }

    /** How many columns a row covers, across all its spans. */
    private static int width(Shape shape, int row, int size) {
        int[] spans = shape.spans(row, size);
        if (spans == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < spans.length; i += 2) {
            total += spans[i + 1] - spans[i];
        }
        return total;
    }

    /** Whether any span of a row covers the whole interval {@code [from, to)}. */
    private static boolean covers(int[] spans, int from, int to) {
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

    /** How many columns differ between two shapes on one row. */
    private static int difference(Shape a, Shape b, int row, int size) {
        int[] left = a.spans(row, size);
        int[] right = b.spans(row, size);
        int differing = 0;
        for (int col = 0; col < size; col++) {
            if (covers(left, col, col + 1) != covers(right, col, col + 1)) {
                differing++;
            }
        }
        return differing;
    }

    // ------------------------------------------------------------------
    // Invariants
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("invariants, over every shape and every size")
    class Invariants {

        @Test
        @DisplayName("no span is ever empty, outside the square, out of order or overlapping")
        void spansAreWellFormed() {
            // A row inside the square may have no material at all -- a gear's teeth are sub-pixel at
            // four pixels, and a shape need not touch its bounding square's edges. What it may not do is
            // return a span that is empty, out of bounds, out of order or overlapping: those are the
            // four properties every caller reads as read.
            for (Shape shape : ALL) {
                for (int size : sizes()) {
                    for (int row = 0; row < size; row++) {
                        int[] spans = shape.spans(row, size);
                        if (spans == null) {
                            continue;
                        }
                        assertEquals(0, spans.length % 2, shape + " " + size + " row " + row
                                + ": an odd number of endpoints is not a list of spans");
                        int previousEnd = -1;
                        for (int i = 0; i < spans.length; i += 2) {
                            int from = spans[i];
                            int to = spans[i + 1];
                            assertTrue(from >= 0, shape + " " + size + " row " + row + ": from < 0");
                            assertTrue(from < to,
                                    shape + " " + size + " row " + row + ": empty span " + from + ".." + to);
                            assertTrue(to <= size,
                                    shape + " " + size + " row " + row + ": to " + to + " past the edge");
                            assertTrue(from > previousEnd, shape + " " + size + " row " + row
                                    + ": spans " + previousEnd + " and " + from
                                    + " are out of order or overlapping");
                            previousEnd = to;
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("every shape that fills its square has material on every row of it")
        void shapesThatFillTheirSquareDoSoOnEveryRow() {
            // The safety net the old sweep had, kept and now true of every shape: a row that suddenly
            // came back empty would be a hole in a silhouette, which is a rendering fault rather than a
            // small shape. It is still not an interface property -- see the well-formedness test -- but
            // every built-in has material on every row of its square, including the sub-pixel cases,
            // which {@code spans} widens to a pixel.
            //
            // The two sampled shapes are not in this sweep at all, and that is not a concession: a
            // gear has <b>gaps</b> between its teeth, so a row through a gap above the root circle has
            // no material on it by design, and a predicate asked about pixel centres misses a feature
            // thinner than the distance between two of them. What holds them to account instead is the
            // icon-fit sweep below (a shape with no material anywhere fits no square), the gear's own
            // teeth-and-gaps test, and the heart's notch test.
            for (Shape shape : ALL) {
                if (shape == Shapes.GEAR || shape == Shapes.HEART) {
                    continue;
                }
                for (int size : sizes()) {
                    for (int row = 0; row < size; row++) {
                        assertNotNull(shape.spans(row, size),
                                shape + " " + size + " has no material on row " + row);
                    }
                }
            }
        }

        @Test
        @DisplayName("a row outside the shape has no spans")
        void rowsOutsideHaveNoSpans() {
            // Different from an empty span, and the difference is load-bearing: a caller walking rows
            // past the bottom needs to know it has finished. A clamped span would draw the last row
            // twice.
            for (Shape shape : ALL) {
                assertNull(shape.spans(-1, 20), shape + " had a span above itself");
                assertNull(shape.spans(20, 20), shape + " had a span at its bottom edge");
                assertNull(shape.spans(0, 0), shape + " had a span in a zero-size square");
                assertNull(shape.spans(0, -5), shape + " had a span in a negative square");
            }
        }

        @Test
        @DisplayName("a point is inside exactly when it is in a span for its row, over every pixel")
        void containsAgreesWithSpans() {
            // The property that makes the hit test trustworthy, asserted directly rather than assumed:
            // whatever spans say to fill, contains says is clickable. A separate inequality would agree
            // almost everywhere and differ by a pixel at the edges, which shows up as a node that
            // refuses a click on its own border.
            for (Shape shape : ALL) {
                for (int size : List.of(12, 26, 33, 48, 64)) {
                    for (int row = 0; row < size; row++) {
                        int[] spans = shape.spans(row, size);
                        for (int col = 0; col < size; col++) {
                            boolean inSpan = covers(spans, col, col + 1);
                            assertEquals(inSpan, shape.containsLocal(col + 0.5, row + 0.5, size),
                                    shape + " " + size + " at " + col + "," + row);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("a fractional point outside the square is never inside, including just above it")
        void fractionalPointsOutsideAreNeverInside() {
            // The negative-coordinate case specifically, and the reason {@code containsLocal} floors
            // rather than casts. A cast truncates towards zero, so a local y of -0.4 lands on row 0 and
            // reads as inside -- which is how a click a fraction of a pixel above a node selects it.
            for (Shape shape : ALL) {
                assertTrue(!shape.containsLocal(-0.4, 5.0, 48), shape + " accepted a negative x");
                assertTrue(!shape.containsLocal(5.0, -0.4, 48), shape + " accepted a negative y");
                assertTrue(!shape.containsLocal(-1.0, -1.0, 48), shape + " accepted outside");
                assertTrue(!shape.containsLocal(48.0, 5.0, 48), shape + " accepted x past the edge");
                assertTrue(!shape.containsLocal(5.0, 48.0, 48), shape + " accepted y past the edge");
            }
        }
    }

    // ------------------------------------------------------------------
    // What each shape looks like
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("each shape")
    class Each {

        @Test
        @DisplayName("RECT is a rectangle, at every size and row")
        void rectIsARectangle() {
            for (int size : sizes()) {
                for (int row = 0; row < size; row++) {
                    int[] span = first(Shapes.RECT, row, size);
                    assertEquals(0, span[0]);
                    assertEquals(size, span[1]);
                }
            }
        }

        @Test
        @DisplayName("ROUNDED is full width through its middle and cut at both corners")
        void roundedHasCutCorners() {
            int size = 48;
            int[] middle = first(Shapes.ROUNDED, size / 2, size);
            assertEquals(0, middle[0], "the middle row should reach the left edge");
            assertEquals(size, middle[1], "the middle row should reach the right edge");

            int[] top = first(Shapes.ROUNDED, 0, size);
            assertEquals(12, top[0], "a quarter-radius corner at 48 pixels should cut in by 12");
            assertEquals(top[0], size - top[1], "the two corners should match");
            assertEquals(0, first(Shapes.ROUNDED, 12, size)[0], "the cut should be gone by the radius");
        }

        @Test
        @DisplayName("a proportional radius divides in integers, so an existing size is unchanged")
        void proportionalRadiusKeepsIntegerDivision() {
            // The one-pixel case that makes the divisor form worth having. At 18 pixels, integer division
            // gives 4 and `(int) (18 * 0.25)` rounds to 5 -- a pixel of corner, invisible in a screenshot
            // and a change to every existing node's appearance. Asserted because the difference is
            // exactly the kind of thing a later "simplification" to a float would erase silently.
            assertEquals(4, 18 / 4, "fixture sanity: integer division at 18 gives 4");
            assertEquals(5, (int) Math.round(18 * 0.25), "and the float form gives 5");

            // The shape must agree with the integer form.
            assertEquals(4, first(Shapes.roundedProportional(4), 0, 18)[0],
                    "the proportional radius used the float form, changing every existing node");
        }

        @Test
        @DisplayName("a fixed radius is the same at every size, which is what a panel wants")
        void aFixedRadiusDoesNotScale() {
            // The reason both forms exist. A panel 400 pixels wide should have the same corner as one
            // 120 wide; a node should not. Neither is the default for the other.
            for (int size : List.of(40, 80, 200)) {
                assertEquals(6, first(Shapes.rounded(6), 0, size)[0],
                        "a fixed 6-pixel radius cut by " + first(Shapes.rounded(6), 0, size)[0]
                                + " at size " + size);
            }
        }

        @Test
        @DisplayName("a radius of zero or less is a rectangle, which is a real answer rather than an error")
        void aNonPositiveRadiusIsARectangle() {
            // Reachable from a caller deriving a radius from a size that shrank to nothing. Throwing
            // during a resize would take the frame with it.
            assertSame(Shapes.RECT, Shapes.rounded(0));
            assertSame(Shapes.RECT, Shapes.rounded(-4));
        }

        @Test
        @DisplayName("CIRCLE is widest through the middle and narrowest at the top")
        void circleIsRound() {
            int size = 48;
            int[] middle = first(Shapes.CIRCLE, size / 2, size);
            int[] top = first(Shapes.CIRCLE, 0, size);

            assertTrue(middle[1] - middle[0] > size * 0.9, "the middle should be nearly full width");
            assertTrue(top[1] - top[0] < size * 0.4, "the top row should be a narrow cap");
            assertTrue(middle[0] <= 1 && middle[1] >= size - 1, "the middle should touch both edges");
        }

        @Test
        @DisplayName("CIRCLE's inscribed square is size/sqrt(2), as a pencil would give")
        void circleInscribedSquareMatchesTheMaths() {
            // The strongest available check on maxInset, because there is an independent closed form:
            // the largest square inside a circle of diameter d has side d/sqrt(2), so the inset is
            // (d - d/sqrt(2)) / 2. For 48 that is 7.03, so 7.
            for (int size : List.of(24, 48, 96)) {
                int inset = Shapes.CIRCLE.maxInset(size);
                double expected = (size - size / Math.sqrt(2)) / 2.0;
                assertTrue(Math.abs(inset - expected) <= 1.5,
                        "circle " + size + " gave inset " + inset + ", expected about " + expected);
            }
        }

        @Test
        @DisplayName("HEXAGON tapers in a straight line rather than an arc")
        void hexagonTapersLinearly() {
            // The whole visual difference between a hexagon and a rounded rectangle at these sizes, and
            // they are hard to tell apart in a small picture -- which is why this asserts equal steps
            // rather than merely that the shape narrows. An arc starts slow and accelerates; this does
            // not.
            int size = 48;
            assertEquals(0, first(Shapes.HEXAGON, size / 2, size)[0], "the middle should reach the edge");

            int a = first(Shapes.HEXAGON, 0, size)[0];
            int b = first(Shapes.HEXAGON, 1, size)[0];
            int c = first(Shapes.HEXAGON, 2, size)[0];
            assertEquals(b - c, a - b, "the taper should be linear");
            assertTrue(a > c, "the taper should be narrowing towards the edge");
        }

        @Test
        @DisplayName("DIAMOND is a point at each end and full width through the middle")
        void diamondIsPointed() {
            int size = 48;
            assertTrue(width(Shapes.DIAMOND, 0, size) <= 2, "the top should be a point");
            assertTrue(width(Shapes.DIAMOND, size - 1, size) <= 2, "and so should the bottom");
            int[] middle = first(Shapes.DIAMOND, size / 2, size);
            assertEquals(0, middle[0], "the middle should reach the left edge");
            assertEquals(size, middle[1], "the middle should reach the right edge");

            // Symmetric top to bottom, and asserted as such because the taper is measured from the
            // nearer edge -- a one-sided taper would be a wedge.
            for (int row = 0; row < size; row++) {
                int[] top = Shapes.DIAMOND.spans(row, size);
                int[] bottom = Shapes.DIAMOND.spans(size - 1 - row, size);
                assertEquals(top[0], bottom[0], "row " + row + " and its mirror disagree on from");
                assertEquals(top[1], bottom[1], "row " + row + " and its mirror disagree on to");
            }
        }

        @Test
        @DisplayName("OCTAGON cuts its corners in a straight 45-degree line")
        void octagonChamfersStraight() {
            int size = 48;
            int cut = Math.max(1, size / 3);
            assertEquals(cut, first(Shapes.OCTAGON, 0, size)[0], "the top should be cut by a third");
            assertEquals(0, first(Shapes.OCTAGON, cut, size)[0], "the chamfer should be gone by the cut");
            for (int row = 1; row <= cut; row++) {
                int previous = first(Shapes.OCTAGON, row - 1, size)[0];
                int current = first(Shapes.OCTAGON, row, size)[0];
                assertEquals(1, previous - current, "row " + row + " should step in by exactly one");
            }
        }

        @Test
        @DisplayName("OCTAGON is visibly different from ROUNDED, not a second name for it")
        void octagonIsDistinctFromRounded() {
            // Two shapes that differ by a pixel are two shapes an author cannot tell apart, and one is
            // then pointless. The chamfer is a third of the size against the rounded rectangle's
            // quarter, and a line against an arc, so the corners differ by a clear margin.
            int size = 48;
            int octagonCut = first(Shapes.OCTAGON, 0, size)[0];
            int roundedCut = first(Shapes.ROUNDED, 0, size)[0];
            assertTrue(octagonCut >= roundedCut + 3,
                    "OCTAGON's corner cut " + octagonCut + " is too close to ROUNDED's " + roundedCut);
        }

        @Test
        @DisplayName("PENTAGON is a point up with a flat base, and deliberately asymmetric")
        void pentagonPointsUp() {
            int size = 48;
            assertTrue(width(Shapes.PENTAGON, 0, size) <= 2, "the top should be a point");
            assertEquals(size, width(Shapes.PENTAGON, (int) Math.round(size * 0.30), size),
                    "the shoulders should be full width");
            assertTrue(width(Shapes.PENTAGON, size - 1, size) > size * 0.55,
                    "the base should be flat and wide, not a second point");
            assertTrue(width(Shapes.PENTAGON, size - 1, size) < size * 0.75,
                    "the base should be narrower than the shoulders");
            // The asymmetry is the read: a pentagon with a flat top would be a different shape.
            assertTrue(width(Shapes.PENTAGON, 0, size) < width(Shapes.PENTAGON, size - 1, size),
                    "the point should be at the top and the flat base at the bottom");
        }

        @Test
        @DisplayName("GEAR has teeth standing off its hub, so its outline is not monotone")
        void gearHasTeeth() {
            // The property that forced maxInset to be rewritten: a row through a tooth is wider than
            // the row nearer the middle, so a search that only checked the edges of a candidate square
            // would put the icon through the outline.
            int size = 48;
            boolean narrowsInward = false;
            for (int row = 0; row < size / 2; row++) {
                if (width(Shapes.GEAR, row, size) > width(Shapes.GEAR, row + 1, size)) {
                    narrowsInward = true;
                    break;
                }
            }
            assertTrue(narrowsInward, "no row is wider than the one below it, so there are no teeth");

            // And the gaps between the teeth: the widest row is a tooth reaching the edge, and the row
            // between two teeth is much narrower. Found by measuring rather than by naming a row -- the
            // gap is between the top tooth and its neighbours, and where that falls depends on the
            // tooth count, which depends on the size.
            int widest = 0;
            for (int row = 0; row < size; row++) {
                widest = Math.max(widest, width(Shapes.GEAR, row, size));
            }
            // Within a pixel of the edge rather than exactly on it: the tip circle touches the square
            // at four points, and a pixel centre never lands on one -- the outermost pixel whose centre
            // is inside the tip circle is half a pixel in. A sampled shape's boundary is where the curve
            // is, not where the box is.
            assertTrue(widest >= size - 2,
                    "a tooth at the compass points should reach the edge, and the widest row is "
                            + widest + " of " + size);

            int narrowest = size;
            for (int row = size / 6; row <= size / 3; row++) {
                narrowest = Math.min(narrowest, width(Shapes.GEAR, row, size));
            }
            assertTrue(narrowest <= widest - 8,
                    "the widest row is " + widest + " and the narrowest in the upper third is "
                            + narrowest + ", which is not a visible gap between two teeth");
        }

        @Test
        @DisplayName("HEART has a notch: two spans on its top rows, one below them")
        void heartHasANotch() {
            // The shape that made rows plural. If the two lobes ever merge all the way up, the heart
            // is a shield -- which is a different shape and would need a different name.
            int size = 48;
            boolean twoSpans = false;
            for (int row = 0; row < size / 4; row++) {
                int[] spans = Shapes.HEART.spans(row, size);
                if (spans.length == 4) {
                    twoSpans = true;
                    break;
                }
            }
            assertTrue(twoSpans, "no row of the heart has two lobes, so there is no notch");
            assertEquals(2, Shapes.HEART.spans(size / 4, size).length,
                    "below the notch a row should be one piece of material");
            assertTrue(width(Shapes.HEART, size - 1, size) <= 4, "the heart should come to a point");
            assertTrue(width(Shapes.HEART, size / 2, size) > size * 0.5,
                    "the heart's body should be wide");
        }

        @Test
        @DisplayName("TOME has a flat spine on the left and a rounded fore-edge on the right")
        void tomeIsABookWithAFlatSpine() {
            // The asymmetry is the read, and the first version had it backwards: it gave the *left* the
            // larger radius, producing a quarter cut out of the top-left -- at 48 pixels the top row ran
            // from x=24 to x=40, a bar floating right of centre. Six pixels of asymmetry sounded
            // plausible and looked like a broken shape.
            int size = 48;
            for (int row = 0; row < size; row++) {
                assertEquals(0, first(Shapes.TOME, row, size)[0],
                        "row " + row + ": the spine should be a straight edge at x=0");
            }
            assertEquals(size, first(Shapes.TOME, size / 2, size)[1], "the middle should reach the right");
            assertTrue(first(Shapes.TOME, 0, size)[1] < size, "the top should be cut on the right");
        }

        @Test
        @DisplayName("TOME is visibly narrower at its corners than ROUNDED is")
        void tomeIsDistinctFromRounded() {
            // Two shapes that differ by a pixel are two shapes an author cannot tell apart, and one is
            // then pointless. A third-of-the-size fore-edge against a quarter means the corners differ
            // by a clear margin.
            int size = 48;
            int tomeCut = size - first(Shapes.TOME, 0, size)[1];
            int roundedCut = first(Shapes.ROUNDED, 0, size)[0];
            assertTrue(tomeCut > roundedCut + 3,
                    "TOME's corner cut " + tomeCut + " is too close to ROUNDED's " + roundedCut);
        }

        @Test
        @DisplayName("no two shapes are within three pixels of each other anywhere")
        void everyShapeIsDistinct() {
            // A picker of near-duplicates is a picker an author cannot choose from: two names that
            // draw the same picture are one shape with two names. Three pixels on some row, measured as
            // the columns covered by exactly one of the two, because equal widths in different places
            // are still two different shapes.
            int size = 48;
            for (int a = 0; a < ALL.size(); a++) {
                for (int b = a + 1; b < ALL.size(); b++) {
                    int worst = 0;
                    for (int row = 0; row < size; row++) {
                        worst = Math.max(worst, difference(ALL.get(a), ALL.get(b), row, size));
                    }
                    assertTrue(worst >= 3, ALL.get(a) + " and " + ALL.get(b)
                            + " differ by only " + worst + " pixels, so they are one shape");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Rotation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rotation")
    class Rotation {

        /** A few angles, including one that is not a multiple of anything. */
        private static final List<Double> ANGLES = List.of(30.0, 90.0, 137.0, 180.0, 270.0);

        @Test
        @DisplayName("zero and 360 degrees are the shape itself, not a copy of it")
        void aFullTurnIsTheShapeItself() {
            // Not an optimisation: a rotation of nothing must not pay for a sampled table, and the
            // identity is the one rotation a caller can be sure of.
            for (Shape shape : ALL) {
                assertSame(shape, Shapes.rotated(shape, 0), shape + " was copied by a zero turn");
                assertSame(shape, Shapes.rotated(shape, 360), shape + " was copied by a full turn");
                assertSame(shape, Shapes.rotated(shape, -360), shape + " was copied by a negative turn");
            }
        }

        @Test
        @DisplayName("a quarter turn of a rectangle is the same rectangle")
        void aQuarterTurnOfARectangleIsItself() {
            // The strongest available check that the turn is a turn and not a shear or a flip: a
            // rectangle has fourfold symmetry, so its spans must come back unchanged, row for row.
            for (int size : List.of(16, 26, 33, 48, 64)) {
                Shape turned = Shapes.rotated(Shapes.RECT, 90);
                for (int row = 0; row < size; row++) {
                    int[] base = Shapes.RECT.spans(row, size);
                    int[] there = turned.spans(row, size);
                    assertNotNull(there, "the turned rectangle lost row " + row + " of " + size);
                    assertTrue(Math.abs(base[0] - there[0]) <= 1 && Math.abs(base[1] - there[1]) <= 1,
                            "a quarter turn moved the rectangle's row " + row + " of " + size
                                    + " from " + base[0] + ".." + base[1] + " to "
                                    + there[0] + ".." + there[1]);
                }
            }
        }

        @Test
        @DisplayName("a circle is unchanged by any angle")
        void aCircleIsUnchangedByAnyAngle() {
            // A circle is the shape with no orientation at all, so every angle must agree with zero --
            // which is the check that the sampling is not losing material as the curve turns.
            for (double angle : ANGLES) {
                Shape turned = Shapes.rotated(Shapes.CIRCLE, angle);
                for (int size : List.of(24, 48)) {
                    int widest = 0;
                    int widestThere = 0;
                    for (int row = 0; row < size; row++) {
                        widest = Math.max(widest, width(Shapes.CIRCLE, row, size));
                        widestThere = Math.max(widestThere, width(turned, row, size));
                    }
                    assertTrue(Math.abs(widest - widestThere) <= 2,
                            "a circle turned " + angle + " degrees measures " + widestThere
                                    + " where it measured " + widest + " at " + size);
                }
            }
        }

        @Test
        @DisplayName("every turned shape still answers every invariant the callers rely on")
        void aTurnedShapeIsStillAShape() {
            // The sweeps again, over turned shapes: a row's spans well formed and in order, a point
            // inside exactly when it is in a span for its row, and an icon square that fits and is the
            // largest that does. A rotation that quietly produced overlapping runs would draw a node
            // twice and hit-test it once.
            for (Shape base : ALL) {
                for (double angle : ANGLES) {
                    Shape shape = Shapes.rotated(base, angle);
                    for (int size : List.of(12, 26, 33, 48, 64, 97)) {
                        for (int row = 0; row < size; row++) {
                            int[] spans = shape.spans(row, size);
                            if (spans == null) {
                                continue;
                            }
                            int previousEnd = -1;
                            for (int i = 0; i < spans.length; i += 2) {
                                assertTrue(spans[i] >= 0 && spans[i] < spans[i + 1]
                                                && spans[i + 1] <= size && spans[i] > previousEnd,
                                        base + " turned " + angle + " at " + size + " row " + row
                                                + " has a malformed span " + spans[i] + ".." + spans[i + 1]);
                                previousEnd = spans[i + 1];
                            }
                            for (int col = 0; col < size; col++) {
                                assertEquals(covers(spans, col, col + 1),
                                        shape.containsLocal(col + 0.5, row + 0.5, size),
                                        base + " turned " + angle + " at " + size + " " + col + "," + row
                                                + ": the hit test and the drawing disagree");
                            }
                        }
                        int inset = shape.maxInset(size);
                        for (int row = inset; row <= size - 1 - inset; row++) {
                            assertTrue(covers(shape.spans(row, size), inset, size - inset),
                                    base + " turned " + angle + " at " + size
                                            + ": the icon square does not fit");
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("turning a gear moves its teeth, and turning it back is the gear again")
        void turningAGearMovesItsTeeth() {
            // A rotation that did nothing would pass every invariant above -- a shape is still a shape
            // if it never moved. This is the one that says the pixels changed.
            int size = 48;
            Shape gear = Shapes.GEAR;
            Shape turned = Shapes.rotated(gear, 15);
            int different = 0;
            for (int row = 0; row < size; row++) {
                different += difference(gear, turned, row, size);
            }
            assertTrue(different > size, "a fifteen-degree turn moved " + different + " pixel(s) of a"
                    + " forty-eight pixel gear, which is not a turn");
        }
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("names")
    class Names {

        @Test
        @DisplayName("every name a data file would use resolves to the shape of that name")
        void namesResolve() {
            assertSame(Shapes.ROUNDED, Shapes.byName("rounded", Shapes.RECT));
            assertSame(Shapes.CIRCLE, Shapes.byName("circle", Shapes.RECT));
            assertSame(Shapes.HEXAGON, Shapes.byName("hexagon", Shapes.RECT));
            assertSame(Shapes.TOME, Shapes.byName("tome", Shapes.RECT));
            assertSame(Shapes.DIAMOND, Shapes.byName("diamond", Shapes.RECT));
            assertSame(Shapes.OCTAGON, Shapes.byName("octagon", Shapes.RECT));
            assertSame(Shapes.PENTAGON, Shapes.byName("pentagon", Shapes.RECT));
            assertSame(Shapes.GEAR, Shapes.byName("gear", Shapes.RECT));
            assertSame(Shapes.HEART, Shapes.byName("heart", Shapes.RECT));
            assertSame(Shapes.RECT, Shapes.byName("rectangle", Shapes.CIRCLE));
            assertSame(Shapes.RECT, Shapes.byName("square", Shapes.CIRCLE));
        }

        @Test
        @DisplayName("a name is matched whatever its capitalisation")
        void namesAreCaseInsensitive() {
            // A hand-written file's capitalisation is not a thing to be strict about, and the file format
            // spells these lowercase.
            assertSame(Shapes.CIRCLE, Shapes.byName("Circle", Shapes.RECT));
            assertSame(Shapes.HEXAGON, Shapes.byName("HEXAGON", Shapes.RECT));
            assertSame(Shapes.TOME, Shapes.byName("ToMe", Shapes.RECT));
            assertSame(Shapes.HEART, Shapes.byName("Heart", Shapes.RECT));
        }

        @Test
        @DisplayName("an unknown name falls back rather than throwing")
        void anUnknownNameFallsBack() {
            // A payload from a server running a newer version can name a shape this client has never
            // heard of, and drawing the fallback is obviously better than a screen that throws while a
            // player is standing in front of it. Different from the validator's decision, which does
            // error -- there the author can still fix it.
            assertSame(Shapes.ROUNDED, Shapes.byName("dodecahedron", Shapes.ROUNDED));
            assertSame(Shapes.ROUNDED, Shapes.byName("", Shapes.ROUNDED));
            assertSame(Shapes.ROUNDED, Shapes.byName(null, Shapes.ROUNDED));
        }
    }

    // ------------------------------------------------------------------
    // The icon fit
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the icon fit")
    class IconFit {

        @Test
        @DisplayName("the square at the inset fits inside the shape")
        void theIconSquareFits() {
            for (Shape shape : ALL) {
                for (int size : List.of(12, 20, 26, 33, 48, 64, 128)) {
                    int inset = shape.maxInset(size);
                    for (int row = inset; row <= size - 1 - inset; row++) {
                        int[] spans = shape.spans(row, size);
                        assertTrue(covers(spans, inset, size - inset),
                                shape + " " + size + " inset " + inset + ": row " + row
                                        + " does not cover it");
                    }
                }
            }
        }

        @Test
        @DisplayName("one pixel less does not fit, so the answer is the largest and not merely an answer")
        void theIconSquareIsAsLargeAsItCanBe() {
            // The tightness assertion, and the one that catches a search running the wrong way. The
            // first implementation returned on the first *failure*, which for a circle returned 0 -- a
            // full-size icon hanging well outside the outline. "It fits" alone would have passed that.
            for (Shape shape : ALL) {
                for (int size : List.of(12, 26, 48, 64)) {
                    int inset = shape.maxInset(size);
                    if (inset == 0) {
                        continue;
                    }
                    int smaller = inset - 1;
                    boolean fits = true;
                    for (int row = smaller; row <= size - 1 - smaller; row++) {
                        if (!covers(shape.spans(row, size), smaller, size - smaller)) {
                            fits = false;
                            break;
                        }
                    }
                    assertTrue(!fits, shape + " " + size + ": inset " + smaller + " also fits, so "
                            + inset + " was not the largest");
                }
            }
        }

        @Test
        @DisplayName("a circle needs a bigger inset than a rounded rectangle")
        void aCircleNeedsMoreRoomThanARoundedRectangle() {
            // The reason the inset cannot be one constant, stated as a test. One number for both would
            // either spill the icon outside the circle or waste a fifth of the rounded rectangle.
            for (int size : List.of(24, 48, 96, 128)) {
                assertTrue(Shapes.CIRCLE.maxInset(size) > Shapes.ROUNDED.maxInset(size),
                        "at " + size + " the circle should need more inset than the rounded rectangle");
            }
        }

        @Test
        @DisplayName("the inset always leaves a non-empty box, at any size a node can be drawn at")
        void theInsetAlwaysLeavesRoom() {
            // size >= 3, and the boundary matters. At 2 pixels there is no shape to speak of and the
            // correct inset is 0 -- which a version of this clamped up to 1, leaving a box of
            // 2 - 2 = 0 and drawing a zero-pixel item. The floor was in the code while the javadoc
            // above it claimed there was no floor: a comment and its code disagreeing.
            for (Shape shape : ALL) {
                assertEquals(0, shape.maxInset(2), shape + " at size 2 should give no inset, not a floor");
                for (int size = 3; size <= 80; size++) {
                    int inset = shape.maxInset(size);
                    assertTrue(size - inset * 2 >= 1,
                            shape + " " + size + ": inset " + inset + " leaves a box of "
                                    + (size - inset * 2) + " pixels");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The icon box
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the icon box")
    class IconBox {

        @Test
        @DisplayName("the box is centred: the margins either side agree")
        void theIconBoxIsCentred() {
            // A bug that shipped, and the reason this is one method rather than two numbers at the call
            // site. A screen took the box's SIZE from the inset -- 6 for a 48-pixel circle -- and its
            // POSITION from a hardcoded constant of 3. So a 36-pixel item was drawn 3 pixels in from the
            // corner instead of 6: three pixels up and left of centre, with its corner through the
            // rounded outline. It looked like an icon that "wasn't centred", which is exactly what it was.
            //
            // Asserted for every shape and every size including the odd ones, where the subtraction
            // cannot come out even and the leftover pixel has to be split by centring explicitly.
            for (Shape shape : ALL) {
                for (int size = 3; size <= 80; size++) {
                    int[] box = shape.iconBox(100, 200, size);
                    int left = box[0] - 100;
                    int top = box[1] - 200;
                    int right = size - left - box[2];
                    int bottom = size - top - box[2];

                    assertEquals(left, top, shape + " " + size + ": left margin != top margin");
                    assertTrue(Math.abs(left - right) <= 1,
                            shape + " " + size + ": margins " + left + " and " + right + " differ by more"
                                    + " than the odd pixel");
                    assertTrue(Math.abs(top - bottom) <= 1,
                            shape + " " + size + ": margins " + top + " and " + bottom + " differ by more"
                                    + " than the odd pixel");
                    assertTrue(left >= 0 && box[2] >= 0 && left + box[2] <= size,
                            shape + " " + size + ": box " + box[2] + " at " + left + " leaves the node");
                }
            }
        }

        @Test
        @DisplayName("the box is inside the shape, at every size")
        void theIconBoxIsInsideTheShape() {
            // The other half: centred is not the same as fitting. A box can be perfectly centred and
            // still stick out of a circle at its corners -- which is the fault the shape-aware inset was
            // introduced for, and the one a caller reintroduces by using a constant.
            for (Shape shape : ALL) {
                for (int size : List.of(12, 20, 26, 33, 48, 64, 128)) {
                    int[] box = shape.iconBox(0, 0, size);
                    for (int row = box[1]; row < box[1] + box[2]; row++) {
                        assertTrue(covers(shape.spans(row, size), box[0], box[0] + box[2]),
                                shape + " " + size + ": row " + row + " does not cover the icon box at "
                                        + box[0] + " width " + box[2]);
                    }
                }
            }
        }

        @Test
        @DisplayName("a scaled box is centred and inside the full-size box, so it is inside the shape too")
        void aScaledBoxIsASubset() {
            // The property that makes a scaled icon safe: it is a subset of a box that already fits,
            // centred on the same point. So "inside the shape" is inherited rather than re-argued --
            // which is exactly why the scale applies to the size and then centres, rather than applying
            // to the inset and then positioning.
            for (Shape shape : ALL) {
                for (int size : List.of(26, 33, 48, 64)) {
                    int[] full = shape.iconBox(0, 0, size);
                    for (double scale : new double[] {0.25, 0.5, 0.75, 1.0}) {
                        int[] scaled = shape.iconBox(0, 0, size, scale);

                        assertTrue(scaled[2] <= full[2],
                                shape + " " + size + " at " + scale + ": scaled box " + scaled[2]
                                        + " is bigger than the full one " + full[2]);
                        assertTrue(scaled[0] >= full[0] && scaled[1] >= full[1],
                                shape + " " + size + " at " + scale + ": scaled box starts before the box"
                                        + " it should be inside");
                        assertTrue(scaled[0] + scaled[2] <= full[0] + full[2],
                                shape + " " + size + " at " + scale + ": scaled box ends past the full one");

                        int left = scaled[0];
                        int right = size - left - scaled[2];
                        assertTrue(Math.abs(left - right) <= 1,
                                shape + " " + size + " at " + scale + ": margins " + left + " and " + right);
                    }
                }
            }
        }

        @Test
        @DisplayName("the scale is clamped, so a bad number cannot draw outside the node")
        void theScaleIsClamped() {
            // Reached by a payload as well as by a file, so the clamp is not redundant with the codec:
            // the codec ran on the server, over a number from that server's version. The same reasoning
            // that makes span clamp its own output.
            for (Shape shape : ALL) {
                int[] full = shape.iconBox(0, 0, 48, 1.0);
                assertEquals(full[2], shape.iconBox(0, 0, 48, 4.0)[2], shape + ": >1 should clamp to 1");
                assertEquals(full[2], shape.iconBox(0, 0, 48, 1.0E9)[2], shape + ": absurd should clamp");

                int[] tiny = shape.iconBox(0, 0, 48, Shape.MIN_ICON_SCALE);
                assertEquals(tiny[2], shape.iconBox(0, 0, 48, 0.0)[2], shape + ": 0 should clamp up");
                assertEquals(tiny[2], shape.iconBox(0, 0, 48, -3.0)[2], shape + ": negative should clamp up");
            }
        }

        @Test
        @DisplayName("the minimum scale leaves a visible box, and full scale is bigger still")
        void theMinimumScaleIsVisible() {
            // The lower bound is a claim about perception rather than arithmetic -- below a quarter the
            // item is a smudge -- and it is asserted so that moving the constant has to move a test.
            for (Shape shape : ALL) {
                for (int size : List.of(26, 48, 64)) {
                    int[] smallest = shape.iconBox(0, 0, size, Shape.MIN_ICON_SCALE);
                    int[] full = shape.iconBox(0, 0, size, Shape.MAX_ICON_SCALE);

                    assertTrue(smallest[2] > 0, shape + " " + size + ": the minimum scale draws nothing");
                    assertTrue(smallest[2] < full[2],
                            shape + " " + size + ": the minimum scale is not smaller than full size");
                }
            }
        }

        @Test
        @DisplayName("a size too small to hold anything gives zero rather than throwing")
        void degenerateSizesDoNotThrow() {
            // A screen must not crash because a quest file said size 1. The validator bounds the real
            // range, but this is also reached by a payload from a server that might not.
            for (Shape shape : ALL) {
                assertEquals(0, shape.maxInset(0), shape + " at size 0");
                assertEquals(0, shape.maxInset(1), shape + " at size 1");
                assertEquals(0, shape.maxInset(2), shape + " at size 2");
            }
        }
    }
}
