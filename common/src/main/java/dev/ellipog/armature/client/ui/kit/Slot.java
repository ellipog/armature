package dev.ellipog.armature.client.ui.kit;

/**
 * One element's place in a layout: what it is, and where it ended up.
 *
 * <h2>Why a key rather than just a rectangle</h2>
 *
 * <p>Because the caller has to be able to act on what it finds without knowing where it was put. A
 * hit test that returns coordinates means the screen matches on those coordinates, so moving a row by
 * changing a metric somewhere else leaves a test that still compiles, still runs, and now names the
 * wrong row. Returning the key the caller used when it described the element makes that class of
 * mistake unrepresentable: the row moves and the dispatch moves with it.
 *
 * <p>The key is an {@code Object} rather than a {@code String} or an enum because the kit has no
 * business deciding how a caller names its own elements. A row keyed by an index, a record, or an
 * identifier all work, and the comparison is identity-or-equals at the call site's discretion.
 *
 * <h2>A null key is meaningful: drawn, but not interactive</h2>
 *
 * <p>A divider, a gap, a heading and a block of prose are things a layout places that nothing can
 * click. They are still slots -- they occupy height, they are what the culling pass skips over, and a
 * layout that could not describe them would have to fold their heights into their neighbours.
 * {@link Layout#at(int, int)} skips a null key rather than returning it, so a caller that asks "what
 * is under this point" never receives something it cannot act on.
 *
 * <h2>The rectangle is half-open</h2>
 *
 * <p>A point exactly on the right or bottom edge is <b>not</b> inside. Adjacent rows share an edge,
 * and a closed interval would put that shared pixel inside both of them -- so a click on the boundary
 * between two rows would be answered by whichever the caller happened to test first. That is stable
 * and wrong, which is the worst combination: it reproduces, so it looks intentional.
 *
 * <p><b>Game-free by design.</b>
 *
 * @param key    what this is, or null for something that is drawn but not interactive
 * @param x      left edge
 * @param y      top edge
 * @param width  width
 * @param height height
 */
public record Slot(Object key, int x, int y, int width, int height) {

    /** The x coordinate just past the right edge. */
    public int right() {
        return x + width;
    }

    /** The y coordinate just past the bottom edge. */
    public int bottom() {
        return y + height;
    }

    /** Whether a point is inside. Double, because mouse coordinates are. */
    public boolean contains(double px, double py) {
        return px >= x && px < right() && py >= y && py < bottom();
    }

    /** Whether this slot is one a caller can act on. See the class note on the null key. */
    public boolean interactive() {
        return key != null;
    }

    /** The same slot moved by an offset. Used to place a laid-out element into a scrolled viewport. */
    public Slot moved(int dx, int dy) {
        return new Slot(key, x + dx, y + dy, width, height);
    }
}
