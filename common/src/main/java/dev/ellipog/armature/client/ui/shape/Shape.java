package dev.ellipog.armature.client.ui.shape;

/**
 * A closed shape a UI element can be drawn in, hit-tested against, and have an icon fitted inside.
 *
 * <h2>One span table answers all three questions</h2>
 *
 * <p>Everything here derives from the horizontal extent of the shape on one row of its bounding
 * square. The renderer walks the rows and fills each span; the hit test asks whether the pointer is
 * inside a span for its row; the icon fit asks which square fits inside every row it covers. So
 * <b>a click lands on exactly the pixels that were drawn, and the icon sits inside them</b>, by
 * construction rather than by three pieces of arithmetic agreeing.
 *
 * <p>A hit test written separately from the drawing is one that disagrees with what a player can see.
 * The disagreement shows up as a node that cannot be clicked near its edges, which reads as an input
 * bug rather than as a geometry bug — and it is the reason this is a type rather than three helper
 * methods on an enum.
 *
 * <h2>A row can have more than one span, and the default method is why that is safe</h2>
 *
 * <p>An implementation says what a row covers — {@link #spansOf}, flattened {@code {from, to, …}}
 * pairs, its honest arithmetic — and {@link #spans} does the clamping, the dropping, the ordering and
 * the merging. That split exists because those four are a contract every shape has to honour and no
 * shape should have to remember:
 *
 * <ul>
 *   <li>A row outside the square has <b>no</b> spans rather than an empty list, because a caller
 *       walking rows past the bottom needs to know it has finished. A clamped row would draw the
 *       last one twice.</li>
 *   <li>Every span is <b>non-empty</b>, because a zero-width row is a visible hole in an outline. At
 *       12 pixels a circle's top row computes to nothing, and the clamp to one pixel is what makes a
 *       small circle a small circle rather than a circle with a notch.</li>
 *   <li>No span leaves the square, because a renderer given {@code from > to} draws a rectangle the
 *       other way round rather than drawing nothing.</li>
 *   <li>Spans come out <b>sorted and disjoint</b>. A row with two overlapping intervals would draw
 *       the overlap twice — invisible for an opaque fill, a wrong alpha for a wash — and a hit test
 *       that walked them would be answering a question about pixels that are not there. Merging them
 *       here is what lets every caller assume one interval per piece of material.</li>
 * </ul>
 *
 * <p>More than one span per row is not a curiosity: a heart's top is two lobes with a notch between
 * them, and a gear's teeth stand off its hub. Those shapes are why this is a list rather than a pair.
 *
 * <h2>What is deliberately not on this interface</h2>
 *
 * <p>Nothing about textures, colours, borders or drawing. A shape answers geometry and nothing else,
 * which is what makes the whole family testable without a client — the invariant sweeps in
 * {@code ShapeTest} run every shape at every size from 1 to 80, and that is only possible because a
 * shape is a list of integers per row.
 */
@FunctionalInterface
public interface Shape {

    /**
     * What this shape covers on {@code row}, as flattened {@code {from, to, …}} pairs, or {@code null}.
     *
     * <p>May be out of range or empty: {@link #spans} clamps, drops what is outside the square, and
     * widens what is inside it to a pixel. Implementations should return their honest arithmetic rather
     * than pre-clamping, so the clamp happens once.
     */
    int[] spansOf(int row, int size);

    /**
     * The horizontal extents of this shape on one row of a {@code size}-pixel square.
     *
     * @param row 0 to {@code size - 1}, measured from the <b>top</b>
     * @return {@code {from, to, from, to, …}} in local coordinates — each {@code from} inclusive and
     *     {@code to} exclusive — or {@code null} when the row is outside the shape or covers nothing.
     *     Never empty, never out of the square, always sorted and never overlapping: see the class
     *     note for why those are the interface's job rather than the implementation's.
     */
    default int[] spans(int row, int size) {
        if (size <= 0 || row < 0 || row >= size) {
            return null;
        }
        int[] raw = spansOf(row, size);
        if (raw == null || raw.length < 2) {
            return null;
        }
        int count = raw.length / 2;
        // Clamp, drop what is outside, and sort by `from` as it goes in. Insertion sort because a row
        // has a handful of spans at most and the arrays are small enough that anything cleverer would
        // be more code than arithmetic.
        int[] from = new int[count];
        int[] to = new int[count];
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int f = raw[i * 2];
            int t = raw[i * 2 + 1];
            if (t < f) {
                // An inverted pair is an empty interval where `from` says, not material running
                // backwards: a shape whose radius rounds its two edges past each other at one pixel
                // says "nothing here", not "everything here".
                t = f;
            }
            if (t < 0 || f > size) {
                // Strictly beyond the square. Dropped rather than clamped to a pixel at the edge: a
                // stray pixel is material the shape never claimed, and inventing it is how a shape
                // grows a speck the hit test then agrees with. Material that merely <i>touches</i> the
                // edge is not beyond it, and is kept below.
                continue;
            }
            f = Math.max(0, Math.min(f, size - 1));
            t = Math.max(f, Math.min(t, size));
            if (t <= f) {
                // Inside the square but narrower than a pixel: a circle's top row at twelve pixels, a
                // gear tooth's first row. Widened rather than dropped, because the row does cut the
                // shape -- and a shape that loses its own cap is a shape with a notch in it.
                t = Math.min(size, f + 1);
            }
            int at = kept;
            while (at > 0 && from[at - 1] > f) {
                from[at] = from[at - 1];
                to[at] = to[at - 1];
                at--;
            }
            from[at] = f;
            to[at] = t;
            kept++;
        }
        if (kept == 0) {
            return null;
        }
        // Merge what overlaps or touches. Touching intervals merge too, because [0,5) and [5,10) are
        // one piece of material with an invisible seam -- and a seam is a second fill call, and a
        // second place for a border to be drawn twice.
        int out = 0;
        for (int i = 1; i < kept; i++) {
            if (from[i] <= to[out]) {
                to[out] = Math.max(to[out], to[i]);
            }
            else {
                out++;
                from[out] = from[i];
                to[out] = to[i];
            }
        }
        int[] result = new int[(out + 1) * 2];
        for (int i = 0; i <= out; i++) {
            result[i * 2] = from[i];
            result[i * 2 + 1] = to[i];
        }
        return result;
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
        int[] spans = spans((int) Math.floor(localY), size);
        if (spans == null) {
            return false;
        }
        for (int i = 0; i < spans.length; i += 2) {
            if (localX >= spans[i] && localX < spans[i + 1]) {
                return true;
            }
        }
        return false;
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
     * <h2>What the search may and may not assume</h2>
     *
     * <p>Nothing about the shape's outline. The search this replaced checked only the top and bottom
     * rows of a candidate square, which is correct exactly when every row between them is at least as
     * wide — a property the old shapes had and the new ones do not. A gear is wider at a tooth than at
     * the gap nearer its middle, and a heart is narrow at the notch above its widest row; on either,
     * the two-row check would return an inset whose square pokes out of the outline.
     *
     * <p>So the answer is derived instead of guessed. A square of inset {@code i} covers rows
     * {@code [i, size - 1 - i]}, and each of those rows must cover the whole interval
     * {@code [i, size - 1 - i]} in at least one span — which one span can do when
     * {@code from <= i} and {@code to >= size - i}, i.e. when {@code i >= max(from, size - to)}. So a
     * row's <b>need</b> is the smallest inset it can host, {@code min over spans of max(from, size-to)},
     * and the answer is the smallest {@code i} whose whole window needs no more than {@code i}. One
     * pass outward from the middle computes the running maximum of every deeper window, so the whole
     * search is a single walk of the rows.
     *
     * <p>The answer is asserted in both directions rather than one: {@code ShapeTest} checks that the
     * square at that inset fits <i>and</i> that one pixel less does not. "It fits" alone would pass for
     * a search that returned zero — a full-size icon hanging well outside the outline.
     */
    default int maxInset(int size) {
        if (size <= 2) {
            return 0;
        }
        int deepest = (size - 1) / 2;
        int needed = 0;
        int answer = Math.max(0, size / 2 - 1);
        for (int inset = deepest; inset >= 0; inset--) {
            needed = Math.max(needed, need(inset, size));
            needed = Math.max(needed, need(size - 1 - inset, size));
            if (needed <= inset) {
                answer = inset;
            }
        }
        return answer;
    }

    /** The smallest inset whose centred square this row can host; see {@link #maxInset}. */
    default int need(int row, int size) {
        int[] spans = spans(row, size);
        if (spans == null) {
            // A row with no material cannot host a square at all. Only reachable through an inset that
            // the loop has already rejected, so a value no inset can satisfy is the honest answer.
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < spans.length; i += 2) {
            best = Math.min(best, Math.max(spans[i], size - spans[i + 1]));
        }
        return best;
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
