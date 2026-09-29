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
 *   <li>A span is never empty and never leaves the square, at <b>every</b> size. A zero-width row is a
 *       visible gap in an outline; a span past the edge is a node drawn over its neighbour.</li>
 *   <li>Every shape is symmetric top to bottom. The first rounded rectangle here measured its corner
 *       depth from the top edge only, which rounded the top two corners and left the bottom two
 *       square — obvious in a picture, invisible in a test that does not ask.</li>
 *   <li>No row is wider than the one nearer the middle, because {@code maxInset} checks only the two
 *       edge rows of a candidate square and would be wrong on a shape that bulged.</li>
 *   <li>A point is inside exactly when it is in the span for its row, asserted by walking every pixel —
 *       which is the whole reason the hit test and the drawing cannot disagree.</li>
 * </ul>
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
            Shapes.ROUNDED, Shapes.CIRCLE, Shapes.HEXAGON, Shapes.TOME, Shapes.RECT);

    private static List<Integer> sizes() {
        List<Integer> out = new ArrayList<>();
        for (int size = 1; size <= 80; size++) {
            out.add(size);
        }
        out.addAll(List.of(97, 128, 199, 512));
        return out;
    }

    // ------------------------------------------------------------------
    // Invariants
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("invariants, over every shape and every size")
    class Invariants {

        @Test
        @DisplayName("no span is ever empty or outside the square")
        void spansAreNeverEmptyOrOutOfBounds() {
            for (Shape shape : ALL) {
                for (int size : sizes()) {
                    for (int row = 0; row < size; row++) {
                        int[] span = shape.span(row, size);
                        assertNotNull(span, shape + " returned null for row " + row + " of " + size);
                        assertEquals(2, span.length);
                        assertTrue(span[0] >= 0, shape + " " + size + " row " + row + ": from < 0");
                        assertTrue(span[0] < span[1],
                                shape + " " + size + " row " + row + ": empty span " + span[0] + ".." + span[1]);
                        assertTrue(span[1] <= size,
                                shape + " " + size + " row " + row + ": to " + span[1] + " past the edge");
                    }
                }
            }
        }

        @Test
        @DisplayName("a row outside the shape has no span")
        void rowsOutsideHaveNoSpan() {
            // Different from an empty span, and the difference is load-bearing: a caller walking rows
            // past the bottom needs to know it has finished. A clamped span would draw the last row
            // twice.
            for (Shape shape : ALL) {
                assertNull(shape.span(-1, 20), shape + " had a span above itself");
                assertNull(shape.span(20, 20), shape + " had a span at its bottom edge");
                assertNull(shape.span(0, 0), shape + " had a span in a zero-size square");
                assertNull(shape.span(0, -5), shape + " had a span in a negative square");
            }
        }

        @Test
        @DisplayName("every shape is symmetric top to bottom")
        void shapesAreVerticallySymmetric() {
            for (Shape shape : ALL) {
                for (int size : sizes()) {
                    for (int row = 0; row < size; row++) {
                        int[] top = shape.span(row, size);
                        int[] bottom = shape.span(size - 1 - row, size);
                        assertEquals(top[0], bottom[0],
                                shape + " " + size + ": row " + row + " and its mirror disagree on from");
                        assertEquals(top[1], bottom[1],
                                shape + " " + size + ": row " + row + " and its mirror disagree on to");
                    }
                }
            }
        }

        @Test
        @DisplayName("no row is wider than the one nearer the middle")
        void widthNarrowsTowardTheEdges() {
            // Monotonicity, which maxInset's search depends on: it checks only the two edge rows of a
            // candidate square because every row between them is at least as wide. If that stopped
            // being true the icon would overflow on a bulge -- and the code would look correct, because
            // the assumption is documented rather than asserted.
            for (Shape shape : ALL) {
                for (int size : sizes()) {
                    for (int row = 1; row <= size / 2; row++) {
                        int[] outer = shape.span(row - 1, size);
                        int[] inner = shape.span(row, size);
                        assertTrue(inner[0] <= outer[0] && inner[1] >= outer[1],
                                shape + " " + size + ": row " + row + " is narrower than row " + (row - 1));
                    }
                }
            }
        }

        @Test
        @DisplayName("a point is inside exactly when it is in the span for its row, over every pixel")
        void containsAgreesWithSpan() {
            // The property that makes the hit test trustworthy, asserted directly rather than assumed:
            // whatever span says to fill, contains says is clickable. A separate inequality would agree
            // almost everywhere and differ by a pixel at the edges, which shows up as a node that
            // refuses a click on its own border.
            for (Shape shape : ALL) {
                for (int size : List.of(12, 26, 33, 48, 64)) {
                    for (int row = 0; row < size; row++) {
                        int[] span = shape.span(row, size);
                        for (int col = 0; col < size; col++) {
                            boolean inSpan = col >= span[0] && col < span[1];
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
                    int[] span = Shapes.RECT.span(row, size);
                    assertEquals(0, span[0]);
                    assertEquals(size, span[1]);
                }
            }
        }

        @Test
        @DisplayName("ROUNDED is full width through its middle and cut at both corners")
        void roundedHasCutCorners() {
            int size = 48;
            int[] middle = Shapes.ROUNDED.span(size / 2, size);
            assertEquals(0, middle[0], "the middle row should reach the left edge");
            assertEquals(size, middle[1], "the middle row should reach the right edge");

            int[] top = Shapes.ROUNDED.span(0, size);
            assertEquals(12, top[0], "a quarter-radius corner at 48 pixels should cut in by 12");
            assertEquals(top[0], size - top[1], "the two corners should match");
            assertEquals(0, Shapes.ROUNDED.span(12, size)[0], "the cut should be gone by the radius");
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
            assertEquals(4, Shapes.roundedProportional(4).span(0, 18)[0],
                    "the proportional radius used the float form, changing every existing node");
        }

        @Test
        @DisplayName("a fixed radius is the same at every size, which is what a panel wants")
        void aFixedRadiusDoesNotScale() {
            // The reason both forms exist. A panel 400 pixels wide should have the same corner as one
            // 120 wide; a node should not. Neither is the default for the other.
            for (int size : List.of(40, 80, 200)) {
                assertEquals(6, Shapes.rounded(6).span(0, size)[0],
                        "a fixed 6-pixel radius cut by " + Shapes.rounded(6).span(0, size)[0]
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
            int[] middle = Shapes.CIRCLE.span(size / 2, size);
            int[] top = Shapes.CIRCLE.span(0, size);

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
            assertEquals(0, Shapes.HEXAGON.span(size / 2, size)[0], "the middle should reach the edge");

            int a = Shapes.HEXAGON.span(0, size)[0];
            int b = Shapes.HEXAGON.span(1, size)[0];
            int c = Shapes.HEXAGON.span(2, size)[0];
            assertEquals(b - c, a - b, "the taper should be linear");
            assertTrue(a > c, "the taper should be narrowing towards the edge");
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
                assertEquals(0, Shapes.TOME.span(row, size)[0],
                        "row " + row + ": the spine should be a straight edge at x=0");
            }
            assertEquals(size, Shapes.TOME.span(size / 2, size)[1], "the middle should reach the right");
            assertTrue(Shapes.TOME.span(0, size)[1] < size, "the top should be cut on the right");
        }

        @Test
        @DisplayName("TOME is visibly narrower at its corners than ROUNDED is")
        void tomeIsDistinctFromRounded() {
            // Two shapes that differ by a pixel are two shapes an author cannot tell apart, and one is
            // then pointless. A third-of-the-size fore-edge against a quarter means the corners differ
            // by a clear margin.
            int size = 48;
            int tomeCut = size - Shapes.TOME.span(0, size)[1];
            int roundedCut = Shapes.ROUNDED.span(0, size)[0];
            assertTrue(tomeCut > roundedCut + 3,
                    "TOME's corner cut " + tomeCut + " is too close to ROUNDED's " + roundedCut);
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
            assertSame(Shapes.RECT, Shapes.byName("rectangle", Shapes.CIRCLE));
        }

        @Test
        @DisplayName("a name is matched whatever its capitalisation")
        void namesAreCaseInsensitive() {
            // A hand-written file's capitalisation is not a thing to be strict about, and the file format
            // spells these lowercase.
            assertSame(Shapes.CIRCLE, Shapes.byName("Circle", Shapes.RECT));
            assertSame(Shapes.HEXAGON, Shapes.byName("HEXAGON", Shapes.RECT));
            assertSame(Shapes.TOME, Shapes.byName("ToMe", Shapes.RECT));
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
                        int[] span = shape.span(row, size);
                        assertTrue(span[0] <= inset && span[1] >= size - inset,
                                shape + " " + size + " inset " + inset + ": row " + row
                                        + " span " + span[0] + ".." + span[1] + " does not cover it");
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
                        int[] span = shape.span(row, size);
                        if (span[0] > smaller || span[1] < size - smaller) {
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
                        int[] span = shape.span(row, size);
                        assertTrue(span[0] <= box[0] && span[1] >= box[0] + box[2],
                                shape + " " + size + ": row " + row + " span " + span[0] + ".." + span[1]
                                        + " does not cover the icon box at " + box[0] + " width " + box[2]);
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
