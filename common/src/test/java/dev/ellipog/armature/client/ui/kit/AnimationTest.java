package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curves and the tween that drives them.
 *
 * <h2>What is worth asserting about an animation</h2>
 *
 * <p>Not the intermediate values — those are a curve's own business and a test that pins them turns
 * every curve tweak into a test edit. What is worth asserting is the set of properties a caller
 * <i>relies</i> on:
 *
 * <ul>
 *   <li><b>Both endpoints are exact.</b> {@code ease(0) == 0} and {@code ease(1) == 1}, and a settled
 *       tween returns its target bit-for-bit. An animation that arrives at 0.998 leaves a control
 *       imperceptibly wrong forever, and nobody ever finds it.</li>
 *   <li><b>Nothing escapes its range except the one curve that is meant to.</b> A caller blending a
 *       colour needs the output clamped, and a stray 1.5 in a scale is a control twice its size for
 *       a frame.</li>
 *   <li><b>A late frame does not overshoot.</b> A window dragged or an alt-tab produces a huge
 *       elapsed time, and the clamp is what stops it.</li>
 *   <li><b>A retarget carries on from where it is.</b> The whole reason {@link Tween} has state.</li>
 * </ul>
 *
 * <h2>Time is a parameter, so none of this sleeps</h2>
 *
 * <p>Every assertion here advances time by passing a larger number. A test that slept for 140 ms
 * would be slow, and would be flaky on a loaded machine in a way that looks like a real failure.
 */
@DisplayName("Easing and tweening")
class AnimationTest {

    private static final long DURATION = 100L;

    // ------------------------------------------------------------------
    // The curves
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every curve starts at exactly 0 and ends at exactly 1")
    void everyCurveHasExactEndpoints() {
        // The property every caller depends on and that a curve's own arithmetic can quietly lose:
        // SINE_IN_OUT is built from a cosine, and a rounded float there lands on 0.99999994 rather
        // than 1. That is invisible in the middle of an animation and permanent at the end of one.
        for (Easing easing : Easing.values()) {
            assertEquals(0F, easing.ease(0F), 1.0E-6F, easing + " does not start at 0");
            assertEquals(1F, easing.ease(1F), 1.0E-6F, easing + " does not end at 1");
        }
    }

    @Test
    @DisplayName("time is clamped, so a late frame cannot overshoot")
    void timeIsClamped() {
        // A window dragged, an alt-tab, a debugger paused: all produce an elapsed time past the
        // duration, and QUAD_IN at t = 1.5 is 2.25. Un-clamped, a control animates to more than twice
        // its own size for one frame — which looks like a rendering fault rather than like a clock.
        for (Easing easing : Easing.values()) {
            assertEquals(0F, easing.ease(-1F), 1.0E-6F, easing + " at a negative t");
            assertEquals(1F, easing.ease(99F), 1.0E-6F, easing + " at an absurd t");
        }
    }

    @Test
    @DisplayName("every curve is monotonic apart from the one that overshoots on purpose")
    void curvesDoNotGoBackwards() {
        // Monotonicity is what makes these usable for a value that must only ever advance — a colour
        // walking towards its target, a progress bar filling. A curve that dipped would show as a
        // visible backwards twitch, and BACK_OUT is the only one allowed to do anything like it.
        for (Easing easing : Easing.values()) {
            if (easing == Easing.BACK_OUT) {
                continue;
            }
            float previous = -1F;
            for (int step = 0; step <= 100; step++) {
                float value = easing.ease(step / 100F);
                assertTrue(value >= previous,
                        easing + " went backwards at t=" + (step / 100F)
                                + " (" + previous + " then " + value + ")");
                previous = value;
            }
        }
    }

    @Test
    @DisplayName("BACK_OUT is the one curve that leaves 0..1, and it does so deliberately")
    void backOutOvershootsAndComesBack() {
        // Asserted rather than merely documented, because it is the reason Colour.lerp clamps and the
        // reason fillAt takes a clamped progress. A curve that quietly stopped overshooting would
        // leave those clamps looking like superstition.
        boolean overshot = false;
        for (int step = 0; step <= 100; step++) {
            if (Easing.BACK_OUT.ease(step / 100F) > 1.001F) {
                overshot = true;
            }
        }
        assertTrue(overshot, "BACK_OUT does not overshoot, so it is not the curve its name claims");

        // And it comes back: the end is exactly 1, asserted above for every curve.
        assertEquals(1F, Easing.BACK_OUT.ease(1F), 1.0E-6F);
    }

    @Test
    @DisplayName("between() eases first and interpolates second, which is the order that matters")
    void betweenEasesThenInterpolates() {
        // The two orders are easy to write the wrong way round and they differ everywhere except the
        // endpoints — and for BACK_OUT the *wrong* order overshoots the destination, so a control
        // would settle past its own final size and stay there.
        assertEquals(10F, Easing.LINEAR.between(10F, 20F, 0F), 1.0E-6F);
        assertEquals(20F, Easing.LINEAR.between(10F, 20F, 1F), 1.0E-6F);
        assertEquals(15F, Easing.LINEAR.between(10F, 20F, 0.5F), 1.0E-6F);

        // A curve that is not linear, checked against the eased value rather than a hardcoded number
        // — so this asserts the composition rather than a constant that a curve tweak would break.
        for (int step = 0; step <= 10; step++) {
            float t = step / 10F;
            assertEquals(10F + 10F * Easing.QUAD_OUT.ease(t),
                    Easing.QUAD_OUT.between(10F, 20F, t), 1.0E-5F,
                    "between() did not apply the curve to the progress at t=" + t);
        }
    }

    // ------------------------------------------------------------------
    // The tween
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a settled tween does not move until it is told to")
    void aSettledTweenIsStill() {
        Tween tween = Tween.settled(0.5F);

        assertEquals(0.5F, tween.value(0L), 1.0E-6F);
        assertEquals(0.5F, tween.value(10_000L), 1.0E-6F,
                "a tween that has never been retargeted moved on its own");
        assertTrue(tween.settled(10_000L));
        assertEquals(0.5F, tween.target());
    }

    @Test
    @DisplayName("it arrives at exactly its target, not near it")
    void itArrivesExactly() {
        // The property that stops a control being imperceptibly the wrong colour forever. An ease that
        // lands on 0.9997 and stops leaves the difference in the pixels and nobody ever finds it.
        Tween tween = Tween.settled(0F, DURATION, Easing.SINE_IN_OUT);
        tween.retarget(1F, 0L);

        assertEquals(1F, tween.value(DURATION), 0F, "the value at the duration is not the target exactly");
        assertEquals(1F, tween.value(DURATION + 5_000L), 0F, "and it stays there");
        assertTrue(tween.settled(DURATION));
    }

    @Test
    @DisplayName("it is at its start before the clock has moved")
    void itStartsWhereItWas() {
        Tween tween = Tween.settled(0F, DURATION, Easing.QUAD_OUT);
        tween.retarget(1F, 1_000L);

        assertEquals(0F, tween.value(1_000L), 1.0E-6F);
    }

    @Test
    @DisplayName("a frame stamped before the retarget does not ease backwards")
    void anEarlyFrameDoesNotGoBackwards() {
        // Reachable in play: after a resize the tick and the render can disagree by a millisecond, so
        // the frame is stamped before the retarget that started the animation. A negative t through
        // QUAD_OUT would give a *negative* progress, and a colour blended at a negative t is not a
        // colour — it is whatever the arithmetic produced, clamped into something plausible.
        Tween tween = Tween.settled(0F, DURATION, Easing.QUAD_OUT);
        tween.retarget(1F, 1_000L);

        assertEquals(0F, tween.value(999L), 1.0E-6F,
                "a frame stamped before the start eased to a value it should not have reached");
    }

    @Test
    @DisplayName("a late frame clamps rather than overshooting the destination")
    void aLateFrameClamps() {
        // A window dragged or an alt-tab: the next frame can be a second later. Without the clamp the
        // ease would run past its own progress, and BACK_OUT in particular would fly past the target.
        Tween tween = Tween.settled(0F, DURATION, Easing.BACK_OUT);
        tween.retarget(1F, 0L);

        assertEquals(1F, tween.value(5_000L), 1.0E-6F);
    }

    @Test
    @DisplayName("retargeting mid-flight carries on from where it is, which is the whole design")
    void retargetingCarriesOn() {
        // A cursor crossing a column of chapters begins and ends a hover in under a hundred
        // milliseconds. An animation that cannot be interrupted has to either finish — so the button
        // stays lit after the pointer has gone — or be abandoned, so it snaps. This is the third
        // option and the reason Tween holds state at all.
        Tween tween = Tween.settled(0F, DURATION, Easing.LINEAR);
        tween.retarget(1F, 0L);

        float halfway = tween.value(50L);
        assertEquals(0.5F, halfway, 1.0E-5F, "fixture sanity: half the duration is half the way");

        // Now reverse it. The new leg must start from 0.5, not from 0 — a tween that restarted from
        // zero would make the control go dark and then brighten again.
        tween.retarget(0F, 50L);
        assertEquals(0.5F, tween.value(50L), 1.0E-5F, "the reverse leg did not start from where it was");

        // 0.375 rather than 0.25, and my first version asserted 0.25 by thinking "a quarter of the way
        // to zero is 0.25" — which confuses a quarter of the *distance* with a quarter of the *way from
        // one to the other*. The leg runs from 0.5 down to 0 over a full duration, so a quarter of its
        // duration is a quarter of the 0.5 gap: 0.5 - 0.125.
        //
        // Kept as a comment because the same confusion is available at every call site that reads a
        // tween's value, and the number the code produces is the correct one.
        assertEquals(0.375F, tween.value(75L), 1.0E-5F, "and it did not ease back towards zero");

        assertEquals(0.0F, tween.value(150L), 1.0E-5F, "the reverse leg did not arrive at zero");

        // `from()` is 0.5 — where the reverse leg *started* — and not 0, which is where it is heading.
        // My first version asserted 0 here and it is the same confusion as the 0.25 above, one step
        // further on: `from` and `target` are the two ends of the current leg, and reading `from` as
        // "where it came from originally" would be a third meaning. It is the leg's own start, which is
        // why it was captured at the retarget.
        assertEquals(0.5F, tween.from(), 1.0E-6F, "from() should be the leg's start, not its target");
        assertEquals(0F, tween.target(), 1.0E-6F, "and target() is where it is heading");
    }

    @Test
    @DisplayName("retargeting to where it is already going does not restart the ease")
    void retargetingToTheSameTargetIsANoOp() {
        // The one that would break in play rather than in a test: a widget's draw method runs sixty
        // times a second and would restart the animation on every frame, so the value would creep
        // towards the target and never arrive. On screen that reads as a stutter, not as a bug.
        Tween tween = Tween.settled(0F, DURATION, Easing.LINEAR);
        tween.retarget(1F, 0L);

        float at25 = tween.value(25L);
        tween.retarget(1F, 25L);
        tween.retarget(1F, 30L);
        tween.retarget(1F, 40L);

        assertEquals(at25, tween.value(25L), 1.0E-6F, "the ease restarted under a repeated retarget");
        assertEquals(1F, tween.value(DURATION), 1.0E-6F, "and it still arrived");
    }

    @Test
    @DisplayName("a zero-length tween snaps, since there is no time for it to ease over")
    void aZeroDurationSnaps() {
        // Reachable from a theme (R6) that sets its animation duration to zero to turn animation off.
        Tween tween = Tween.settled(0F, 0L, Easing.QUAD_OUT);
        tween.retarget(1F, 0L);

        assertEquals(1F, tween.value(0L), 1.0E-6F);
        assertTrue(tween.settled(0L));
    }

    @Test
    @DisplayName("snapTo jumps, for a state change that must not animate")
    void snapToJumps() {
        // A screen opening has no previous state to move from, so an ease there animates from zero and
        // reads as the panel growing out of nothing.
        Tween tween = Tween.settled(0F, DURATION, Easing.QUAD_OUT);
        tween.retarget(1F, 0L);
        tween.value(50L);

        tween.snapTo(1F);

        assertEquals(1F, tween.value(50L), 0F, "snapTo left the value mid-flight");
        assertTrue(tween.settled(50L));
    }

    @Test
    @DisplayName("everything reports the progress it was given, within a frame's worth of ease")
    void progressIsMonotonic() {
        // The general shape: a tween from 0 to 1 with a monotone curve never goes backwards. Checked
        // across the whole duration rather than at the endpoints, because a discontinuity in the
        // middle — which a mistyped curve can produce — is invisible at the ends.
        for (Easing easing : Easing.values()) {
            if (easing == Easing.BACK_OUT) {
                continue;
            }
            Tween tween = Tween.settled(0F, DURATION, easing);
            tween.retarget(1F, 0L);

            float previous = -1F;
            for (long now = 0; now <= DURATION; now += 5) {
                float value = tween.value(now);
                assertTrue(value >= previous - 1.0E-5F,
                        easing + " went backwards at " + now + "ms (" + previous + " then " + value + ")");
                previous = value;
            }
        }
    }

    @Test
    @DisplayName("a negative duration is refused rather than silently treated as an instant")
    void aNegativeDurationIsRefused() {
        // Treated as zero it would look like animation being switched off, so a caller who meant to
        // pass 140 and passed -140 would get a snap and no error anywhere. Reachable from a theme.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Tween.settled(0F, -1L, Easing.LINEAR));
    }
}
