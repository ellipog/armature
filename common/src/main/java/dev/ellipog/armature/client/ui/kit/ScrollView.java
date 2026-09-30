package dev.ellipog.armature.client.ui.kit;

import dev.ellipog.armature.client.render.GuiRenderer;

import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A scrolled region: a {@link Viewport}, the widgets inside it, and the placement pass between them.
 *
 * <h2>Why the widgets live here rather than in the screen</h2>
 *
 * <p>The alternative is a screen that lays out its own rows, hit-tests its own slots and draws its own
 * text. That is smaller today, and it is a dead end: a hand-drawn row cannot take focus, cannot be
 * narrated, cannot be reached by tab, and has no hover state unless the screen reimplements one per
 * row. Every one of those is already solved by {@code AbstractWidget}, and the price of getting them
 * is that something has to move the widget when the content scrolls.
 *
 * <p>So that is this class's whole job: pay the repositioning cost once, so the screen never does. The
 * screen adds a control and gives it a key; the {@link Layout} decides where it goes; this decides what
 * the scroll has done to that, and whether it is visible at all.
 *
 * <h2>Culling is a correctness property, not an optimisation</h2>
 *
 * <p>A row scrolled past the bottom of the viewport is hidden by setting {@code visible = false}. That
 * is not only about not drawing it. {@code AbstractWidget.isMouseOver} already returns false for an
 * invisible widget, and {@code mouseClicked} refuses to fire on one — so a click landing in the band
 * below the clip, where a scrolled-out row happens to sit in content coordinates, cannot reach it.
 * Without the cull, a control a player cannot see would still be clickable, which is the sort of bug
 * that gets reported as "the button in the corner does something".
 *
 * <h2>The clip is pushed by the caller, deliberately</h2>
 *
 * <p>This class positions and hides; {@link Clip} narrows the drawing. They are separate because they
 * have different scopes: the clip must wrap <i>all</i> of the caller's drawing of the region — its
 * background, its rules, its text — while the cull is about the widgets alone. One class doing both
 * would have to own the drawing loop, and then a caller could not draw anything of its own inside its
 * own scroll region.
 *
 * <p>So the usual shape at a call site is:
 *
 * <pre>
 * view.apply(layout, bodyWidth);
 * try (Clip clip = Clip.push(graphics, view.viewport())) {
 *     drawBackground(graphics);
 *     super.render(graphics, mouseX, mouseY, partialTick);   // the widgets, now placed
 * }
 * view.drawScrollbar(graphics, trackColour, thumbColour);
 * </pre>
 *
 * <h2>The scrollbar is a control, not decoration</h2>
 *
 * <p>This class used to draw the bar and offer nothing to press it with, on the reasoning that a
 * scrollbar is chrome. That is wrong in the way that matters: a bar beside a list is the thing a
 * pointer goes to when the list is long, and a bar that cannot be dragged is a picture of a
 * scrollbar. So the geometry is exposed — {@link #scrollbarTrack}, {@link #scrollbarThumb} — and the
 * drag is here beside it, {@link #beginThumbDrag} through {@link #endThumbDrag}.
 *
 * <p>It lives in this class rather than in the screen because the mapping between a pointer's y and a
 * scroll offset is <b>the inverse of the drawing's own formula</b>. Split across two files, the
 * forward and inverse versions would agree on the day they were written and drift on the next edit —
 * and the symptom would be a thumb that slowly walks away from the pointer as you drag, which reads
 * as the list being over-sensitive rather than as an arithmetic fault.
 *
 * <p>What is deliberately <i>not</i> here is the colour. The bar is drawn in whatever the caller hands
 * over, because a colour is a theme's business; this class only says where and how big.
 *
 * <h2>Fit and scroll are the same mechanism</h2>
 *
 * <p>{@link #apply} tells the viewport how tall the content is, which is what makes the offset clamp
 * and the scrollbar agree by construction. A caller cannot forget it, because it is not a separate
 * call: it is the same one that placed the widgets, and it takes the height from the same
 * {@link Layout} that produced the slots.
 *
 * <h2>A widget with no slot is hidden, not left alone</h2>
 *
 * <p>Anything in {@link #children} that the current layout has no slot for is hidden during
 * {@link #apply}. That is what stops a control from a previous quest — Submit for a task that no
 * longer exists — from lingering on screen, invisible-but-clickable, because somebody forgot to remove
 * it. Adding a widget after the last {@code apply} leaves it hidden until the next one, which is the
 * safe direction for the same reason.
 *
 * <p><b>Names {@code AbstractWidget}, deliberately and by name, and no longer names the renderer.</b>
 * It places and culls real widgets, which is the price of controls that can take focus and be
 * narrated — there is no version of that which does not touch the game's widget type. What it no
 * longer does is draw: {@link #drawScrollbar} takes a {@link GuiRenderer}, so the drawing half of this
 * file went through the seam and only the widget half remains. That is the shape of the R4 work at
 * its best — an exception that shrank rather than one that was granted and kept.
 */
public final class ScrollView {

    private final Viewport viewport;
    private final Map<Object, AbstractWidget> children = new LinkedHashMap<>();

    /** The last layout applied, or null before the first {@link #apply}. */
    private Layout layout;

    private int shown;
    private int hidden;

    private ScrollView(Viewport viewport) {
        this.viewport = viewport;
    }

    /** A scroll view over a viewport. The viewport's bounds are the region; its offset is the scroll. */
    public static ScrollView of(Viewport viewport) {
        Objects.requireNonNull(viewport, "viewport");
        return new ScrollView(viewport);
    }

    /** The viewport this view scrolls within. */
    public Viewport viewport() {
        return viewport;
    }

    // ------------------------------------------------------------------
    // Children
    // ------------------------------------------------------------------

    /**
     * Registers a widget under a key the layout will place it by.
     *
     * <p>The key is not decoration: it is the only thing joining the caller's description of an element
     * (in {@link Stack}) to the control that draws it. A null key is refused rather than accepted and
     * silently never placed, because a widget registered under a key the layout cannot produce is a
     * control that exists, costs its layout height, and is never drawn.
     */
    public ScrollView put(Object key, AbstractWidget widget) {
        Objects.requireNonNull(key, "key -- a widget with no key can never be matched to a slot");
        children.put(key, Objects.requireNonNull(widget, "widget"));
        return this;
    }

    /** The widget registered under a key, or null. */
    public AbstractWidget get(Object key) {
        return children.get(key);
    }

    /** How many widgets are registered. */
    public int size() {
        return children.size();
    }

    /** Forgets every widget and the layout. For a screen rebuilding itself for a different quest. */
    public ScrollView clear() {
        children.clear();
        layout = null;
        shown = 0;
        hidden = 0;
        return this;
    }

    // ------------------------------------------------------------------
    // Placing
    // ------------------------------------------------------------------

    /**
     * Places the registered widgets from a layout, and sizes the scroll range from its height.
     *
     * <p>The one call that has to happen before the widgets are drawn. It does three things in one
     * pass, on purpose: tells the viewport how tall the content is, moves every widget to its slot's
     * on-screen position, and hides those outside the view. Splitting those would let a caller do two
     * of the three, which is exactly the half-updated state a scrollbar cannot detect.
     *
     * @param layout       what to place. Its slot keys are matched against {@link #children}.
     * @param contentWidth how wide the content is, for a viewport that clamps horizontally.
     */
    public void apply(Layout layout, int contentWidth) {
        Objects.requireNonNull(layout, "layout");
        this.layout = layout;
        viewport.setContentSize(Math.max(0, contentWidth), layout.height());
        place();
    }

    /** The layout last applied, or null. */
    public Layout layout() {
        return layout;
    }

    /**
     * Moves the content by a screen-space delta and re-places.
     *
     * <p>Re-placing here rather than leaving it to the next frame is what keeps a scrolled widget from
     * spending one frame at its old position — which, on a fast wheel, is a visible stutter, and on a
     * click is a control being hit-tested where it used to be.
     */
    public void scrollBy(int dy) {
        viewport.scrollBy(dy);
        place();
    }

    /** Scrolls to an absolute position and re-places. Clamped by the viewport. */
    public void scrollTo(int scrollY) {
        viewport.setScrollY(scrollY);
        place();
    }

    /** Whether a screen point is inside the region. The test that rejects a click outside the clip. */
    public boolean accepts(double screenX, double screenY) {
        return viewport.containsScreen(screenX, screenY);
    }

    /** How many widgets the last placement left visible. */
    public int placed() {
        return shown;
    }

    /** How many it culled. Both counts exist so a test can assert the cull *ran*, not just worked. */
    public int culled() {
        return hidden;
    }

    /**
     * Where a widget ended up on screen, or null if it was culled.
     *
     * <p>Reads the widget's own position rather than recomputing it, so a caller that asks and a
     * renderer that draws cannot disagree — the same reason the layout returns keys instead of
     * coordinates.
     */
    public Slot placedSlot(Object key) {
        AbstractWidget widget = children.get(key);
        if (widget == null || !widget.visible) {
            return null;
        }
        return new Slot(key, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
    }

    private void place() {
        shown = 0;
        hidden = 0;

        if (layout == null) {
            for (AbstractWidget widget : children.values()) {
                widget.visible = false;
            }
            return;
        }

        List<Object> matched = new ArrayList<>(children.size());

        for (Slot slot : layout.slots()) {
            if (!slot.interactive()) {
                continue;
            }
            AbstractWidget widget = children.get(slot.key());
            if (widget == null) {
                // A slot the caller described but registered nothing for. Legitimate: a row that is
                // drawn by the screen rather than hosted as a widget.
                continue;
            }

            int x = viewport.screenX(slot.x());
            int y = viewport.screenY(slot.y());
            int w = Math.max(0, viewport.scaled(slot.width()));
            int h = Math.max(0, viewport.scaled(slot.height()));

            widget.setX(x);
            widget.setY(y);
            widget.setWidth(w);
            widget.setHeight(h);

            // Half-open at both ends, matching Slot.contains: a row whose bottom edge is exactly at the
            // viewport's top edge is off screen, so it is not drawn and cannot be clicked.
            boolean onScreen = y < viewport.viewBottom() && y + h > viewport.originY();
            widget.visible = onScreen;

            if (onScreen) {
                shown++;
            }
            else {
                hidden++;
            }
            matched.add(slot.key());
        }

        for (Map.Entry<Object, AbstractWidget> entry : children.entrySet()) {
            if (!matched.contains(entry.getKey())) {
                // Registered but not in this layout. Hidden rather than left as it was: see the class
                // note on the control from a previous quest.
                entry.getValue().visible = false;
            }
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * The scrollbar, drawn only when there is something to scroll.
     *
     * <p>Here rather than in the caller because every number it needs comes from the viewport, and a
     * second implementation at a call site is a second chance for the thumb and the offset to disagree
     * — a thumb that stops short of the end is a scroll view that looks broken while working.
     *
     * <p>The thumb has a minimum height, because a scaled-to-proportion thumb on a very long document
     * becomes a two-pixel line that reads as an artefact rather than as a control.
     *
     * <p>Takes a {@link GuiRenderer} rather than a graphics context, so this kit file no longer names
     * the game — see the class note below, which used to be an exception and is now history.
     *
     * @param renderer     the target
     * @param trackColour  the colour of the full-height track
     * @param thumbColour  the colour of the grip
     */
    public void drawScrollbar(GuiRenderer renderer, int trackColour, int thumbColour) {
        Objects.requireNonNull(renderer, "renderer");

        Slot track = scrollbarTrack();
        if (track == null) {
            // Everything fits. A track with a full-height thumb in it says "there is more" and lies.
            return;
        }

        Slot thumb = scrollbarThumb();
        renderer.fill(track.x(), track.y(), track.right(), track.bottom(), trackColour);
        renderer.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), thumbColour);
    }

    // ------------------------------------------------------------------
    // The scrollbar as a control
    // ------------------------------------------------------------------

    /**
     * Where the scrollbar's bar is drawn: a three-pixel column four pixels right of the viewport.
     *
     * <p>Null when everything fits, which is the same condition {@link #drawScrollbar} returns early
     * on. One condition, asked in one place — a hit test that accepted a press on a bar nobody drew
     * would swallow a click in the margin of a list that does not scroll.
     */
    public Slot scrollbarTrack() {
        if (viewport.maxScrollY() <= 0 || thumbHeight() <= 0) {
            return null;
        }
        return new Slot(null, viewport.viewRight() + 4, viewport.originY(), BAR_WIDTH,
                viewport.viewHeight());
    }

    /** Where the thumb is drawn, or null when everything fits. */
    public Slot scrollbarThumb() {
        Slot track = scrollbarTrack();
        if (track == null) {
            return null;
        }
        return new Slot(null, track.x(), thumbTop(), BAR_WIDTH, thumbHeight());
    }

    /**
     * Whether a point should count as a press on the scrollbar.
     *
     * <h2>Why this is deliberately wider than the bar</h2>
     *
     * <p>The bar is three pixels. Three pixels is a target you miss, and a miss on a scrollbar lands on
     * the list underneath — so the failure mode of an exact hit test is not "nothing happened", it is
     * "I clicked the thing behind it". The band here runs from just past the viewport's right edge to
     * the far side of the bar, which is about nine pixels: comfortably grabbable, and still outside
     * the viewport, so it cannot steal a click from a row.
     */
    public boolean scrollbarHit(double screenX, double screenY) {
        Slot track = scrollbarTrack();
        if (track == null) {
            return false;
        }
        return screenX >= viewport.viewRight() + 1
                && screenX < track.right() + BAR_GRAB
                && screenY >= track.y()
                && screenY < track.bottom();
    }

    /**
     * Starts a drag, remembering where inside the thumb the pointer landed.
     *
     * <h2>Why the grab offset is kept</h2>
     *
     * <p>Because grabbing a thumb's middle and having it jump so its <i>top</i> is under the pointer is
     * the thing that makes a hand-rolled scrollbar feel wrong. The offset is the distance from the
     * thumb's top to the press, and every later position is computed from it — so the thumb stays
     * exactly where the pointer grabbed it for the whole drag.
     */
    public void beginThumbDrag(double screenY) {
        if (scrollbarTrack() == null) {
            return;
        }
        draggingThumb = true;
        grabOffset = (int) screenY - thumbTop();
    }

    /** Whether a drag is in progress. A caller routes move and release on this. */
    public boolean draggingThumb() {
        return draggingThumb;
    }

    /**
     * Moves the thumb so the pointer still holds the same point on it, and scrolls to match.
     *
     * <p>The arithmetic is the inverse of the drawing's, and it is written as that inverse on purpose:
     * {@link #thumbTop} computes {@code top} from {@code scrollY}, and this computes {@code scrollY}
     * from a {@code top}. Two independent formulas that agree today would disagree after one edit, and
     * the symptom would be a thumb that creeps away from the pointer the longer you drag.
     *
     * <p>Rounded rather than integer-divided. An integer division loses up to a pixel per event, and
     * this is called on every mouse move — so a slow drag across a long list would arrive short of the
     * bottom, which reads as the scroll range being wrong rather than as rounding.
     */
    public void dragThumbTo(double screenY) {
        if (!draggingThumb) {
            return;
        }
        Slot track = scrollbarTrack();
        int travel = track.bottom() - thumbHeight() - track.y();
        if (travel <= 0) {
            // The thumb fills the track, so there is nowhere to drag it. Not an error: it is what a
            // list one row taller than its viewport produces.
            return;
        }
        int wanted = (int) screenY - grabOffset - track.y();
        int max = viewport.maxScrollY();
        scrollTo(Math.round(Math.max(0, Math.min(travel, wanted)) * (float) max / travel));
    }

    /** Always true on release, not a state change. Idempotent, so a doubled release is harmless. */
    public boolean endThumbDrag() {
        boolean was = draggingThumb;
        draggingThumb = false;
        grabOffset = 0;
        return was;
    }

    /** Three pixels wide, which is what the drawing has always used. */
    private static final int BAR_WIDTH = 3;

    /** How far past the bar a press still counts. Six, so the whole band is about nine pixels. */
    private static final int BAR_GRAB = 6;

    private boolean draggingThumb;
    private int grabOffset;

    /** The thumb's drawn height, or 0 when there is no bar. */
    private int thumbHeight() {
        int trackHeight = viewport.viewHeight();
        if (trackHeight <= 0 || viewport.maxScrollY() <= 0) {
            return 0;
        }
        int content = Math.max(1, viewport.scaled(viewport.contentHeight()));
        return Math.min(trackHeight, Math.max(16, trackHeight * trackHeight / content));
    }

    /** The thumb's drawn top. The forward formula; {@link #dragThumbTo} is its inverse. */
    private int thumbTop() {
        Slot track = scrollbarTrack();
        int travel = track.bottom() - thumbHeight() - track.y();
        if (travel <= 0) {
            return track.y();
        }
        return track.y() + travel * viewport.scrollY() / Math.max(1, viewport.maxScrollY());
    }

    @Override
    public String toString() {
        return "ScrollView(" + children.size() + " child(ren), " + viewport + ")";
    }
}
