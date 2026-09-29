package dev.ellipog.armature.client.ui.shape;

/**
 * A closed shape a UI element can be drawn in, hit-tested against, and have an icon fitted inside.
 *
 * <h2>One span table answers all three questions</h2>
 *
 * <p>Everything here derives from the horizontal extent of the shape on one row of its bounding
 * square. The renderer walks the rows and fills each span; the hit test asks whether the pointer is
 * inside the span for its row; the icon fit asks which square fits inside every row it covers. So
 * <b>a click lands on exactly the pixels that were drawn, and the icon sits inside them</b>, by
 * construction rather than by three pieces of arithmetic agreeing.
 *
 * <p>A hit test written separately from the drawing is one that disagrees with what a player can see.
 * The disagreement shows up as a node that cannot be clicked near its edges, which reads as an input
 * bug rather than as a geometry bug — and it is the reason this is a type rather than three helper
 * methods on an enum.
 *
 * <h2>Two abstract methods, not one, and that is on purpose</h2>
 *
 * <p>An implementation says where the span <b>starts and ends</b> on a row; {@link #span} does the
 * bounds check and the clamping. That split exists because the clamping is a contract every shape has
 * to honour and no shape should have to remember:
 *
 * <ul>
 *   <li>A row outside the square has <b>no</b> span rather than an empty one, because a caller walking
 *       rows past the bottom needs to know it has finished. A clamped span would draw the last row
 *       twice.</li>
 *   <li>A span is <b>never empty</b>, because a zero-width row is a visible hole in an outline. At 12
 *       pixels a circle's top row computes to nothing, and the clamp to one pixel is what makes a
 *       small circle a small circle rather than a circle with a notch.</li>
 *   <li>A span never leaves the square, because a renderer given {@code from > to} draws a rectangle
 *       the other way round rather than drawing nothing.</li>
 * </ul>
 *
 * <p>So an implementation cannot get those three wrong, and a caller can rely on them. That is worth
 * two method names.
 *
 * <h2>Why the ends and not a whole span</h2>
 *
 * <p>Because the shape's own arithmetic is about its two edges — a corner cuts in from the left and
 * from the right by the same amount, a taper narrows symmetrically — and asking an implementation to
 * return an array would be asking it to do the clamping itself, or to be trusted not to.
 *
 * <h2>What is deliberately not on this interface</h2>
 *
 * <p>Nothing about textures, colours, borders or drawing. A shape answers geometry and nothing else,
 * which is what makes the whole family testable without a client — the invariant sweeps in
 * {@code ShapeTest} run every shape at every size from 1 to 80, and that is only possible because a
 * shape is a pair of integers per row.
 */
public interface Shape {

    /**
     * The left edge of this shape's span on {@code row}, in local coordinates.
     *
     * <p>May be out of range: {@link #span} clamps it. Implementations should return their honest
     * arithmetic rather than pre-clamping, so the clamp happens once.
     */
    int startOf(int row, int size);

    /** The right edge of this shape's span on {@code row}, <b>exclusive</b>. Clamped by {@link #span}. */
    int endOf(int row, int size);

    /**
     * The horizontal extent of this shape on one row of a {@code size}-pixel square.
     *
     * @param row 0 to {@code size - 1}, measured from the <b>top</b>
     * @return {@code {from, to}} in local coordinates — {@code from} inclusive, {@code to} exclusive —
     *     or {@code null} when the row is outside the shape. Never empty, and never outside the square:
     *     see the class note for why both of those are the interface's job rather than the
     *     implementation's.
     */
    default int[] span(int row, int size) {
        if (size <= 0 || row < 0 || row >= size) {
            return null;
        }
        int from = Math.max(0, Math.min(startOf(row, size), size - 1));
        int to = Math.max(from + 1, Math.min(endOf(row, size), size));
        return new int[] {from, to};
    }

    /**
     * How much of an icon fits, as a fraction of the largest square that does.
     *
     * <p>The lower bound is not zero, and the reasoning is about perception rather than arithmetic: a
     * scale of 0 draws nothing, and a node whose icon is invisible is indistinguishable from one with
     * no icon at all — which a screen already draws a state-coloured block for. Below a quarter the
     * item is a smudge, so a quarter is where "small" stops being "broken".
     */
    double MIN_ICON_SCALE = 0.25;

    /** Full size: the icon fills the largest square that fits inside the shape. */
    double MAX_ICON_SCALE = 1.0;

    /**
     * Whether a point is inside this shape, given in the shape's own local coordinates.
     *
     * <p>{@code floor} rather than a cast, and the difference is a real bug rather than pedantry: a cast
     * truncates towards zero, so a local {@code y} of {@code -0.4} lands on row 0 and reads as inside —
     * which is how a click a fraction of a pixel above a node selects it.
     */
    default boolean containsLocal(double localX, double localY, int size) {
        if (localX < 0 || localY < 0) {
            return false;
        }
        int[] span = span((int) Math.floor(localY), size);
        return span != null && localX >= span[0] && localX < span[1];
    }

    /** The same test, given a screen point and the shape's corner. */
    default boolean contains(double px, double py, int x, int y, int size) {
        return containsLocal(px - x, py - y, size);
    }

    /**
     * The largest inset whose square lies wholly inside this shape — the biggest icon that fits.
     *
     * <h2>Why this cannot be a constant</h2>
     *
     * <p>The corner of a square is outside a circle of the same size, so one fixed inset either
     * overflows the outline on a circle or wastes a fifth of the area on a rounded rectangle. At 48
     * pixels a circle wants 7 and a rounded rectangle wants 4 — and 7 for a circle is exactly the
     * inscribed square, {@code size/√2}, which is the arithmetic arriving at the answer a pencil would.
     *
     * <h2>Which rows have to be checked, and which way the search runs</h2>
     *
     * <p>Only the top and bottom rows of the candidate square: every shape here is widest at its
     * vertical middle and narrows monotonically towards either end, so the narrowest row a square
     * covers is always one of its two edges. {@code ShapeTest} asserts that monotonicity rather than
     * assuming it, because this method depends on it.
     *
     * <p>The search runs <b>upwards from zero and returns the first inset that fits</b>, because a
     * smaller required span is easier to satisfy — so the predicate turns true once and stays true, and
     * the first success is the largest square. Returning on the first <i>failure</i> instead gives zero
     * for a circle, which draws a full-size icon hanging well outside the outline. Getting the direction
     * of a monotone search backwards is the whole bug, and it is why {@code ShapeTest} asserts both that
     * the answer fits <i>and</i> that one pixel less does not.
     */
    default int maxInset(int size) {
        if (size <= 2) {
            return 0;
        }
        for (int inset = 0; inset < size / 2; inset++) {
            if (rowCovers(inset, size, inset) && rowCovers(size - 1 - inset, size, inset)) {
                return inset;
            }
        }
        // Unreachable for every shape in Shapes: a single centre pixel always fits. Returned rather than
        // thrown so a malformed size cannot crash a screen mid-frame.
        return Math.max(0, size / 2 - 1);
    }

    /** Whether {@code row}'s span contains every column from {@code inset} to {@code size - inset - 1}. */
    default boolean rowCovers(int row, int size, int inset) {
        int[] span = span(row, size);
        return span != null && span[0] <= inset && span[1] >= size - inset;
    }

    /**
     * The box an icon fills on a node — the inset applied to <b>both</b> the position and the size.
     *
     * <h2>Why this is one method and not two numbers at the call site</h2>
     *
     * <p>Because the two numbers apart is a bug that shipped. A screen computed the box's size from
     * {@link #maxInset} — 6 for a 48-pixel circle, giving a 36-pixel box — and took its position from a
     * hardcoded constant of 3. So the item was drawn 3 pixels in from the corner at a size that wanted
     * 6: it sat 3 pixels up and left of centre, with its corner through the rounded outline it was
     * supposed to be inside.
     *
     * <p>That is the same mistake as two controls colliding, and a label and its room: <b>a value that
     * must be one thing, computed in two places.</b> The durable fix is to compute the pair together,
     * here. The screen only ever decides whether the box is big enough to be worth drawing an item in.
     *
     * <h2>Centred, and that is not the same as "inset"</h2>
     *
     * <p>At an odd size the subtraction cannot come out even whichever way it rounds, so the leftover
     * pixel is split by centring explicitly. {@code ShapeTest} asserts the equal margins for every size
     * rather than for the even ones.
     *
     * @param scale {@link #MIN_ICON_SCALE} to {@link #MAX_ICON_SCALE}; anything outside is clamped
     *     rather than refused, because this is also reached by a number off the wire
     * @return {@code {x, y, box}} — the icon's corner and its width, all three from one inset
     */
    default int[] iconBox(int nodeX, int nodeY, int size, double scale) {
        double clamped = Math.min(Math.max(scale, MIN_ICON_SCALE), MAX_ICON_SCALE);

        int inset = maxInset(size);
        int largest = Math.max(0, size - inset * 2);
        // Round rather than truncate: at 48 pixels and three-quarters the answer is 27 rather than 26,
        // which is the difference between "three-quarters" and "a bit under it".
        int box = Math.max(0, (int) Math.round(largest * clamped));
        int left = (size - box) / 2;
        return new int[] {nodeX + left, nodeY + left, box};
    }

    /** The same, at full size. */
    default int[] iconBox(int nodeX, int nodeY, int size) {
        return iconBox(nodeX, nodeY, size, MAX_ICON_SCALE);
    }
}
