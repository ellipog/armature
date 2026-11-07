package dev.ellipog.armature.neoforge;

import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A break {@code mayBreak} posts to ask claim mods is a question, not a break, so it must never
 * become a BLOCK_BROKEN.
 *
 * <p>What this holds, without a world: the probe is posted on the real bus, the forwarder's own
 * test ({@code isRealBreak}) refuses it for exactly the length of its post, and nothing else is
 * caught by the mark: an ordinary break, a real break posted from inside the probe, the probe itself
 * once its post is over, and an outer probe after a nested one returns. What it cannot hold: the
 * whole path from {@code NeoForgePlatform.mayBreak} to the forwarder with a player, since the
 * forwarder only forwards for a {@code ServerPlayer} and {@code mayBreak} only posts for a player who
 * may build. Both need a live server. That last hop is one line in each, and review-covered.
 */
@DisplayName("a break probe is never a break")
class NeoForgeBreakProbeTest {

    /** What the forwarder's test answered for each event, read while that event was being posted. */
    private static final Map<BreakBlockEvent, Boolean> REAL_WHILE_POSTED = new IdentityHashMap<>();

    /** Whatever a test wants done inside a post. Nothing, between tests. */
    private static Consumer<BreakBlockEvent> inside = event -> { };

    @BeforeAll
    static void listen() {
        // The game bus is built shut down and the loader starts it once mods are constructed; until then a
        // post returns without calling anyone. Nothing starts it in a unit test, so this does -- without it
        // every assertion below would be about listeners that were never asked.
        NeoForge.EVENT_BUS.start();
        NeoForge.EVENT_BUS.addListener((BreakBlockEvent event) -> {
            REAL_WHILE_POSTED.put(event, NeoForgeEvents.isRealBreak(event));
            inside.accept(event);
        });
    }

    @AfterEach
    void reset() {
        inside = event -> { };
        REAL_WHILE_POSTED.clear();
    }

    @Test
    @DisplayName("a probe is not a real break while it is posted, and is unmarked once the post is over")
    void aProbeIsMarkedForItsPost() {
        BreakBlockEvent probe = breakEvent();

        NeoForgeEvents.postProbe(probe);

        assertEquals(Boolean.FALSE, REAL_WHILE_POSTED.get(probe),
                "the forwarder would have reported mayBreak's question as a break");
        assertTrue(NeoForgeEvents.isRealBreak(probe),
                "the mark outlived the post, so it would also hide the next event that happened to be it");
    }

    @Test
    @DisplayName("an ordinary break is still a real break")
    void anOrdinaryBreakIsReal() {
        BreakBlockEvent real = breakEvent();

        NeoForge.EVENT_BUS.post(real);

        assertEquals(Boolean.TRUE, REAL_WHILE_POSTED.get(real),
                "a break posted the ordinary way must still forward");
    }

    @Test
    @DisplayName("a break posted during a probe is real, and a nested probe leaves the outer one marked")
    void eventsInsideAProbeAreTheirOwn() {
        BreakBlockEvent outer = breakEvent();
        BreakBlockEvent real = breakEvent();
        BreakBlockEvent nested = breakEvent();
        boolean[] outerStillMarked = new boolean[1];
        inside = event -> {
            if (event == outer) {
                NeoForge.EVENT_BUS.post(real);
                NeoForgeEvents.postProbe(nested);
                outerStillMarked[0] = !NeoForgeEvents.isRealBreak(outer);
            }
        };

        NeoForgeEvents.postProbe(outer);

        assertEquals(Boolean.TRUE, REAL_WHILE_POSTED.get(real),
                "a real break posted while a probe ran was skipped: the mark is a flag, not the probe");
        assertEquals(Boolean.FALSE, REAL_WHILE_POSTED.get(nested), "the nested probe is a probe too");
        assertTrue(outerStillMarked[0],
                "the nested probe cleared the outer one's mark, so the outer probe would forward");
        assertEquals(Boolean.FALSE, REAL_WHILE_POSTED.get(outer), "and the outer probe is a probe");
        assertTrue(NeoForgeEvents.isRealBreak(outer), "with nothing left marked afterwards");
    }

    @Test
    @DisplayName("a listener that throws during a probe does not leave the mark behind")
    void aThrowingListenerDoesNotStrandTheMark() {
        BreakBlockEvent probe = breakEvent();
        inside = event -> {
            if (event == probe) {
                throw new ListenerFailure();
            }
        };

        assertThrows(ListenerFailure.class, () -> NeoForgeEvents.postProbe(probe),
                "the bus passes a listener's failure on to the poster");

        assertTrue(NeoForgeEvents.isRealBreak(probe),
                "the mark survived a failed post, so this event would be skipped from now on");
    }

    /** No level, state or player: the probe's marking reads none of them. */
    private static BreakBlockEvent breakEvent() {
        return new BreakBlockEvent(null, BlockPos.ZERO, null, null);
    }

    /** Its own type, so the failure asserted on is this test's and not one the bus raised itself. */
    private static final class ListenerFailure extends RuntimeException {
        ListenerFailure() {
            super("a listener failing in the middle of a probe");
        }
    }
}
