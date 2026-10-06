package dev.ellipog.armature.client.ui.kit;

import dev.ellipog.armature.client.render.GuiRenderer;

import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A scrolled region: a {@link Viewport}, the widgets inside it, the placement pass between them, and
 * the {@link ScrollBar} that scrolls it.
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
 * <p>This class positions and hides; the renderer's {@code clip} narrows the drawing. They are separate
 * because they have different scopes: the clip must wrap <i>all</i> the caller's drawing of the region
 * — its background, its rules, its text — while the cull is about the widgets alone. One class doing
 * both would have to own the drawing loop, and then a caller could not draw anything of its own inside
 * its own scroll region.
 *
 * <p>So the usual shape at a call site is:
 *
 * <pre>
 * view.apply(layout, bodyWidth);
 * try (GuiRenderer.Scoped clip = renderer.clip(view.viewport())) {
 *     drawBackground(renderer);
 *     super.render(graphics, mouseX, mouseY, partialTick);   // the widgets, now placed
 * }
 * view.bar().draw(renderer, ArmatureScrollStyle.skin(), mouseX, mouseY, now);
 * </pre>
 *
 * <h2>The bar is a separate object, and that is the newest thing about this class</h2>
 *
 * <p>It used to keep the bar's geometry, its hit test and its drag, and a full account of why a
 * scrollbar is a control rather than decoration. All of that moved to {@link ScrollBar} when the other
 * screens that draw a list needed the same four things — the arithmetic had been copied three times by
 * then, and the copies had already begun to disagree (two different minimum grip heights, two
 * different strip positions, and a hand-rolled thumb that was painted over the last three pixels of
 * every row it described). What is left here is the part that is genuinely about <i>this</i> region: a
 * viewport, the widgets in it, and a bar bound to the strip just outside it.
 *
 * <p>So the strip's position is the one piece of the bar this class still decides — {@code OFFSET}
 * pixels past the viewport's right edge, where {@code BookGeometry.SIDEBAR_SCROLLBAR} reserves room for
 * it — and {@link #bar()} binds it before answering, so a caller that hit-tests the bar and a pass that
 * draws it cannot be looking at two rectangles.
 *
 * <h2>Fit and scroll are the same mechanism</h2>
 *
 * <p>{@link #apply} tells the viewport how tall the content is, which is what makes the offset clamp
 * and the bar agree by construction. A caller cannot forget it, because it is not a separate call: it
 * is the same one that placed the widgets, and it takes the height from the same {@link Layout} that
 * produced the slots.
 *
 * <h2>A widget with no slot is hidden, not left alone</h2>
 *
 * <p>Anything in {@link #children} that the current layout has no slot for is hidden during
 * {@link #apply}. That is what stops a control from a previous entry — Submit for a task that no
 * longer exists — from lingering on screen, invisible-but-clickable, because somebody forgot to remove
 * it. Adding a widget after the last {@code apply} leaves it hidden until the next one, which is the
 * safe direction for the same reason.
 *
 * <p><b>Names {@code AbstractWidget}, deliberately and by name, and does not name the renderer's
 * context.</b> It places and culls real widgets, which is the price of controls that can take focus and
 * be narrated — there is no version of that which does not touch the game's widget type. What it does
 * not do is draw: the bar is a {@link ScrollBar}, which takes a {@link GuiRenderer} and a
 * {@link ScrollBar.Skin}, so this file is the only one in the kit that needs its exception and the
 * drawing half of it went through the seam. That is the shape of the R4 work at its best — an exception
 * that shrank rather than one that was granted and kept.
 */
public final class ScrollView {

    private final Viewport viewport;
    private final ScrollBar bar;
    private final Map<Object, AbstractWidget> children = new LinkedHashMap<>();

    /**
     * How each widget's rectangle is derived from the slot its key holds.
     *
     * <p>Only entries whose derivation is not the identity are stored, which is the common case and the
     * reason this is a second map rather than a required argument everywhere.
     */
    private final Map<Object, Shape> shapes = new LinkedHashMap<>();

    /** The last layout applied, or null before the first {@link #apply}. */
    private Layout layout;

    private int shown;
    private int hidden;

    private ScrollView(Viewport viewport) {
        this.viewport = viewport;
        // The bar re-places these children after every offset it writes, and this is the one place that
        // is set: `ScrollBar.onScrolled` runs after a drag, a page and a wheel, so a widget cannot be
        // left at the position it held before the list moved. See that field's note for the fault this
        // closes -- the tools panel's controls and the sidebar's rows stayed still while their lists
        // scrolled, because both place their widgets only in a rebuild.
        this.bar = new ScrollBar(viewport).onScrolled(this::place);
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

    /**
     * This view's scrollbar, with its strip bound to the viewport's current bounds.
     *
     * <p>Bound on the way out rather than assumed from the last {@link #apply}, because the blame for a
     * stale rectangle is nobody's in particular: {@code render} and {@code mouseClicked} are not in an
     * order a screen can rely on, and a resize is announced to neither. The call is four assignments.
     */
    public ScrollBar bar() {
        return bar.stripOutsideViewport();
    }

    // ------------------------------------------------------------------
    // Children
    // ------------------------------------------------------------------

    /**
     * A widget's rectangle, derived from the slot the layout holds for it.
     *
     * <p>A function rather than a rectangle, so a caller cannot capture a position the layout has not
     * produced yet: the slot it is handed is the one {@link #apply} is placing right now.
     */
    @FunctionalInterface
    public interface Shape {

        /** A widget that occupies its own row: the answer for every control that is the whole of one. */
        Shape IDENTITY = slot -> slot;

        /**
         * The rectangle this widget occupies, in content coordinates.
         *
         * @return the rectangle, or null to hide the widget -- the honest answer for a row that no
         *         longer offers the control, such as a permission that has since been withdrawn
         */
        Slot of(Slot slot);
    }

    /**
     * Registers a widget under a key the layout will place it by.
     *
     * <p>The key is not decoration: it is the only thing joining the caller's description of an element
     * (in {@link Stack}) to the control that draws it. A null key is refused rather than accepted and
     * silently never placed, because a widget registered under a key the layout cannot produce is a
     * control that exists, costs its layout height, and is never drawn.
     */
    public ScrollView put(Object key, AbstractWidget widget) {
        return put(key, widget, Shape.IDENTITY);
    }

    /**
     * The same, for a widget that occupies a <b>strip</b> of its row rather than the whole of it.
     *
     * <h2>Why a control can be smaller than its row</h2>
     *
     * <p>Because a row can carry more than one thing. The party panel's rows are a label with a button
     * beside it, so the button's rectangle is a piece of its row rather than the row itself — and the
     * derivation already exists as a tested pure function ({@code PartyRoster.removeSlot} places a
     * member's Remove button, {@code PartyPanelLayout.buttonStrip} an action's). Handing that function
     * here, at the moment the widget is registered, is what keeps placement from becoming a second copy
     * of it: a caller that computed the strip itself and then had the layout place the whole row would
     * have two derivations of one rectangle, which is the fault this kit exists to make unrepresentable.
     *
     * <p>The function is applied in <b>content</b> coordinates, before the viewport translates and
     * culls, so it knows nothing about the scroll.
     */
    public ScrollView put(Object key, AbstractWidget widget, Shape shape) {
        Objects.requireNonNull(key, "key -- a widget with no key can never be matched to a slot");
        AbstractWidget placed = Objects.requireNonNull(widget, "widget");
        // Hidden until a placement gives it somewhere to be. The class note promises this -- "adding a
        // widget after the last `apply` leaves it hidden until the next one, which is the safe direction"
        // -- and it was not true: a widget is visible by default, so one registered and not yet placed was
        // drawn at whatever rectangle it was constructed with, which for most controls is the origin. A
        // test asserting the documented behaviour found it; the fix is to make the promise the code.
        placed.visible = false;
        children.put(key, placed);
        shapes.put(key, Objects.requireNonNull(shape, "shape"));
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

    /** Forgets every widget and the layout. For a screen rebuilding itself for a different entry. */
    public ScrollView clear() {
        children.clear();
        shapes.clear();
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
        bar.stripOutsideViewport();
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

            // The widget's own rectangle, which is its row's unless the caller derived a strip -- see
            // `put`. A null answer is a control this layout does not offer, which is the same outcome
            // as a row the caller registered nothing for: hidden, and therefore unclickable.
            Slot at = shapes.getOrDefault(slot.key(), Shape.IDENTITY).of(slot);
            if (at == null) {
                widget.visible = false;
                hidden++;
                matched.add(slot.key());
                continue;
            }

            int x = viewport.screenX(at.x());
            int y = viewport.screenY(at.y());
            int w = Math.max(0, viewport.scaled(at.width()));
            int h = Math.max(0, viewport.scaled(at.height()));

            widget.setX(x);
            widget.setY(y);
            widget.setWidth(w);
            widget.setHeight(h);

            // Half-open at both ends, matching Slot.contains: a row whose bottom edge is exactly at the
            // viewport's top edge is off screen, so it is not drawn and cannot be clicked.
            boolean onScreen = y < viewport.viewBottom() && y + h > viewport.originY();

            // And with `whole` set, a widget that is only *partly* inside is hidden rather than
            // half-drawn. That is on the caller to ask for, because what it means depends on who clips:
            // a caller that wraps its own drawing in a scissor wants the partial row drawn and cut by that
            // scissor (the sidebar does), and a caller whose widgets are **not** clipped -- a panel drawn
            // inside a card, whose widget pass belongs to a screen that clips something else entirely --
            // has a half-visible row drawn straight over whatever is above the list. That was a report:
            // *"buttons not going under things when scrolling"*.
            if (whole && (y < viewport.originY() || y + h > viewport.viewBottom())) {
                onScreen = false;
            }
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
                // note on the control from a previous entry.
                entry.getValue().visible = false;
            }
        }
    }

    // ------------------------------------------------------------------
    // The bar
    // ------------------------------------------------------------------

    /**
     * Shows only widgets that are <b>wholly</b> inside the viewport: a partly visible one is hidden.
     *
     * <p>See the note where it is used in {@code place()}. Off by default, because the alternative -- a
     * partial row cut by the caller's own scissor -- is what a clipped list wants and what the sidebar has
     * always done. On for a caller whose widget pass is not clipped to this region: a control that is half
     * scrolled out would otherwise be drawn over the thing above the list.
     */
    public ScrollView whole(boolean value) {
        this.whole = value;
        if (layout != null) {
            place();
        }
        return this;
    }

    private boolean whole;

    @Override
    public String toString() {
        return "ScrollView(" + children.size() + " child(ren), " + viewport + ")";
    }
}
