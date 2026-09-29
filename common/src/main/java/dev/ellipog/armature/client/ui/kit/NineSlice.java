package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Nine-slice geometry: one source image drawn into a box of any size without its corners stretching.
 *
 * <h2>The problem it solves, in one sentence</h2>
 *
 * <p>A panel border drawn from a picture has to work at 120 pixels wide and at 400, and scaling the
 * whole picture makes the border thick on one and thin on the other. Cutting it into nine — four
 * corners that never scale, four edges that stretch along one axis, and a middle that fills — is the
 * standard answer and it is exact: the corner pixels are the corner pixels at every size.
 *
 * <h2>This is geometry only, and that is deliberate</h2>
 *
 * <p>It produces the nine source-and-destination rectangles and nothing else. It does not know what a
 * texture is, it does not draw, and it holds no image. That is what lets it be tested — the awkward
 * cases below are all arithmetic, and every one of them is a case where a renderer would draw
 * something wrong rather than throw.
 *
 * <p>Drawing the pieces is the caller's job, and in this project there is exactly one caller: a loop
 * over {@link #pieces} calling {@code GuiRenderer} for each. When the procedural atlas (R5's
 * remaining half) lands, the source rectangle is a region of it.
 *
 * <h2>Four insets rather than one, because a border rarely matches its art</h2>
 *
 * <p>A single border width would be simpler and is wrong for anything but a perfectly symmetric
 * picture: a panel that has a highlight along its top edge and a shadow along its bottom has a
 * different top inset from its bottom one, and collapsing them is how a nine-slice draws a seam.
 *
 * <h2>The case that matters: a destination smaller than its own corners</h2>
 *
 * <p>A nine-slice with 8-pixel corners cannot be drawn into a 10-pixel box the naive way — the left
 * and right pieces would overlap, and drawing them in sequence paints one over the other, so the
 * border comes out looking clipped or doubled depending on the order. {@link #pieces} scales the
 * corners down proportionally instead, so the result is a shrunken version of the art rather than
 * overlapping fragments. This is reachable in normal use: a scrollbar track, a collapsed sidebar, and
 * every window a player drags small.
 */
public record NineSlice(int left, int top, int right, int bottom) {

    public NineSlice {
        if (left < 0 || top < 0 || right < 0 || bottom < 0) {
            throw new IllegalArgumentException(
                    "nine-slice insets cannot be negative: " + left + ", " + top + ", " + right + ", " + bottom);
        }
    }

    /** The same inset on all four sides. The common case, for art with a symmetric border. */
    public static NineSlice uniform(int inset) {
        return new NineSlice(inset, inset, inset, inset);
    }

    /** No insets at all, which degenerates to one stretched piece. Useful as a "no border" value. */
    public static final NineSlice NONE = new NineSlice(0, 0, 0, 0);

    /** The width of the middle column in a source of this width. */
    public int middleWidth(int sourceWidth) {
        return Math.max(0, sourceWidth - left - right);
    }

    /** The height of the middle row in a source of this height. */
    public int middleHeight(int sourceHeight) {
        return Math.max(0, sourceHeight - top - bottom);
    }

    /**
     * One of the nine pieces: where to read from and where to draw.
     *
     * <p>Source and destination are separate because that is the whole point — the corner is read at
     * its own size and drawn at its own size, while the edge is read at eight pixels and drawn at
     * three hundred.
     */
    public record Piece(int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                        int destX, int destY, int destWidth, int destHeight) {

        /** Whether this piece would draw anything. Zero-sized pieces are dropped, not emitted. */
        public boolean empty() {
            return sourceWidth <= 0 || sourceHeight <= 0 || destWidth <= 0 || destHeight <= 0;
        }

        @Override
        public String toString() {
            return "src(" + sourceX + "," + sourceY + " " + sourceWidth + "x" + sourceHeight + ")"
                    + " -> dst(" + destX + "," + destY + " " + destWidth + "x" + destHeight + ")";
        }
    }

    /**
     * The pieces needed to draw this slice from one rectangle into another.
     *
     * @param sourceX source region's left edge in the texture
     * @param sourceY source region's top edge
     * @param sourceWidth the source region's full width. Must accommodate both horizontal insets, or
     *     they are clamped to share what there is.
     * @param sourceHeight as above, vertically
     * @param destX where to draw, left edge
     * @param destY where to draw, top edge
     * @param destWidth how wide to draw. Smaller than the insets is handled — see the class note.
     * @param destHeight how tall to draw
     * @return between one and nine pieces, in reading order, with every empty one omitted. Never empty
     *     for a destination with positive size; a fully degenerate call returns an empty list rather
     *     than throwing, because a caller drawing a collapsed panel should draw nothing and carry on.
     */
    public List<Piece> pieces(int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                              int destX, int destY, int destWidth, int destHeight) {
        List<Piece> pieces = new ArrayList<>(9);
        if (sourceWidth <= 0 || sourceHeight <= 0 || destWidth <= 0 || destHeight <= 0) {
            return pieces;
        }

        // The source insets, clamped so they cannot between them exceed the source. A source narrower
        // than its own border is a mis-authored texture, and the honest reading of it is "there is no
        // middle", not a negative middle width.
        int[] cols = split(left, right, sourceWidth);
        int[] rows = split(top, bottom, sourceHeight);
        int srcLeft = cols[0];
        int srcRight = cols[1];
        int srcTop = rows[0];
        int srcBottom = rows[1];

        // The destination insets, scaled down together if the box cannot hold them at full size.
        // Proportional rather than clamped-one-at-a-time, because clamping the right edge only would
        // leave the left corner at full size and shift the whole border off centre.
        int[] dstCols = fit(left, right, destWidth);
        int[] dstRows = fit(top, bottom, destHeight);
        int dstLeft = dstCols[0];
        int dstRight = dstCols[1];
        int dstTop = dstRows[0];
        int dstBottom = dstRows[1];

        // The three column boundaries and the three row boundaries, in both spaces. Three of each
        // rather than four, because the fourth is the far edge and is implied.
        int[] srcXs = {sourceX, sourceX + srcLeft, sourceX + sourceWidth - srcRight, sourceX + sourceWidth};
        int[] dstXs = {destX, destX + dstLeft, destX + destWidth - dstRight, destX + destWidth};
        int[] srcYs = {sourceY, sourceY + srcTop, sourceY + sourceHeight - srcBottom, sourceY + sourceHeight};
        int[] dstYs = {destY, destY + dstTop, destY + destHeight - dstBottom, destY + destHeight};

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                Piece piece = new Piece(
                        srcXs[col], srcYs[row],
                        srcXs[col + 1] - srcXs[col], srcYs[row + 1] - srcYs[row],
                        dstXs[col], dstYs[row],
                        dstXs[col + 1] - dstXs[col], dstYs[row + 1] - dstYs[row]);
                if (!piece.empty()) {
                    pieces.add(piece);
                }
            }
        }
        return pieces;
    }

    /**
     * How many pieces {@link #pieces} would produce, without building them. For an assertion about
     * which case a given call falls into.
     */
    public int pieceCount(int sourceWidth, int sourceHeight, int destWidth, int destHeight) {
        return pieces(0, 0, sourceWidth, sourceHeight, 0, 0, destWidth, destHeight).size();
    }

    /**
     * Two insets and a total, giving back a near and far inset that fit inside it.
     *
     * <p>Clamped equally rather than one at a time, and split with the larger half first so an odd
     * pixel goes to the near side consistently — which is what stops a one-pixel border shifting as a
     * window is resized by a pixel.
     */
    private static int[] split(int near, int far, int total) {
        if (near + far <= total) {
            return new int[] {near, far};
        }
        if (total <= 0) {
            return new int[] {0, 0};
        }
        int half = total / 2;
        return new int[] {Math.min(near, total - half), Math.min(far, half)};
    }

    /**
     * The destination insets for a box of {@code total} pixels, scaled down together if they do not
     * fit.
     *
     * <h2>The remainder goes to the far inset, and that is what makes a tiny box tile exactly</h2>
     *
     * <p>{@code near} takes the rounded share and {@code far} takes {@code total - near}, so the two
     * always sum to exactly the total — no pixel is left uncovered and none is covered twice, at any
     * size. That is not a nicety: at a box of two or three pixels the corners are fractions of a pixel,
     * and a version that rounded both would leave a one-pixel seam in the middle of a border, which is
     * the one artefact a player is guaranteed to see.
     */
    private static int[] fit(int near, int far, int total) {
        if (near + far <= total) {
            return new int[] {near, far};
        }
        if (total <= 0) {
            return new int[] {0, 0};
        }
        float scale = total / (float) (near + far);
        int scaledNear = Math.round(near * scale);
        // Clamped into the box, because rounding a fractional share can round *up* to the whole total
        // — at a 2-pixel box with equal insets, a scale of 0.5 rounds each to 1 and the near inset
        // would take both pixels, leaving the far edge with nothing and the far *piece* therefore
        // empty. Taking the remainder instead means the two add up even when the rounding does not.
        int clampedNear = Math.max(0, Math.min(scaledNear, total));
        return new int[] {clampedNear, total - clampedNear};
    }

    @Override
    public String toString() {
        return "NineSlice(" + left + ", " + top + ", " + right + ", " + bottom + ")";
    }
}
