package dev.ellipog.armature.client;

/**
 * The player's text size, as one number the renderer seam reads.
 *
 * <h2>Why a switch here rather than a parameter everywhere</h2>
 *
 * <p>Vanilla scales the whole interface — every widget, every margin — through the GUI scale option.
 * A player who needs <i>text</i> bigger without the whole window shrinking has no setting at all, and
 * this is that setting's engine half: the seam multiplies every text measurement and every drawn
 * string by this factor, so a layout that derives from {@code Measure} grows with its words, and a
 * fixed-height row keeps its height and draws its text larger within it.
 *
 * <p>It sits beside {@code Motion} and for the same reason: it is the player's own accessibility
 * preference, set once by the mod that owns a {@code Look}, and read by the one class that draws.
 * Bounds are enforced here so the file, the card and the renderer cannot disagree about the range.
 *
 * <h2>What does not scale</h2>
 *
 * <p>Icons, item slots, player faces, borders and every pixel the layout reserved before it measured
 * a word. That is deliberate: an icon scaled by 1.5 in a slot sized for the unscaled one would clip,
 * and the toolkit's fixed chrome — buttons, rows, the scrollbar — is sized to hold the default text
 * with room to spare, which is what makes 150% fit without a second layout pass.
 */
public final class TextScale {

    /** The smallest factor offered. Below one is a shrink, which the GUI scale already does better. */
    public static final double MIN = 1.0D;

    /** The largest. Beyond this the fixed-height chrome genuinely runs out of room. */
    public static final double MAX = 1.5D;

    /** Unscaled. What a client that never touched the setting uses. */
    public static final double DEFAULT = 1.0D;

    private static volatile double scale = DEFAULT;

    private TextScale() {
    }

    /** The factor in force. Never outside {@link #MIN}..{@link #MAX}. */
    public static double get() {
        return scale;
    }

    /**
     * Sets the factor, clamped.
     *
     * <p>Clamped rather than refused: this is called from a file read as well as from a control, and
     * a file holding 9 should draw readable text rather than throw while a frame is being built.
     */
    public static void set(double next) {
        scale = clamp(next);
    }

    /** The nearest legal factor to the one asked for. */
    public static double clamp(double value) {
        if (Double.isNaN(value)) {
            return DEFAULT;
        }
        return Math.max(MIN, Math.min(MAX, value));
    }

    /** A pixel measurement at the current factor. */
    public static int of(int pixels) {
        return (int) Math.round(pixels * scale);
    }
}
