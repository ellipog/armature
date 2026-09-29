package dev.ellipog.armature.client.ui.kit;

/**
 * One value that moves towards a target over a fixed time, and can be given a new target mid-flight.
 *
 * <h2>The retargeting is the whole design</h2>
 *
 * <p>A cursor passes over a button and off it in under a hundred milliseconds, and an animation that
 * cannot be interrupted has to either finish or be abandoned. This one <b>carries on from where it
 * is</b>: {@link #retarget} captures the current value as the new start, so a hover that begins and
 * ends quickly produces a short ease out from wherever the ease in had reached — which is what the
 * hand expects and what snapping back would ruin.
 *
 * <p>That is why this is a class with state rather than a function of {@code (from, to, t)}. The
 * stateless form is simpler and cannot express "carry on from here", which is the only case that
 * looks wrong when it is missing.
 *
 * <h2>Time is passed in, never read</h2>
 *
 * <p>Every method takes {@code nowMillis}. Nothing here calls a clock, which is what makes an
 * animation <b>testable</b>: a test advances time by passing a larger number rather than by sleeping,
 * so a two-hundred-millisecond ease is asserted in microseconds and cannot be flaky on a loaded
 * machine. The clock itself is the caller's problem, and in this project it is
 * {@code ArmatureClient}'s.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>No spring, no physics, no velocity. A tween with a duration and a curve covers everything this
 * UI animates — a hover colour, a panel appearing, a scrollbar settling — and a spring needs a
 * timestep and a rest threshold, which is a great deal of machinery to make a button slightly
 * bouncier. {@link Easing#BACK_OUT} already gives the overshoot without any of it.
 *
 * <p>Not thread-safe, and not {@code volatile}: every field is read and written on the client thread,
 * because that is where a widget is drawn. Adding synchronisation would be a claim about contention
 * that cannot happen.
 */
public final class Tween {

    private final long durationMillis;
    private final Easing easing;

    private float from;
    private float current;
    private float target;
    private long startedAt;

    /** False once the value has arrived, so {@link #value} can stop doing arithmetic. */
    private boolean running;

    private Tween(float initial, long durationMillis, Easing easing) {
        if (durationMillis < 0) {
            throw new IllegalArgumentException("a duration cannot be negative: " + durationMillis);
        }
        this.durationMillis = durationMillis;
        this.easing = java.util.Objects.requireNonNull(easing, "easing");
        this.from = initial;
        this.current = initial;
        this.target = initial;
    }

    /**
     * A value that is already at rest.
     *
     * <p>The usual way to build one: a control is not animating most of the time, and its first
     * animation starts from wherever it happens to be.
     */
    public static Tween settled(float initial, long durationMillis, Easing easing) {
        return new Tween(initial, durationMillis, easing);
    }

    /** The same, with the default curve and duration. What a hover wants. */
    public static Tween settled(float initial) {
        return new Tween(initial, DEFAULT_MILLIS, DEFAULT_EASING);
    }

    /**
     * What a hover animates over.
     *
     * <p>140 ms, and the number is a judgement rather than a measurement. Under about 80 ms an ease is
     * not perceived as movement — it reads as a snap, so the work is pointless. Over about 250 ms it
     * is perceived as <i>slowness</i>, and a control that takes a fifth of a second to acknowledge the
     * cursor feels broken even though nothing is wrong. 140 ms sits in the band where the transition
     * is visible and the control still feels immediate.
     */
    public static final long DEFAULT_MILLIS = 140L;

    /** {@link Easing#QUAD_OUT}, the default for anything that responds to a person. See its javadoc. */
    public static final Easing DEFAULT_EASING = Easing.QUAD_OUT;

    // ------------------------------------------------------------------
    // Driving it
    // ------------------------------------------------------------------

    /**
     * Points the value at a new target, carrying on from where it currently is.
     *
     * <p>A no-op if it is already heading there — which matters more than it looks: a widget's draw
     * method runs sixty times a second and would otherwise restart the animation on every frame, so
     * the value would creep towards the target and never arrive, which looks like a stutter rather
     * than like a bug.
     */
    public void retarget(float newTarget, long nowMillis) {
        if (newTarget == target) {
            return;
        }
        this.from = current;
        this.target = newTarget;
        this.startedAt = nowMillis;
        this.running = durationMillis > 0 && from != newTarget;
        if (!running) {
            current = newTarget;
        }
    }

    /**
     * Jumps to a value with no animation.
     *
     * <p>For a state change that must not ease: a screen opening has no previous state to move from,
     * so an ease there animates from zero and looks like the control growing out of nothing.
     */
    public void snapTo(float value) {
        this.from = value;
        this.current = value;
        this.target = value;
        this.running = false;
    }

    /** The value at this instant. */
    public float value(long nowMillis) {
        if (!running) {
            return current;
        }

        long elapsed = nowMillis - startedAt;
        if (elapsed <= 0) {
            // A frame that arrives stamped before the retarget — which happens on the first frame
            // after a resize, when the tick and the render can disagree by a millisecond. Returning
            // `from` is the honest answer; letting a negative `t` through would ease backwards.
            return from;
        }
        if (elapsed >= durationMillis) {
            current = target;
            running = false;
            return target;
        }

        float t = (float) elapsed / (float) durationMillis;
        current = easing.between(from, target, t);
        return current;
    }

    /** Whether the value has arrived. */
    public boolean settled(long nowMillis) {
        return !running || nowMillis - startedAt >= durationMillis;
    }

    /** Where it is heading. */
    public float target() {
        return target;
    }

    /** The value it started this leg from, for a caller that needs to know. */
    public float from() {
        return from;
    }

    @Override
    public String toString() {
        return "Tween(" + from + " -> " + target + " over " + durationMillis + "ms"
                + (running ? ", running" : ", settled") + ")";
    }
}
