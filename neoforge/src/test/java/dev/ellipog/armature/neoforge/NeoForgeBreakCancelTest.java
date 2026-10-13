package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.api.event.ArmatureEvents;

import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancelled break is a protected block: nothing broke, so no BLOCK_BROKEN may fire.
 *
 * <p>Pinned without a world, and honest about what headless can and cannot hold. What this
 * holds: the entry point attaches without a world; a cancelled event short-circuits before
 * touching the event — level, state and player are all null here, so a guard that reads
 * anything before refusing throws instead of passing; and a late cancellation during the
 * post runs the whole priority path without leaking or crashing. What it cannot hold without
 * a real player object: the positive path (an uncancelled break by a person forwards) and
 * the priority half against a higher listener — those need a live server, see the testserver
 * combos, and the LOWEST + receiveCanceled=false pair in NeoForgeEvents stays review-covered.
 */
@DisplayName("cancelled breaks never forward")
class NeoForgeBreakCancelTest {

    private static final List<String> FORWARDED = new ArrayList<>();
    private static final AtomicBoolean ARMED = new AtomicBoolean(false);

    @BeforeAll
    static void attach() {
        NeoForgeEvents.attach();
        ArmatureEvents.BLOCK_BROKEN.register((player, state) -> FORWARDED.add("forward"));
        // A protection mod cancelling late: LOW runs after everything but our listener, so a
        // forward past this point is a listener that does not listen last.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, true, (BreakBlockEvent event) -> {
            if (ARMED.get()) {
                event.setCanceled(true);
            }
        });
    }

    @Test
    @DisplayName("a break cancelled before the post short-circuits before touching it")
    void cancelledBeforePostNeverForwards() {
        FORWARDED.clear();
        BreakBlockEvent event =
                new BreakBlockEvent(null, BlockPos.ZERO, null, null);
        event.setCanceled(true);
        NeoForge.EVENT_BUS.post(event);
        assertTrue(FORWARDED.isEmpty(),
                "a cancelled break forwarded: the listener takes cancelled events or skips its guard");
    }

    @Test
    @DisplayName("a break cancelled late runs the priority path without leaking")
    void cancelledLateNeverForwards() {
        FORWARDED.clear();
        ARMED.set(true);
        try {
            NeoForge.EVENT_BUS.post(new BreakBlockEvent(null, BlockPos.ZERO, null, null));
        }
        finally {
            ARMED.set(false);
        }
        assertTrue(FORWARDED.isEmpty(),
                "a late cancellation leaked a BLOCK_BROKEN: the listener does not listen last");
    }
}
