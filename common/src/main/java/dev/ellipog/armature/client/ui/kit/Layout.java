package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link Stack} placed, and how tall it turned out.
 *
 * <h2>One object, produced once</h2>
 *
 * <p>This is the whole answer to "how tall is that, and where does it go". A caller builds a layout and
 * gets both facts from the same computation, so there is no second pass that can disagree with the
 * first. That is not a tidiness preference: the screen this kit was extracted from has a measure pass
 * and a draw pass that must agree by hand, and is reminded to in a comment, because an empty list
 * still reserves a row. Making that disagreement unrepresentable is the reason this class exists.
 *
 * <h2>Immutable, and cheap to keep</h2>
 *
 * <p>A layout is rebuilt when the input changes -- a different quest, a resized window, a new scroll
 * clamp -- and read on every frame in between. Nothing in it mutates, so a screen can hold one and
 * compare it against the next, and a test can assert on a layout it built without the screen's
 * cooperation.
 *
 * <h2>Keys, for the things that can be clicked</h2>
 *
 * <p>{@link #at} returns the <b>topmost interactive</b> slot containing a point. Non-interactive slots
 * -- headings, prose, gaps, dividers -- are skipped, so a caller asking "what did the player click"
 * never has to filter the answer itself. Reverse order, because a stack is drawn in order and the
 * last thing drawn is the thing on top; a caller that reordered its own drawing would otherwise be
 * answered by something underneath it.
 *
 * <p><b>Game-free by design.</b> Every field is a number or an {@code Object}.
 */
public final class Layout {

    private final List<Slot> slots;
    private final int height;

    private Layout(List<Slot> slots) {
        this.slots = List.copyOf(slots);
        int bottom = 0;
        for (Slot slot : this.slots) {
            bottom = Math.max(bottom, slot.bottom());
        }
        this.height = bottom;
    }

    /** Wraps slots in a layout. Internal -- a {@link Stack} is what builds one. */
    static Layout of(List<Slot> slots) {
        return new Layout(slots);
    }

    /** Every slot, in the order the stack placed them. */
    public List<Slot> slots() {
        return slots;
    }

    /**
     * The height of the content: the bottom edge of the lowest slot.
     *
     * <p>Derived from the slots rather than accumulated alongside them. An accumulator and the slots it
     * describes are two numbers that can disagree, and this class exists because of exactly that.
     */
    public int height() {
        return height;
    }

    /** Whether anything was placed at all. */
    public boolean isEmpty() {
        return slots.isEmpty();
    }

    /**
     * The topmost interactive slot containing a point, or null.
     *
     * @param x the point's x, in the same space the layout was built in
     * @param y the point's y
     */
    public Slot at(double x, double y) {
        for (int i = slots.size() - 1; i >= 0; i--) {
            Slot slot = slots.get(i);
            if (slot.interactive() && slot.contains(x, y)) {
                return slot;
            }
        }
        return null;
    }

    /** The slot a key was placed at, or null. The first, if a caller used one key twice. */
    public Slot slot(Object key) {
        Objects.requireNonNull(key, "key -- a null key is never findable, by design");
        for (Slot slot : slots) {
            if (key.equals(slot.key())) {
                return slot;
            }
        }
        return null;
    }

    /**
     * The slots that intersect a vertical band, for a caller that only wants to draw what is visible.
     *
     * <p>Culling, and it is here rather than at the call site because the rule is about this class's
     * own coordinates: a slot is worth drawing if any part of it is inside the band, which is
     * half-open at the bottom for the same reason {@link Slot#contains} is.
     */
    public List<Slot> visibleIn(int top, int bottom) {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.bottom() > top && slot.y() < bottom) {
                out.add(slot);
            }
        }
        return out;
    }

    /** The slots a scroll offset is inside, ready to be drawn at their on-screen positions. */
    public List<Slot> visibleInViewport(int viewportTop, int viewportBottom, int scrollOffset) {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : slots) {
            Slot onScreen = slot.moved(0, -scrollOffset);
            if (onScreen.bottom() > viewportTop && onScreen.y() < viewportBottom) {
                out.add(onScreen);
            }
        }
        return out;
    }

    /** Every slot moved by an offset, keyed by what it was. For placing a layout into a viewport. */
    public Map<Object, Slot> moved(int dx, int dy) {
        Map<Object, Slot> out = new LinkedHashMap<>();
        for (Slot slot : slots) {
            if (slot.interactive()) {
                out.put(slot.key(), slot.moved(dx, dy));
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "Layout(" + slots.size() + " slot(s), height " + height + ")";
    }
}
