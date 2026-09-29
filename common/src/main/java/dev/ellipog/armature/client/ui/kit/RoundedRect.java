package dev.ellipog.armature.client.ui.kit;

/**
 * The geometry of a rectangle with rounded corners: the horizontal extent of each row.
 *
 * <h2>Why a span table and not four arcs</h2>
 *
 * <p>Because a renderer draws horizontal runs of pixels, and every question about a shape can be
 * answered from "how wide is it on this row". {@link #span} is that answer, and the three things that
 * need it — filling the shape, drawing its one-pixel border, and telling whether the mouse is inside
 * it — all read the same number. So a click lands on exactly the pixels that were drawn, which is the
 * property that a separately-written hit test always eventually loses.
 *
 * <p>The alternative is a precomputed pixel mask, which is what FTB Quests does for node shapes. A
 * mask costs a buffer per shape, ties the shape to a pixel grid, and cannot be scaled or re-radiused
 * without rebuilding it. This is a handful of multiplications per row and a radius parameter.
 *
 * <h2>Where this came from, and why it moved</h2>
 *
 * <p>This is rounding that lived as a private helper inside a consumer's quest-shape enum,
 * generalised in two ways: the radius is a <b>parameter</b> rather than a quarter of the size, and the
 * shape is not an enum member, so anything can use it.
 *
 * <p>Deliberately not naming that consumer, because Armature does not know its consumers and must not
 * be able to: the dependency arrow points one way, and a library that names a mod is one that cannot
 * be built or released without it. The shape of the thing is what matters here — a screen needed
 * rounded corners, so the rounded corners were written inside the screen's own shape enum, where
 * nothing but that enum could reach them.
 *
 * <p>What that cost, concretely: the circle equation was sitting in a closed enum in a UI, so a
 * <b>theme</b> could not put a radius in a data file, a <b>button</b> could not round its corners, and
 * a reskin could not change the corner without a code change. Those are one function with three
 * arguments rather than three copies of the arithmetic — and the copies are where a transposed term
 * lives undiscovered.
 *
 * <h2>The small-size clamp is not defensive, it is necessary</h2>
 *
 * <p>A node can be drawn as small as twelve pixels, and a three-pixel radius on a two-pixel rectangle
 * computes a span that runs backwards. A renderer given {@code from > to} draws a rectangle the other
 * way round rather than drawing nothing, so the clamp to at least one pixel wide is what stops a
 * small shape inverting. Reachable by resizing a window, not by a malformed file.
 */
public final class RoundedRect {

    private RoundedRect() {
    }

    /**
     * The horizontal extent of the shape on one row.
     *
     * @param row 0 to {@code size - 1}, measured from the <b>top</b>
     * @param size the square's width and height
     * @param radius the corner radius. Clamped to {@code size / 2}; a larger value is a circle rather
     *     than an error, which is what a caller wants when a radius is derived from a size that shrank.
     * @return {@code {from, to}}, {@code from} inclusive and {@code to} exclusive, or {@code null} when
     *     the row is outside the shape. Never returns an empty span — a zero-width row is a hole in a
     *     drawn outline, which reads as a rendering fault rather than as a small shape.
     */
    public static int[] span(int row, int size, int radius) {
        if (size <= 0 || row < 0 || row >= size) {
            return null;
        }

        int r = Math.min(Math.max(radius, 0), size / 2);
        int inset = r == 0 ? 0 : cornerCut(row, size, r);

        int from = Math.max(0, Math.min(inset, size - 1));
        int to = Math.max(from + 1, size - inset);
        return new int[] {from, to};
    }

    /** The width of the shape on one row, or zero if the row is outside it. */
    public static int width(int row, int size, int radius) {
        int[] span = span(row, size, radius);
        return span == null ? 0 : span[1] - span[0];
    }

    /**
     * How far a corner of this radius cuts in on this row, measuring from the nearer edge.
     *
     * <p>From the circle equation: at a depth {@code d} from the top or bottom edge, the corner cuts
     * in by {@code r - sqrt(r² - (r - d)²)}. The {@code depth} line is what makes the rounding
     * symmetric top and bottom — measuring from the top only rounds the top two corners and leaves the
     * bottom two square, which is a mistake that has been made in this codebase once already and was
     * only visible in a screenshot.
     *
     * <p>Public because an asymmetric shape needs it for its fore-edge: a book drawn from the front is
     * a square left edge and a rounded right one, which is the difference between a book and a pill.
     * That shape lives in a consumer; the arithmetic for its corner lives here.
     */
    public static int cornerCut(int row, int size, int radius) {
        if (radius <= 0 || size <= 0) {
            return 0;
        }
        int depth = Math.min(row, size - 1 - row);
        if (depth >= radius) {
            return 0;
        }
        double dy = radius - depth;
        return radius - (int) Math.round(Math.sqrt(Math.max(0.0, (double) radius * radius - dy * dy)));
    }

    // `maxInset(size, radius)` was written here and then deleted, and the reason is worth a line
    // because it is this project's own rule pointed at itself.
    //
    // The largest square that fits inside a shape is a **shape-generic** algorithm: it walks the rows
    // a candidate square would cover and asks whether each spans it, which works for any shape that
    // can say how wide it is on a row. Written against a radius it would only work for rounded
    // rectangles — and a consumer's shape enum already has the generic version, because it needs it
    // for circles and hexagons too.
    //
    // So a copy here would be a second implementation of one algorithm, which is the exact debt the
    // extraction parts of this project exist to remove. It moves to `ui.shape` in R7, where a `Shape`
    // can hand over its own span table and the algorithm can be written once for all of them.

    @Override
    public String toString() {
        return "RoundedRect";
    }
}
