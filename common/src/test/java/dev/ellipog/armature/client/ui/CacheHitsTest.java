package dev.ellipog.armature.client.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache hit/miss counter: whether the toolkit's memos are being consulted or merely built.
 *
 * <h2>What this asserts, and what it deliberately does not</h2>
 *
 * <p>It asserts that a probe is recorded whichever way it answered, that two caches are kept apart, that a
 * drain consumes the window, and that nothing is recorded while the counter is off. Those are the properties
 * the ratio's honesty rests on: a counter that recorded only the hits would read as a perfect cache, which is
 * the exact failure this instrument exists to make impossible.
 *
 * <p>It does <b>not</b> assert that any particular cache hits at any particular rate. That is a claim about a
 * client drawing real content, and the honest place for it is the run this instrument makes possible — a
 * person with the counter on, reading the line. A test that asserted a hit rate would be asserting the
 * fixture it built.
 *
 * <h2>Why the static state is put back</h2>
 *
 * <p>The counter is static, like every instrument here, and the suite runs in one JVM — so a test that left
 * it on would have the next test's probes counted into its own tally, and the failure would look like a wrong
 * count rather than like a leaked switch. Every test therefore puts both back, in a `finally`.
 */
@DisplayName("The cache hit/miss counter")
class CacheHitsTest {

    private static void off() {
        CacheHits.set(false);
        CacheHits.reset();
    }

    @Test
    @DisplayName("nothing is recorded while the counter is off")
    void offRecordsNothing() {
        off();
        try {
            CacheHits.asked("plans", true);
            CacheHits.asked("plans", false);

            assertFalse(CacheHits.on(), "the switch is off");
            assertTrue(CacheHits.tally().isEmpty(),
                    "a client with the counter off must not pay for the instrument, which is the property "
                            + "that lets it sit on the frame path at all");
        }
        finally {
            off();
        }
    }

    @Test
    @DisplayName("a miss is recorded as well as a hit, which is the whole point")
    void bothAnswersAreRecorded() {
        off();
        CacheHits.set(true);
        try {
            CacheHits.asked("plans", true);
            CacheHits.asked("plans", true);
            CacheHits.asked("plans", false);

            int[] counts = CacheHits.tally().get("plans");
            assertEquals(2, counts[0], "two probes were answered from the cache");
            assertEquals(1, counts[1],
                    "and one was not -- a counter that recorded only the hits would read as a perfect cache, "
                            + "which is the failure this instrument exists to make impossible");
        }
        finally {
            off();
        }
    }

    @Test
    @DisplayName("two caches are kept apart, and the line names both")
    void cachesAreKeptApart() {
        off();
        CacheHits.set(true);
        try {
            CacheHits.asked("plans", true);
            CacheHits.asked("spans", false);
            CacheHits.asked("spans", false);
            CacheHits.asked("widths", true);

            Map<String, int[]> tally = CacheHits.tally();
            assertEquals(2, tally.get("spans")[1], "the spans cache missed twice");
            assertEquals(0, tally.get("spans")[0], "and hit never");
            assertEquals(1, tally.get("plans")[0], "while the plan cache hit");
            assertEquals(0, tally.get("plans")[1], "and missed never");

            String line = CacheHits.describe(tally);
            assertTrue(line.contains("plans 1/0 (100%)"), "the line carries hits, misses and the rate: " + line);
            assertTrue(line.contains("spans 0/2 (0%)"), "including a cache that is missing every time: " + line);
            assertTrue(line.contains("widths 1/0 (100%)"), "and every cache that was asked: " + line);
        }
        finally {
            off();
        }
    }

    @Test
    @DisplayName("a drain consumes the window, and the next window starts empty")
    void drainConsumesTheWindow() {
        off();
        CacheHits.set(true);
        try {
            CacheHits.asked("plans", true);
            Map<String, int[]> first = CacheHits.drain();
            assertEquals(1, first.get("plans")[0], "the drain reports what the window held");

            assertTrue(CacheHits.tally().isEmpty(),
                    "and the window is what a report consumes -- a cumulative total could not answer "
                            + "'is this hitting now', which is the only form a frame's cache can be asked");

            CacheHits.asked("plans", false);
            assertEquals(1, CacheHits.tally().get("plans")[1], "the next window counts on its own");
        }
        finally {
            off();
        }
    }

    @Test
    @DisplayName("an empty tally describes as nothing, so a caller can choose not to log")
    void anEmptyTallyIsNoLine() {
        off();
        CacheHits.set(true);
        try {
            assertEquals("", CacheHits.describe(CacheHits.drain()),
                    "a second in which nothing was asked produces no line rather than a blank one, which "
                            + "would read as a measurement of zero rather than as an absence");
        }
        finally {
            off();
        }
    }

    @Test
    @DisplayName("a cache that was never asked is absent rather than present at zero")
    void anUnaskedCacheIsAbsent() {
        off();
        CacheHits.set(true);
        try {
            CacheHits.asked("plans", true);

            Map<String, int[]> tally = CacheHits.tally();
            assertTrue(tally.containsKey("plans"), "the cache that was asked is there");
            assertFalse(tally.containsKey("spans"),
                    "and one that was not is absent, so a line cannot suggest it was measured and found idle");
        }
        finally {
            off();
        }
    }
}
