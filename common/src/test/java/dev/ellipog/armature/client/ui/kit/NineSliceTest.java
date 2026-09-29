package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nine-slice geometry: where each of the nine pieces reads from and draws to.
 *
 * <h2>The assertion that matters most is that the pieces tile the destination</h2>
 *
 * <p>Not that a particular piece landed at a particular coordinate — that would be a test of the
 * arithmetic against itself. What a caller needs is that the nine pieces cover the destination
 * <b>exactly once</b>: no gap between them, no overlap. A gap shows as a seam of background colour
 * through a panel border; an overlap draws one piece over another, so the border comes out doubled
 * or clipped depending on the order they happen to be in. Both are visible, neither throws, and both
 * are arithmetic.
 *
 * <p>The other half is the awkward case: a destination smaller than its own corners. That is
 * reachable in normal play — a collapsed sidebar, a window dragged small — and the naive
 * implementation overlaps the left and right corners and paints one over the other.
 */
@DisplayName("Nine-slice geometry")
class NineSliceTest {

    /** A typical border: 8-pixel corners, from a 32x32 source. */
    private static final NineSlice EIGHT = NineSlice.uniform(8);

    @Test
    @DisplayName("nine pieces are produced when the destination can hold them")
    void ninePiecesForAComfortableDestination() {
        List<NineSlice.Piece> pieces = EIGHT.pieces(0, 0, 32, 32, 100, 100, 200, 120);
        assertEquals(9, pieces.size(), "a comfortable destination should produce all nine pieces");
    }

    @Test
    @DisplayName("the corners are drawn at their source size, and the edges stretch")
    void cornersDoNotStretchAndEdgesDo() {
        // The whole point of the technique. If this stopped being true the art would scale with the
        // panel, and a 400-pixel-wide panel would have a border twice as thick as a 120-pixel one.
        List<NineSlice.Piece> pieces = EIGHT.pieces(0, 0, 32, 32, 0, 0, 200, 100);

        // The first piece is the top-left corner. Read 8x8, drawn 8x8.
        NineSlice.Piece topLeft = pieces.get(0);
        assertEquals(8, topLeft.sourceWidth());
        assertEquals(8, topLeft.sourceHeight());
        assertEquals(8, topLeft.destWidth(), "the corner was scaled with the panel");
        assertEquals(8, topLeft.destHeight());
        assertEquals(0, topLeft.destX());
        assertEquals(0, topLeft.destY());

        // The top edge stretches horizontally and not vertically.
        NineSlice.Piece topEdge = pieces.get(1);
        assertEquals(16, topEdge.sourceWidth(), "the top edge should read the source's middle 16 pixels");
        assertEquals(8, topEdge.sourceHeight());
        assertEquals(200 - 16, topEdge.destWidth(), "the top edge did not stretch to fill the gap");
        assertEquals(8, topEdge.destHeight(), "the top edge stretched vertically, which it must not");

        // And the middle stretches both ways.
        NineSlice.Piece middle = pieces.get(4);
        assertEquals(16, middle.sourceWidth());
        assertEquals(16, middle.sourceHeight());
        assertEquals(200 - 16, middle.destWidth());
        assertEquals(100 - 16, middle.destHeight());
    }

    @Test
    @DisplayName("the nine pieces cover the destination exactly once, with no gap and no overlap")
    void piecesTileTheDestinationExactly() {
        // The property worth asserting, and it is asserted over a sweep rather than one case because
        // the failure is always at an edge: a destination whose middle is one pixel wide, or whose
        // corners exactly fill it, or an odd number where the split rounds. Every one of those is a
        // seam or a double-draw in one direction.
        int[][] destinations = {
                {40, 40}, {41, 40}, {40, 41}, {17, 17}, {16, 16}, {15, 15}, {3, 200},
                {200, 3}, {100, 100}, {2, 2}, {0, 40}, {40, 0}, {0, 0}, {7, 7}, {8, 8}, {9, 9},
                {1, 1}, {1, 40}, {40, 1}, {1, 2}, {2, 1}, {13, 5}, {5, 13},
        };

        for (int[] destination : destinations) {
            int width = destination[0];
            int height = destination[1];
            List<NineSlice.Piece> pieces = EIGHT.pieces(0, 0, 32, 32, 10, 20, width, height);

            if (width <= 0 || height <= 0) {
                assertTrue(pieces.isEmpty(),
                        "a collapsed destination should draw nothing, and " + width + "x" + height
                                + " produced " + pieces.size() + " piece(s)");
                continue;
            }

            // Every piece inside the destination, and the total area exactly the destination's area.
            // Area rather than a per-pixel walk: no gaps and no overlaps is the same statement as
            // "the areas sum to the whole", for pieces that are all rectangles and all aligned to the
            // same three column and row boundaries — which they are, by construction.
            int area = 0;
            for (NineSlice.Piece piece : pieces) {
                assertTrue(piece.destX() >= 10, "a piece started left of the destination");
                assertTrue(piece.destY() >= 20, "a piece started above the destination");
                assertTrue(piece.destX() + piece.destWidth() <= 10 + width,
                        "a piece ran past the destination's right edge at " + width + "x" + height);
                assertTrue(piece.destY() + piece.destHeight() <= 20 + height,
                        "a piece ran past the destination's bottom edge at " + width + "x" + height);
                area += piece.destWidth() * piece.destHeight();
            }

            assertEquals(width * height, area,
                    "the pieces do not tile " + width + "x" + height + " exactly: their areas sum to "
                            + area + ", which means a gap or an overlap");
        }
    }

    @Test
    @DisplayName("a destination smaller than its corners scales them down rather than overlapping them")
    void aTinyDestinationScalesItsCorners() {
        // The case a naive implementation gets wrong: with 8-pixel corners and a 10-pixel box, the
        // left and right pieces would each want 8 pixels, so drawing them in sequence paints one over
        // the other and the border comes out clipped on one side. Scaling both to share what there is
        // gives a shrunken version of the art instead.
        List<NineSlice.Piece> pieces = EIGHT.pieces(0, 0, 32, 32, 0, 0, 10, 10);

        assertFalse(pieces.isEmpty(), "a ten-pixel box should still draw its border");
        int area = 0;
        for (NineSlice.Piece piece : pieces) {
            assertTrue(piece.destX() + piece.destWidth() <= 10,
                    "a piece ran past the right edge of a ten-pixel box");
            assertTrue(piece.destY() + piece.destHeight() <= 10,
                    "a piece ran past the bottom edge of a ten-pixel box");
            area += piece.destWidth() * piece.destHeight();
        }
        assertEquals(100, area, "the scaled pieces do not cover the ten-pixel box exactly");
    }

    @Test
    @DisplayName("a hidden piece of the corner is drawn when the box is only a few pixels")
    void aVerySmallBoxStillTiles() {
        // The band below twice the insets, where `fit` has to divide fractional corners between two
        // pixels. Asserted for every size in the band rather than swept with the larger cases, because
        // this is where an integer assignment can leave a one-pixel seam — and a seam in a border is
        // the one artefact a player is guaranteed to notice.
        for (int size = 1; size <= 16; size++) {
            for (int[] box : new int[][] {{size, size}, {size, 40}, {40, size}, {size, size + 1}}) {
                int width = box[0];
                int height = box[1];
                List<NineSlice.Piece> pieces = EIGHT.pieces(0, 0, 32, 32, 0, 0, width, height);

                int area = 0;
                for (NineSlice.Piece piece : pieces) {
                    assertTrue(piece.destX() >= 0 && piece.destY() >= 0, "a piece left the box: " + piece);
                    assertTrue(piece.destX() + piece.destWidth() <= width,
                            "a piece ran past the right edge of " + width + "x" + height + ": " + piece);
                    assertTrue(piece.destY() + piece.destHeight() <= height,
                            "a piece ran past the bottom edge of " + width + "x" + height + ": " + piece);
                    area += piece.destWidth() * piece.destHeight();
                }

                assertEquals(width * height, area,
                        "the pieces of a " + width + "x" + height + " box do not cover it: area "
                                + area + " against " + (width * height) + ", pieces " + pieces);
            }
        }
    }

    @Test
    @DisplayName("a destination exactly the size of its corners still tiles")
    void aDestinationExactlyTheCornerSizeTiles() {
        // The boundary between "fits" and "must scale": 16x16 is exactly the two 8-pixel corners, so
        // there is no middle at all — no edge pieces and no centre. An off-by-one here either draws a
        // zero-width edge or drops a piece, and both leave a hole.
        List<NineSlice.Piece> pieces = EIGHT.pieces(0, 0, 32, 32, 0, 0, 16, 16);

        assertEquals(4, pieces.size(), "exactly the corners should draw, and no edges or centre: " + pieces);

        int area = 0;
        for (NineSlice.Piece piece : pieces) {
            assertTrue(piece.destWidth() > 0 && piece.destHeight() > 0, "an empty piece was emitted");
            area += piece.destWidth() * piece.destHeight();
        }
        assertEquals(256, area);
    }

    @Test
    @DisplayName("no piece is ever emitted empty, since one would draw nothing and mislead a count")
    void emptyPiecesAreOmitted() {
        // A caller iterating the pieces is drawing each one. An empty piece is a wasted draw call, and
        // worse, it makes `pieces.size()` a lie about how many draws are coming — which is the sort of
        // number a test elsewhere would then assert against.
        for (int size = 1; size <= 40; size++) {
            for (NineSlice.Piece piece : EIGHT.pieces(0, 0, 32, 32, 0, 0, size, size)) {
                assertFalse(piece.empty(), "an empty piece was emitted at destination size " + size);
                assertTrue(piece.sourceWidth() > 0 && piece.sourceHeight() > 0);
            }
        }
    }

    @Test
    @DisplayName("a collapsed destination draws nothing rather than throwing")
    void aCollapsedDestinationDrawsNothing() {
        // Reachable by dragging a window to nothing. A caller in the middle of drawing a panel should
        // draw no border and carry on, not crash — and an exception from inside a render pass takes
        // the whole frame with it.
        assertTrue(EIGHT.pieces(0, 0, 32, 32, 0, 0, 0, 0).isEmpty());
        assertTrue(EIGHT.pieces(0, 0, 32, 32, 0, 0, 10, 0).isEmpty());
        assertTrue(EIGHT.pieces(0, 0, 32, 32, 0, 0, 0, 10).isEmpty());
        assertTrue(EIGHT.pieces(0, 0, 0, 0, 0, 0, 10, 10).isEmpty(), "a zero-sized source draws nothing");
    }

    @Test
    @DisplayName("the source insets are clamped to the source, so an over-thick border cannot invert it")
    void sourceInsetsAreClamped() {
        // A mis-authored texture with 8-pixel corners in a 6-pixel image. The honest reading is "there
        // is no middle", not a negative middle width — which would produce source rectangles running
        // backwards, and a renderer given those reads the wrong pixels rather than nothing.
        NineSlice tooThick = NineSlice.uniform(8);

        List<NineSlice.Piece> pieces = tooThick.pieces(0, 0, 6, 6, 0, 0, 40, 40);
        for (NineSlice.Piece piece : pieces) {
            assertTrue(piece.sourceWidth() > 0 && piece.sourceHeight() > 0,
                    "a source rectangle inverted: " + piece);
            assertTrue(piece.sourceX() >= 0 && piece.sourceX() + piece.sourceWidth() <= 6,
                    "a source rectangle left the source image: " + piece);
            assertTrue(piece.sourceY() >= 0 && piece.sourceY() + piece.sourceHeight() <= 6,
                    "a source rectangle left the source image: " + piece);
        }
    }

    @Test
    @DisplayName("NONE degenerates to one stretched piece, which is a valid border of nothing")
    void noneIsOneStretchedPiece() {
        // Useful as a "no border" value in a theme: it draws the whole source into the whole
        // destination, which for a plain panel is exactly right and costs one call rather than nine.
        List<NineSlice.Piece> pieces = NineSlice.NONE.pieces(0, 0, 64, 64, 0, 0, 200, 100);

        assertEquals(1, pieces.size(), "no insets should produce one piece, not nine");
        assertEquals(0, pieces.get(0).sourceX());
        assertEquals(200, pieces.get(0).destWidth());
        assertEquals(100, pieces.get(0).destHeight());
    }

    @Test
    @DisplayName("the middle dimensions are reported for a caller laying out art")
    void middleDimensions() {
        assertEquals(16, EIGHT.middleWidth(32));
        assertEquals(16, EIGHT.middleHeight(32));
        // Never negative, even for a source narrower than its own border.
        assertEquals(0, EIGHT.middleWidth(6));
        assertEquals(0, EIGHT.middleWidth(8));
    }

    @Test
    @DisplayName("a negative inset is refused, since it is a marshalling error rather than a shape")
    void negativeInsetsAreRefused() {
        // Not clamped: a negative inset has no sensible reading — it would mean a piece reading
        // outside the source on one side and inside on the other — and it can only arrive from a
        // mistyped constant or a theme that loaded wrong. Better to fail where the value was written.
        assertThrows(IllegalArgumentException.class, () -> new NineSlice(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> NineSlice.uniform(-4));
    }

    @Test
    @DisplayName("pieceCount agrees with pieces, since a caller may ask before building them")
    void pieceCountAgrees() {
        // A convenience for an assertion, so it must not be a second implementation of the count.
        for (int size : new int[] {4, 8, 16, 17, 32, 100}) {
            assertEquals(EIGHT.pieces(0, 0, 32, 32, 0, 0, size, size).size(),
                    EIGHT.pieceCount(32, 32, size, size),
                    "pieceCount disagreed with pieces at " + size);
        }
    }
}
