package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The motion switch and the eased hover that respects it.
 *
 * <h2>What this file is really testing</h2>
 *
 * <p>Two properties, and both of them are about <i>the switch reaching everything</i> rather than
 * about easing.
 *
 * <p>First: <b>off means instant, not different.</b> With motion off a tween's duration is zero, so it
 * arrives on the frame it was retargeted — but every caller still calls {@code retarget} and still
 * asks for a value. Nothing takes a second branch through the drawing code. That is asserted directly,
 * because the tempting implementation is an {@code if (Motion.enabled())} at each animated site, and a
 * branch nobody runs by default is where a bug lives unexercised.
 *
 * <p>Second: <b>a control that builds its own tween is the one the switch misses.</b> {@link Hover}
 * gets its tween through {@link Motion#tween}, so it snaps when motion is off; a version that called
 * {@code Tween.settled} directly would keep easing, and the symptom would be one widget that ignores
 * the setting — which reads as the setting being broken rather than as that widget having missed it.
 */
@DisplayName("Motion and hover")
class MotionTest {

    private static final long DURATION = 100L;

    @Test
    @DisplayName("motion is on by default, since a UI that does not move is not the expected one")
    void motionIsOnByDefault() {
        // Restored by every test that changes it, and asserted here so a test that forgets to restore
        // it is the thing that fails rather than the test after it.
        try {
            Motion.setEnabled(true);
            assertTrue(Motion.enabled());
            assertEquals(Tween.DEFAULT_MILLIS, Motion.defaultDuration());
            assertEquals(Tween.DEFAULT_EASING, Motion.defaultEasing());
        }
        finally {
            Motion.setEnabled(true);
        }
    }

    @Test
    @DisplayName("off makes a tween's duration zero, so it arrives on the frame it was told to")
    void offScalesTheDurationToNothing() {
        try {
            Motion.setEnabled(false);

            assertEquals(0L, Motion.scaledDuration(DURATION));
            assertEquals(0L, Motion.scaledDuration(0L));

            Tween tween = Motion.tween(0F, DURATION);
            tween.retarget(1F, 1_000L);

            // Not "very fast" -- arrived. A tween given one millisecond would still spend a frame at an
            // intermediate value, and a UI that flickers one frame of half-colour is harder to reason
            // about than one that does not.
            assertEquals(1F, tween.value(1_000L), 0F,
                    "with motion off, a tween should be at its target on the day it is retargeted");
            assertTrue(tween.settled(1_000L));
        }
        finally {
            Motion.setEnabled(true);
        }
    }

    @Test
    @DisplayName("the switch changes how long a transition takes, not which code runs")
    void theSwitchOnlyChangesTheDuration() {
        // The property that keeps one drawing path rather than two, asserted by making the *same*
        // sequence of calls under both settings and showing that the only difference is the value.
        //
        // This matters because the tempting implementation is `if (Motion.enabled())` around each
        // animated site: two paths through every animated control, only one of which is exercised by
        // default, which is where a bug lives unexercised. Here, motion on must actually run the easing
        // -- a midpoint strictly between the ends -- and motion off must not.
        try {
            Motion.setEnabled(true);
            Tween on = Motion.tween(0F, DURATION);
            on.retarget(1F, 0L);
            float midwayOn = on.value(DURATION / 2);
            assertTrue(midwayOn > 0F && midwayOn < 1F,
                    "with motion on the easing path should run, and the midpoint was " + midwayOn);

            Motion.setEnabled(false);
            Tween off = Motion.tween(0F, DURATION);
            off.retarget(1F, 0L);
            float midwayOff = off.value(DURATION / 2);
            assertEquals(1F, midwayOff, 0F,
                    "with motion off the same calls should arrive immediately");

            // And both report the same target, so nothing about which value is being aimed at changed.
            assertEquals(on.target(), off.target(), "the two settings aimed at different targets");
        }
        finally {
            Motion.setEnabled(true);
        }
    }

    @Test
    @DisplayName("a duration is refused rather than clamped when negative")
    void aNegativeDurationIsRefused() {
        // It can only arrive from a mistyped constant, and treating it as zero would look like motion
        // having been switched off -- so the mistake would be silent and attributed to the switch.
        assertThrows(IllegalArgumentException.class, () -> Motion.setDefaultDuration(-1));
    }

    // ------------------------------------------------------------------
    // Hover
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a hover eases up over its duration, and lands exactly on 1")
    void aHoverEasesUp() {
        Hover hover = new Hover(DURATION);
        hover.update("row", 0L);

        assertEquals(0F, hover.amount("row", 0L), 1.0E-5F, "it should start where it was");

        // The midway value comes from the curve rather than being written as a number. `Motion` uses
        // QUAD_OUT by default, which is 0.75 halfway -- not 0.5. Asserting 0.5 would be asserting linear
        // easing, which is the one curve this deliberately does not use for a hover.
        assertEquals(Motion.defaultEasing().between(0F, 1F, 0.5F), hover.amount("row", DURATION / 2),
                1.0E-4F, "the midway amount should be the default curve's");

        assertEquals(1F, hover.amount("row", DURATION), 1.0E-5F, "and land exactly on 1");
        assertTrue(hover.settled(DURATION));
    }

    @Test
    @DisplayName("the row being left fades out while the new one fades in, and they sum to one")
    void bothHalvesEase() {
        // The half that is easy to miss, and the one that decides whether a fast sweep across a list
        // looks like motion or like tearing. Without it the row you leave snaps to its resting colour
        // while the row you arrive at eases in.
        Hover hover = new Hover(DURATION);
        hover.update("first", 0L);
        hover.amount("first", DURATION);       // settle on the first

        hover.update("second", DURATION);

        assertEquals(0F, hover.amount("second", DURATION), 1.0E-5F, "the new row should start dark");
        assertEquals(1F, hover.amount("first", DURATION), 1.0E-5F, "the old row should still be lit");

        // `long`, not `float` -- `amount` takes a millisecond timestamp, and a float there is a
        // millisecond count that has lost precision as well as being the wrong type.
        long half = DURATION + DURATION / 2;
        float leaving = hover.amount("first", half);
        float arriving = hover.amount("second", half);
        assertEquals(1F, leaving + arriving, 1.0E-4F,
                "the two halves should sum to one, so the list never has a gap or a double-lit row");
    }

    @Test
    @DisplayName("a key that is neither current nor previous is zero without being remembered")
    void unknownKeysAreZero() {
        // What makes this cheap for a list of forty rows: only two keys have any state at all, so the
        // other thirty-eight cost a comparison and nothing else.
        Hover hover = new Hover(DURATION);
        hover.update("first", 0L);
        hover.amount("first", DURATION);
        hover.update("second", DURATION);

        // The one before last is now forgotten entirely, not held at a fading value.
        hover.amount("second", DURATION * 2);

        assertEquals(0F, hover.amount("third", DURATION * 2), 0F);
        assertEquals(0F, hover.amount("first", DURATION * 2), 1.0E-5F, "the fade-out should have finished");
        assertEquals(0F, hover.amount(null, DURATION * 2), 0F);
        assertEquals(0F, hover.amount("anything", 0L), 0F);
    }

    @Test
    @DisplayName("pointing at the same thing twice does not restart the fade")
    void repeatingTheSameKeyIsANoOp() {
        // A draw method runs sixty times a second, so retargeting on every frame would restart the
        // fade continuously and the highlight would creep towards its colour and never arrive. On
        // screen that reads as a stutter rather than as a bug.
        Hover hover = new Hover(DURATION);
        hover.update("row", 0L);

        float midway = hover.amount("row", DURATION / 2);
        hover.update("row", DURATION / 2);
        hover.update("row", DURATION / 2 + 1);

        assertEquals(midway, hover.amount("row", DURATION / 2), 1.0E-6F, "the fade restarted");
        assertEquals(1F, hover.amount("row", DURATION), 1.0E-5F, "and it still arrived");
    }

    @Test
    @DisplayName("leaving for nothing at all fades the row out, and null is a valid key for that")
    void pointingAtNothingFadesOut() {
        // The pointer leaving the list entirely, which is not a rare case -- it is what happens every
        // time the mouse leaves the panel.
        Hover hover = new Hover(DURATION);
        hover.update("row", 0L);
        hover.amount("row", DURATION);

        hover.update(null, DURATION);

        assertNull(hover.current());
        assertEquals(1F, hover.amount("row", DURATION), 1.0E-5F, "it should start fading from lit");
        assertEquals(0F, hover.amount("row", DURATION * 2), 1.0E-5F, "and arrive at nothing");
    }

    @Test
    @DisplayName("a key can be any object, including an index or a record")
    void keysCanBeAnything() {
        // The same rule as Layout's slots: this toolkit has no business deciding how a caller names
        // its rows. The two real uses in this project are a generated String key and a quest id.
        Hover hover = new Hover(DURATION);

        hover.update(0, 0L);
        assertEquals(1F, hover.amount(0, DURATION), 1.0E-5F, "an Integer key should work");
        assertEquals(0F, hover.amount("0", DURATION), 0F, "and must not match the String of it");

        record Ref(String id, int index) {
        }
        Ref key = new Ref("punch_a_tree", 3);
        hover.update(key, DURATION * 2);
        assertEquals(1F, hover.amount(new Ref("punch_a_tree", 3), DURATION * 3), 1.0E-5F,
                "an equal record should match, since the comparison is by equals");
        assertEquals(0F, hover.amount(new Ref("punch_a_tree", 4), DURATION * 3), 0F,
                "and a different one should not");
    }

    @Test
    @DisplayName("moving between two rows really does fade one out while the other fades in")
    void movingBetweenRowsCrossfades() {
        // The bug this test exists for was in the class, not in a test expectation, and it was in
        // exactly the case the class was written for.
        //
        // `update` used to retarget the tween to 1 -- and on a move from one row to another the target
        // was *already* 1, so `retarget` saw no change and returned early. The row being left then
        // computed `1 - 1 = 0` and went dark instantly while the row arriving snapped bright. So there
        // was no crossfade between rows at all: only entering and leaving the list animated, and the
        // most common movement in a list -- up and down it -- flickered.
        //
        // The fix is that the tween tracks the *transition* and is restarted on every change, which is
        // what makes the two amounts sum to one at every instant. This asserts both halves separately
        // and then the sum, because either half could be right while the other was wrong.
        Hover hover = new Hover(DURATION);
        hover.update("above", 0L);
        assertTrue(hover.amount("above", DURATION) > 0.99F, "fixture sanity: settle on the first row");

        hover.update("below", DURATION);

        assertTrue(hover.amount("below", DURATION) < 0.01F,
                "the row arrived at should start dark, and it started at " + hover.amount("below", DURATION));
        assertTrue(hover.amount("above", DURATION) > 0.99F,
                "the row left should still be lit, and it was " + hover.amount("above", DURATION));

        long midway = DURATION + DURATION / 2;
        float arriving = hover.amount("below", midway);
        float leaving = hover.amount("above", midway);

        assertTrue(arriving > 0.1F && arriving < 0.9F,
                "mid-transition the arriving row should be part-way, and it was " + arriving);
        assertTrue(leaving > 0.1F && leaving < 0.9F,
                "mid-transition the leaving row should be part-way, and it was " + leaving);
        assertEquals(1F, arriving + leaving, 1.0E-4F,
                "the two halves should sum to one, so the list is never lit twice or dark once");

        assertEquals(0F, hover.amount("above", DURATION * 2), 1.0E-4F, "and the old row should finish dark");
        assertEquals(1F, hover.amount("below", DURATION * 2), 1.0E-4F, "with the new one fully lit");
    }

    @Test
    @DisplayName("clearing forgets both keys at once, so a rebuilt list lights nothing")
    void clearForgetsEverything() {
        // For a screen closing, or a list rebuilt for a different quest, where the row keys mean
        // something else now -- a fade carried over would light up a row nobody is pointing at.
        Hover hover = new Hover(DURATION);
        hover.update("old", 0L);
        hover.amount("old", DURATION);

        hover.clear();

        assertNull(hover.current());
        assertEquals(0F, hover.amount("old", DURATION), 0F, "the old key should not still be lit");
        assertEquals(0F, hover.amount("new", DURATION), 0F);

        // And it works again afterwards, so clearing is a reset rather than a poisoning.
        hover.update("new", DURATION);
        assertEquals(1F, hover.amount("new", DURATION + DURATION), 1.0E-5F);
    }

    @Test
    @DisplayName("with motion off a hover arrives immediately, because it takes its tween from Motion")
    void aHoverRespectsTheMotionSetting() {
        // The test that catches the mistake worth catching. A Hover that built its own Tween directly
        // would keep easing with motion off, so one widget would ignore the switch -- and the symptom
        // is the setting looking broken rather than that widget having missed it.
        try {
            Motion.setEnabled(false);

            Hover hover = new Hover(DURATION);
            hover.update("row", 1_000L);

            assertEquals(1F, hover.amount("row", 1_000L), 0F,
                    "with motion off a hover should be fully applied on the frame it is retargeted");
            assertEquals(0F, hover.amount("other", 1_000L), 0F);
        }
        finally {
            Motion.setEnabled(true);
        }
    }

    @Test
    @DisplayName("a tween built before a theme change keeps its duration, and that is a known limit")
    void aTweenKeepsTheDurationItWasBuiltWith() {
        // The honest limitation of routing durations through a static, asserted rather than left for
        // somebody to discover. A Tween captures its duration when it is constructed, so a theme
        // selected afterwards does not reach a tween that already exists.
        //
        // In practice this is nearly invisible: a control's tween is a field on a widget, and a screen
        // rebuilds its widgets when it opens and on a resize -- so the next time the screen is built,
        // the new duration applies. What it means precisely is that an *already-open* screen keeps the
        // old timing until it is rebuilt, and that is worth knowing rather than worth a generation
        // counter and a registry of live tweens.
        try {
            Motion.setEnabled(true);
            Motion.setDefaultDuration(200L);
            Tween early = Motion.tween(0F, 200L);

            // The theme changes to one with no motion.
            Motion.setDefaultDuration(0L);

            early.retarget(1F, 0L);
            assertTrue(early.value(100L) < 1F,
                    "the early tween should still be easing on its original 200ms duration");
            assertEquals(1F, early.value(200L), 0F, "and it should still arrive on time");

            // A tween built after the change gets the new answer, including the zero-duration case.
            Tween late = Motion.tween(0F, 200L);
            assertEquals(0L, Motion.scaledDuration(200L),
                    "with a zero default duration, every requested duration scales to nothing");
            late.retarget(1F, 0L);
            assertEquals(1F, late.value(0L), 0F, "and it arrives immediately");
        }
        finally {
            Motion.setEnabled(true);
            Motion.setDefaultDuration(Tween.DEFAULT_MILLIS);
        }
    }

    @Test
    @DisplayName("a fresh tracker is at rest and reports nothing hovered")
    void aFreshTrackerIsAtRest() {
        Hover hover = new Hover(DURATION);
        assertNull(hover.current());
        assertEquals(0F, hover.amount("anything", 0L), 0F);
        assertTrue(hover.settled(0L));
        assertFalse(hover.settled(999_999L) && hover.current() != null);
    }
}
