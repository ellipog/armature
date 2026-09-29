package dev.ellipog.armature.client.ui.kit;

/**
 * A content space mapped onto a screen rectangle: an offset, a scale, and the mapping between the two.
 *
 * <h2>What it replaces</h2>
 *
 * <p>A pan-and-zoom canvas normally carries a {@code panX}, a {@code panY} and a {@code zoom}, and then
 * writes the same three-line conversion at every site that needs a coordinate — once to draw a node,
 * once to hit-test one, once to work out what a scroll did. Four copies of one transform, and the
 * copies drift: the one that hit-tests is the one nobody re-reads.
 *
 * <p>So the transform is here, and there is exactly one of it. Content coordinates go in, screen
 * coordinates come out, and the inverse is available for the mouse. The canvas stops being the place
 * in a codebase that knows how to map one space onto another.
 *
 * <h2>Two ranges, one object</h2>
 *
 * <p>A scrolling list is a pan-and-zoom canvas at a small enough range: an offset, a scale of one, and
 * a content height that decides how far the offset may go. Rather than a second class, {@link #scale}
 * is allowed to be one and the clamping is switched on by saying how big the content is. That is why
 * {@link ScrollView} is a thin thing rather than a parallel implementation — and it is also why the
 * clamp lives in one place, where a scroll view's thumb and the offset itself cannot disagree.
 *
 * <h2>Bounded pan, and unbounded pan, are both correct</h2>
 *
 * <p>Clamping is <b>opt-in</b>, by calling {@link #setContentSize}. Until then the offset is whatever
 * the caller set, and a canvas may be dragged off into empty space, which is what a graph canvas wants:
 * a player exploring a large questline should not be stopped at an invisible edge, and a chapter's
 * bounding box is not a wall. A scrolling list instead says how tall its content is and gets a
 * clamp, which is the behaviour a scrollbar needs to exist at all.
 *
 * <p><b>Game-free by design.</b> Two {@code float}s and a handful of {@code int}s — no font, no
 * renderer, no widget. That is what lets the zoom-about-the-pointer arithmetic, which is the part
 * everyone gets wrong once, be asserted against a known transform instead of judged in a screenshot.
 */
public final class Viewport {

    private final float minScale;
    private final float maxScale;

    private float scale = 1F;

    /**
     * Where content's origin sits on screen.
     *
     * <p>Held rather than recomputed from the view rectangle, because a viewport is used at two ranges:
     * a canvas's origin is its canvas's top-left corner and never moves, while a scrolling list's
     * bounds can change with the window. Keeping the origin separate means the offset means the same
     * thing in both, and moving a view does not silently invalidate a pan.
     */
    private int originX;
    private int originY;
    private int viewWidth;
    private int viewHeight;

    /** The content origin's position, relative to {@link #originX}/{@link #originY}. */
    private int offsetX;
    private int offsetY;

    /** How big the content is, for clamping. Negative means unbounded -- see the class note. */
    private int contentWidth = -1;
    private int contentHeight = -1;

    private Viewport(float minScale, float maxScale) {
        if (minScale <= 0F) {
            throw new IllegalArgumentException("minScale must be positive: " + minScale);
        }
        if (maxScale < minScale) {
            throw new IllegalArgumentException("maxScale (" + maxScale + ") is below minScale (" + minScale + ")");
        }
        this.minScale = minScale;
        this.maxScale = maxScale;
    }

    /**
     * A viewport that can be zoomed between two scales, inclusive.
     *
     * @param minScale the most zoomed-out scale. Must be positive.
     * @param maxScale the most zoomed-in scale. Must not be below {@code minScale}.
     */
    public static Viewport of(float minScale, float maxScale) {
        return new Viewport(minScale, maxScale);
    }

    /**
     * A viewport that cannot zoom, for a view that only scrolls.
     *
     * <p>Not the same as {@code of(1F, 1F)} only by brevity: a scroll-only view that reports a zoom
     * range of one is a view whose zoom controls would be drawn and enabled and do nothing. Saying it
     * has one scale keeps that from being a thing a caller can offer by accident.
     */
    public static Viewport fixed() {
        return new Viewport(1F, 1F);
    }

    // ------------------------------------------------------------------
    // The view rectangle
    // ------------------------------------------------------------------

    /** Where the view sits on screen, and how big it is. Returns this, so it reads as a chain. */
    public Viewport bounds(int left, int top, int width, int height) {
        this.originX = left;
        this.originY = top;
        this.viewWidth = width;
        this.viewHeight = height;
        return this;
    }

    /** The view's left edge on screen. */
    public int originX() {
        return originX;
    }

    /** The view's top edge on screen. */
    public int originY() {
        return originY;
    }

    /** The view's width in pixels. */
    public int viewWidth() {
        return viewWidth;
    }

    /** The view's height in pixels. */
    public int viewHeight() {
        return viewHeight;
    }

    /** The view's right edge on screen. Half-open, like every rectangle in this kit. */
    public int viewRight() {
        return originX + viewWidth;
    }

    /** The view's bottom edge on screen. */
    public int viewBottom() {
        return originY + viewHeight;
    }

    /** Whether a screen point is inside the view. The test a click should be rejected by. */
    public boolean containsScreen(double screenX, double screenY) {
        return screenX >= originX && screenX < viewRight() && screenY >= originY && screenY < viewBottom();
    }

    // ------------------------------------------------------------------
    // Scale
    // ------------------------------------------------------------------

    /** The current scale. One means content pixels are screen pixels. */
    public float scale() {
        return scale;
    }

    /** The most zoomed-out scale this viewport allows. */
    public float minScale() {
        return minScale;
    }

    /** The most zoomed-in scale this viewport allows. */
    public float maxScale() {
        return maxScale;
    }

    /** Clamps to the allowed range and returns whether anything changed. */
    public boolean setScale(float next) {
        float clamped = clampScale(next);
        if (clamped == scale) {
            return false;
        }
        scale = clamped;
        clampOffset();
        return true;
    }

    private float clampScale(float value) {
        return Math.max(minScale, Math.min(maxScale, value));
    }

    // ------------------------------------------------------------------
    // Offset, and how far it is allowed to go
    // ------------------------------------------------------------------

    /** The x offset: where content's x=0 lands, relative to the view's left edge. */
    public int offsetX() {
        return offsetX;
    }

    /** The y offset: where content's y=0 lands, relative to the view's top edge. */
    public int offsetY() {
        return offsetY;
    }

    /** Moves the content by a screen-space delta, clamped if the content size is known. */
    public void panBy(int dx, int dy) {
        setOffset(offsetX + dx, offsetY + dy);
    }

    /** Places the content origin, clamped if the content size is known. */
    public void setOffset(int x, int y) {
        this.offsetX = x;
        this.offsetY = y;
        clampOffset();
    }

    /**
     * How big the content is, so the offset can be clamped. Negative means unbounded.
     *
     * <p>Switching this on is what makes a view a scrolling list rather than a canvas. A content that
     * fits inside the view is pinned to the top rather than centred: a short list that moves when the
     * window resizes reads as a layout fault, and there is nothing to scroll either way.
     */
    public void setContentSize(int width, int height) {
        this.contentWidth = width;
        this.contentHeight = height;
        clampOffset();
    }

    /** How tall the content is, or a negative number if unbounded. */
    public int contentHeight() {
        return contentHeight;
    }

    /** The scroll position, as a positive number: zero at the top of the content. */
    public int scrollY() {
        return -offsetY;
    }

    /** The largest scroll position, or zero when the content fits. */
    public int maxScrollY() {
        if (contentHeight < 0) {
            return 0;
        }
        return Math.max(0, Math.round(contentHeight * scale) - viewHeight);
    }

    /** Scrolls to a position. Clamped, so a caller may pass an overshoot and get the end. */
    public void setScrollY(int scroll) {
        setOffset(offsetX, -Math.max(0, scroll));
    }

    /** Scrolls by a delta in screen pixels. */
    public void scrollBy(int dy) {
        setScrollY(scrollY() + dy);
    }

    private void clampOffset() {
        offsetX = clampAxis(offsetX, contentWidth, viewWidth);
        offsetY = clampAxis(offsetY, contentHeight, viewHeight);
    }

    private int clampAxis(int offset, int content, int view) {
        if (content < 0) {
            return offset;
        }
        int scaled = Math.round(content * scale);
        if (scaled <= view) {
            return 0;
        }
        // The two limits are "content's far edge at the view's far edge" and "content's near edge at
        // the view's near edge". Written as min/max the wrong way round is how a scroll view ends up
        // scrolling backwards, which is why both are named here rather than inlined.
        int furthest = view - scaled;
        return Math.max(furthest, Math.min(0, offset));
    }

    // ------------------------------------------------------------------
    // Content to screen, and back
    // ------------------------------------------------------------------

    /**
     * Where a content x lands on screen.
     *
     * <p>A float in, because content coordinates are floats — they come out of a quest file and are
     * scaled by the zoom — and rounding one on the way in would move a node by a pixel at some zooms and
     * not others, for no reason other than the parameter's type. The rounding happens once, on the way
     * out, which is where the pixel grid actually is.
     */
    public int screenX(float contentX) {
        return originX + offsetX + Math.round(contentX * scale);
    }

    /** Where a content y lands on screen. See {@link #screenX} for why the parameter is a float. */
    public int screenY(float contentY) {
        return originY + offsetY + Math.round(contentY * scale);
    }

    /** A content length, as screen pixels. The one place scale is applied to a size. */
    public int scaled(int contentLength) {
        return Math.round(contentLength * scale);
    }

    /** The content x under a screen x. Double in, because mouse coordinates are. */
    public float contentX(double screenX) {
        return (float) ((screenX - originX - offsetX) / scale);
    }

    /** The content y under a screen y. */
    public float contentY(double screenY) {
        return (float) ((screenY - originY - offsetY) / scale);
    }

    // ------------------------------------------------------------------
    // The visible rect
    // ------------------------------------------------------------------

    /** The leftmost content x still on screen. */
    public float visibleLeft() {
        return contentX(originX);
    }

    /** The topmost content y still on screen. */
    public float visibleTop() {
        return contentY(originY);
    }

    /** The content x just past the right edge of the view. */
    public float visibleRight() {
        return contentX(viewRight());
    }

    /** The content y just past the bottom edge of the view. */
    public float visibleBottom() {
        return contentY(viewBottom());
    }

    // ------------------------------------------------------------------
    // Zoom, about a point
    // ------------------------------------------------------------------

    /**
     * Changes the scale while keeping the content point under a screen point exactly where it is.
     *
     * <p>The whole calculation is three lines of algebra and the reason this method exists rather than
     * being written at the call site: convert the pointer to a content coordinate, scale, then solve
     * for the offset that puts that same coordinate back under the pointer. Getting it wrong is what
     * makes a zoom appear to run away from the cursor, which is the single most common complaint about
     * a graph UI and looks like the wheel is being handled twice.
     *
     * @return whether the scale actually changed. False at a limit, so a caller can skip a redraw.
     */
    public boolean zoomAt(double screenX, double screenY, float factor) {
        float next = clampScale(scale * factor);
        if (next == scale) {
            // Already at a limit. Returning here rather than recomputing is what stops a view that has
            // been scrolled to its maximum from drifting a pixel per wheel notch while the scale does
            // not move.
            return false;
        }

        float contentX = contentX(screenX);
        float contentY = contentY(screenY);
        scale = next;

        // Solved for the offset directly rather than by panning and re-clamping: the point under the
        // cursor is the contract, so it is what the arithmetic is written in terms of.
        offsetX = Math.round((float) (screenX - originX) - contentX * next);
        offsetY = Math.round((float) (screenY - originY) - contentY * next);
        clampOffset();
        return true;
    }

    /** Zooms about the centre of the view, for a control that has no pointer position. */
    public boolean zoomAboutCentre(float factor) {
        return zoomAt(originX + viewWidth / 2.0, originY + viewHeight / 2.0, factor);
    }

    // ------------------------------------------------------------------
    // Fitting content into the view
    // ------------------------------------------------------------------

    /**
     * Places content so its centre sits at the centre of the view.
     *
     * <p>Given a bounding box rather than a size and an origin, because a questline authored at
     * negative coordinates is the normal case for an editor that grows in every direction — and taking
     * a size and an origin would put such content off the edge with the offset arithmetic still looking
     * correct. No clamping: this is the call that defines where a canvas starts, so applying a clamp it
     * has not been asked for would move content the caller just positioned.
     */
    public void centreOn(float minX, float minY, float maxX, float maxY) {
        offsetX = Math.round(viewWidth / 2F - (minX + maxX) / 2F * scale);
        offsetY = Math.round(viewHeight / 2F - (minY + maxY) / 2F * scale);
    }

    @Override
    public String toString() {
        return "Viewport(scale " + scale + ", offset " + offsetX + "," + offsetY
                + ", view " + viewWidth + "x" + viewHeight + ")";
    }
}
