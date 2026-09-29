package dev.ellipog.armature.client.ui.kit;

/**
 * Space around something: padding when it is inside a box, margin when it is outside one.
 *
 * <h2>Four numbers, not one</h2>
 *
 * <p>A single padding value with a special case for the bottom is the usual shortcut, and it is the
 * wrong default here. The asymmetry is the common case rather than the exception: a heading wants air
 * above it and almost none below, a list row wants a left indent its right edge must not share, and a
 * panel's bottom inset is usually the footer's own height rather than a spacing decision at all. With
 * one number, every one of those becomes a hand-written correction at the call site.
 *
 * <h2>Negative is allowed, and is not a bug</h2>
 *
 * <p>A control that overhangs the box it is drawn in is a real layout -- an overlapping badge, a
 * label that deliberately runs to the edge. Clamping to zero here would not prevent it; it would move
 * the arithmetic into the caller, which is the thing this record exists to avoid.
 *
 * <p><b>Game-free by design.</b> See the package notes on {@code Stack} for what that buys.
 *
 * @param left   space on the left
 * @param top    space above
 * @param right  space on the right
 * @param bottom space below
 */
public record Insets(int left, int top, int right, int bottom) {

    /** No space at all. The identity, and the default for an element that sets none. */
    public static final Insets NONE = new Insets(0, 0, 0, 0);

    /** The same amount on every side. */
    public static Insets all(int amount) {
        return new Insets(amount, amount, amount, amount);
    }

    /**
     * The same amount left and right, and the same amount top and bottom.
     *
     * <p>Named rather than left to {@code all(h)} plus {@code new Insets(...)}, because "10 across,
     * 4 down" is the most common inset in a list and writing it out invites transposing the two.
     */
    public static Insets symmetric(int horizontal, int vertical) {
        return new Insets(horizontal, vertical, horizontal, vertical);
    }

    /** Total space this takes off a width. */
    public int horizontal() {
        return left + right;
    }

    /** Total space this takes off a height. */
    public int vertical() {
        return top + bottom;
    }
}
