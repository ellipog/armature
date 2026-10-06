package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparison a screen makes every frame, asserted without a screen.
 *
 * <h2>The two failures this file is about</h2>
 *
 * <p>A comparison like this goes wrong in exactly two directions, and both look like a working panel
 * from the outside. It can <b>miss</b> a change, which is the staleness it exists to remove; or it can
 * <b>never settle</b>, which is a screen that rebuilds on every frame — a flicker, and a redraw loop
 * that nothing in the game reports. So the cases below are one for each direction, and the settling
 * ones are as load-bearing as the detecting ones.
 */
class WatchTest {

    private static Map<String, Long> of(String name, long value) {
        Map<String, Long> one = new LinkedHashMap<>();
        one.put(name, value);
        return one;
    }

    @Test
    void nothingIsMovedWhenNothingChanged() {
        Watch watch = new Watch();
        watch.moved(of("party", 1));

        assertTrue(watch.moved(of("party", 1)).isEmpty(),
                "the same value twice is not a change, and reporting it would be a rebuild per frame");
    }

    @Test
    void aMovedValueIsReportedOnceAndThenSettles() {
        Watch watch = new Watch();
        watch.settle(of("party", 1));

        assertEquals(java.util.List.of("party"), watch.moved(of("party", 2)),
                "a counter that moved is the whole reason the screen rebuilds");
        assertTrue(watch.moved(of("party", 2)).isEmpty(),
                "and looking is what takes the new value as the baseline -- otherwise the same change "
                        + "would be reported on every frame for as long as the panel stayed open");
    }

    @Test
    void aNameThatWasNotThereBeforeCountsAsMoved() {
        Watch watch = new Watch();
        watch.settle(of("party", 1));

        assertEquals(java.util.List.of("tree"), watch.moved(of("tree", 7L)),
                "a source registered while the client is running has to reach the screens that are "
                        + "already open, rather than being ignored because nobody had heard of it");
    }

    @Test
    void settlingReportsNothing() {
        Watch watch = new Watch();
        watch.settle(of("party", 5));

        assertTrue(watch.moved(of("party", 5)).isEmpty());
        assertFalse(watch.moved(of("party", 5)).contains("party"),
                "a screen that has just built from these numbers holds what is current, and the frame "
                        + "after a rebuild must not find everything moved");
    }

    @Test
    void everyMovedNameIsReportedRatherThanTheFirst() {
        Watch watch = new Watch();
        Map<String, Long> before = new LinkedHashMap<>();
        before.put("party", 1L);
        before.put("tree", 1L);
        before.put("progress", 1L);
        watch.settle(before);

        Map<String, Long> after = new LinkedHashMap<>();
        after.put("party", 2L);
        after.put("tree", 1L);
        after.put("progress", 9L);

        assertEquals(java.util.List.of("party", "progress"), watch.moved(after),
                "which sources moved is what a log line has to say when a screen starts rebuilding too "
                        + "often, so the list is all of them and in the order they were registered");
    }

    @Test
    void theEmptyAnswerIsSharedRatherThanAllocated() {
        // The frame's common case is a screen looking at data that has not moved, and it must not allocate
        // to say so. `ArmatureLive.revisions()` already refuses to build a fresh map when nothing has
        // changed; this is the other half — the comparison's own answer.
        //
        // Identity is the assertion because the answer is empty either way: `List.of()` and a fresh empty
        // `ArrayList` are `equals` and are not the same object, so only `assertSame` can tell a caller that
        // reused one from a caller that built one.
        Watch watch = new Watch();
        watch.settle(of("party", 1));

        assertSame(List.of(), watch.moved(of("party", 1)),
                "nothing moved, and nothing was allocated to say it");
        assertSame(List.of(), watch.moved(of("party", 1)),
                "and the same on the next frame");
    }

    @Test
    void aCallerMayKeepItsOwnMapAndChangeItLater() {
        // `settle` copies, deliberately: it runs on a rebuild rather than every frame, so the copy costs
        // nothing measurable and it keeps the class honest for a caller that hands in a map it goes on to
        // change. This is the property that would break if the copy were dropped for the sake of one
        // allocation on a path that runs a handful of times a session.
        Watch watch = new Watch();
        Map<String, Long> mutable = new LinkedHashMap<>();
        mutable.put("party", 1L);
        watch.settle(mutable);

        mutable.put("party", 99L);

        assertTrue(watch.moved(of("party", 1)).isEmpty(),
                "the baseline is what was handed in, not whatever the caller did to it afterwards");
        assertEquals(List.of("party"), watch.moved(of("party", 99)),
                "and a genuinely moved value is still reported");
    }
}
