package dev.ellipog.armature.client.ui.kit;

/**
 * The shape of an animation over time: a curve from 0 to 1.
 *
 * <h2>Why this is an enum of named curves and not a lambda</h2>
 *
 * <p>Because a curve is a thing you choose, not a thing you write. Every animation in this UI wants
 * one of about seven shapes — "steady", "slow then fast", "fast then slow", "ease both ends",
 * "overshoot a little and come back" — and each of those has a name a person already knows. A
 * {@code Function<Float, Float>} parameter would be more general and would mean every call site
 * carries a few lines of maths that nobody can compare against the call site next to it, and that a
 * theme (R6) cannot name.
 *
 * <p>Naming them also makes them <b>substitutable from data</b>: {@code Theme} will want to say
 * "this UI eases its hovers with CUBIC_OUT", and that is a field rather than code.
 *
 * <h2>Every curve here is a fixed function with two fixed endpoints</h2>
 *
 * <p>{@code ease(0) == 0} and {@code ease(1) == 1} for all of them, including the overshooting one —
 * which is what makes any of them safe to use for a value that must arrive exactly where it was sent.
 * The overshoot happens in the middle, and {@link #BACK_OUT}'s is the only one that leaves the range
 * at all, which is why its javadoc says what it is for and what it is not.
 *
 * <h2>Everything is clamped on the way in</h2>
 *
 * <p>Not defensively — because a caller's {@code t} is derived from wall-clock time, and a frame
 * that arrives late (a window dragged, a debugger paused, an alt-tab) produces a {@code t} past 1.
 * Un-clamped, {@code QUAD_IN} at {@code t = 1.5} returns 2.25 and a control animates to more than
 * twice its own size for one frame. The clamp is here rather than at every call site because there
 * is no call site that wants the un-clamped answer.
 */
public enum Easing {

    /** No easing. Movement at a constant rate, which reads as mechanical. */
    LINEAR,

    /** Starts still and accelerates. For something leaving. */
    QUAD_IN,

    /**
     * Starts fast and settles. <b>The default for anything that appears or responds</b>, and the
     * reason is that a UI is mostly reacting to a person: an interface that accelerates into its
     * answer feels like it was waiting for you.
     */
    QUAD_OUT,

    /** Eases both ends, so it starts and stops gently. For movement across the screen. */
    QUAD_IN_OUT,

    /** A sharper {@link #QUAD_OUT}. For small distances, where a gentle curve is imperceptible. */
    CUBIC_OUT,

    /** Symmetric and smooth, with a gentler start than {@link #QUAD_IN_OUT}. */
    SINE_IN_OUT,

    /**
     * Overshoots past the target and comes back. For a control that has been given something, so the
     * arrival is the point.
     *
     * <p><b>This is the one curve that leaves 0..1</b> — it peaks around 1.1 — and that has two
     * consequences worth knowing rather than discovering. It is right for a position, a size or a
     * scale, where 10% too big for two frames reads as a bounce. It is <b>wrong for a colour</b>:
     * {@link Colour#lerp} clamps its {@code t}, so the overshoot is flattened rather than producing a
     * channel that wraps past white into black, and the animation simply loses its bounce at the end.
     * Nothing breaks; it just does not do anything.
     */
    BACK_OUT;

    /** How far {@link #BACK_OUT} overshoots. The constant every implementation of this curve uses. */
    private static final float BACK_OVERSHOOT = 1.70158F;

    /**
     * The curve applied to a linear {@code t}.
     *
     * @param t linear progress; anything outside 0..1 is clamped rather than extrapolated
     * @return the eased progress. Exactly 0 at 0 and exactly 1 at 1, for every curve except
     *     {@link #BACK_OUT}, which passes 1 in the middle and returns to it.
     */
    public float ease(float t) {
        float clamped = t < 0F ? 0F : (t > 1F ? 1F : t);

        return switch (this) {
            case LINEAR -> clamped;
            case QUAD_IN -> clamped * clamped;
            case QUAD_OUT -> 1F - (1F - clamped) * (1F - clamped);
            case QUAD_IN_OUT -> clamped < 0.5F
                    ? 2F * clamped * clamped
                    : 1F - (float) Math.pow(-2F * clamped + 2F, 2) / 2F;
            case CUBIC_OUT -> 1F - (float) Math.pow(1F - clamped, 3);
            case SINE_IN_OUT -> -(float) (Math.cos(Math.PI * clamped) - 1) / 2F;
            case BACK_OUT -> {
                float c3 = BACK_OVERSHOOT + 1F;
                float shifted = clamped - 1F;
                yield 1F + c3 * shifted * shifted * shifted + BACK_OVERSHOOT * shifted * shifted;
            }
        };
    }

    /**
     * The same curve, interpolating between two values.
     *
     * <p>A convenience, and it is here rather than at the call site because it is where the <b>order
     * of operations</b> matters and is easy to get wrong: ease first, then interpolate — {@code from
     * + (to - from) * ease(t)} — rather than interpolating and then easing the result. Writing
     * {@code ease(from + (to - from) * t)} looks equivalent and is not: it drags the endpoints
     * through the curve, so a hover from 0.0 to 1.0 with {@code QUAD_OUT} would start at
     * {@code ease(0) = 0} (fine) and end at {@code ease(1) = 1} (also fine) but every value between
     * would be wrong, and for {@code BACK_OUT} the *destination* would overshoot.
     */
    public float between(float from, float to, float t) {
        return from + (to - from) * ease(t);
    }
}
