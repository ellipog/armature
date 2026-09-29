package dev.ellipog.armature.client.ui.kit;

import java.util.Objects;

/**
 * Whether this client animates its UI, and how.
 *
 * <h2>Why a toggle exists at all</h2>
 *
 * <p>Three reasons, and only the first is about taste.
 *
 * <p><b>Accessibility.</b> Motion sensitivity is a real thing, and an interface that eases every
 * highlight is an interface some people cannot comfortably use. A single switch that turns every
 * transition off is the standard accommodation, and it is cheap to provide here because every
 * animation in this toolkit already goes through {@link Tween} and {@link Easing}.
 *
 * <p><b>Debugging.</b> A transition that is broken and a transition that is merely slow look
 * identical while they are running. With motion off, every state is its final state, and a colour
 * that is wrong is wrong immediately — which is the difference between finding a palette fault in a
 * second and finding it by watching a fade.
 *
 * <p><b>And it makes the tests honest.</b> {@code Motion.off()} is how a test asserts what a control
 * looks like <i>settled</i> without waiting for a clock. A test that has to advance a tween to its end
 * before it can compare a colour is a test with a fixture in it that is not the thing under test.
 *
 * <h2>Off does not mean "no animation" — it means "no duration"</h2>
 *
 * <p>{@link #scaledDuration} returns zero, so a {@link Tween} snaps to its target on the frame it is
 * retargeted. Every caller keeps its structure: the tween still exists, {@code retarget} is still
 * called, {@code value} is still asked for. Nothing takes a different branch through the drawing code.
 *
 * <p>That is the property worth keeping, and it was a choice rather than the obvious one. The
 * alternative — an {@code if (Motion.enabled())} around each animation — would mean two drawing paths
 * through every animated control, only one of which is ever exercised by default, which is how a bug
 * hides in the path nobody runs.
 *
 * <h2>Static, and why that is not laziness</h2>
 *
 * <p>It describes the <i>client</i>, not a screen. There is one setting, it applies to every control
 * drawn, and reading it from a field on each of them would mean threading it through constructors for
 * no gain — the same reasoning as {@code ArmatureClient}'s own tick state. Not {@code volatile}:
 * written by a command on the client thread, read while drawing on the client thread.
 */
public final class Motion {

    private static boolean enabled = true;
    private static Easing defaultEasing = Tween.DEFAULT_EASING;
    private static long defaultDuration = Tween.DEFAULT_MILLIS;

    private Motion() {
    }

    /** Whether transitions animate. On unless somebody turns it off. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Turns animation on or off.
     *
     * <p>On the next frame every in-flight tween snaps, because its duration becomes zero — see
     * {@link #scaledDuration}. No tween needs to be told, and none needs to be hunted down, which is
     * the point of routing everything through this class rather than checking the flag at each site.
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * The duration a tween should use, given one it asked for.
     *
     * <p>Zero when motion is off, so the tween arrives immediately. Not "a very small number": a tween
     * with a duration of one millisecond still takes a frame, and a UI that flickers one frame of
     * intermediate colour is harder to reason about than one that does not.
     *
     * <p><b>Also zero when the client's default duration is zero</b>, and that is the half a theme
     * needs. A theme declaring no motion — {@code vanilla_plus} does, because vanilla has none — is
     * saying "this UI does not animate", which has to win over whatever duration an individual call
     * site happened to ask for. Without this, a theme could set its motion to zero and every tween
     * that named its own duration would carry on easing: the theme would be describing an appearance
     * it did not actually produce.
     */
    public static long scaledDuration(long requested) {
        if (!enabled || defaultDuration == 0L) {
            return 0L;
        }
        return requested;
    }

    /**
     * A tween already configured for this client: its duration scaled by {@link #enabled()}, and the
     * default curve unless a caller replaces it.
     *
     * <p>The usual way to make one, so a control does not have to remember to scale its own duration —
     * which is exactly the sort of thing one control would forget.
     */
    public static Tween tween(float initial, long duration) {
        return Tween.settled(initial, scaledDuration(duration), defaultEasing);
    }

    /** The same, at the toolkit's default duration. */
    public static Tween tween(float initial) {
        return tween(initial, defaultDuration);
    }

    /** How long transitions last when motion is on. */
    public static long defaultDuration() {
        return defaultDuration;
    }

    /** The curve transitions use by default. */
    public static Easing defaultEasing() {
        return defaultEasing;
    }

    /**
     * Sets the default duration, for a screen or a theme (R6) that wants slower or faster motion.
     *
     * <p>A negative value is refused rather than clamped, because it can only come from a mistyped
     * constant — the same reasoning as {@link Tween}'s own refusal.
     */
    public static void setDefaultDuration(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("a duration cannot be negative: " + millis);
        }
        defaultDuration = millis;
    }

    /** Sets the default curve. */
    public static void setDefaultEasing(Easing easing) {
        defaultEasing = Objects.requireNonNull(easing, "easing");
    }

    /** A one-line description, for a command that reports the current setting. */
    public static String describe() {
        return enabled
                ? "on (" + defaultDuration + "ms, " + defaultEasing + ")"
                : "off (every transition is instant)";
    }

    @Override
    public String toString() {
        return "Motion(" + describe() + ")";
    }
}
