package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.api.event.ArmatureEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancelled break is a protected block: nothing broke, so no BLOCK_BROKEN may fire.
 *
 * <h2>How this sees the forwarder's decision without a player</h2>
 *
 * <p>The forwarder refuses a cancelled break first and asks the event for its player only after, to
 * check it is a {@code ServerPlayer}. A real {@code ServerPlayer} cannot be made headless (its attachment
 * holder needs a running FML loader), so the event here is a subclass that notes when its player is asked
 * for. Asked means the forwarder got past the cancel guard and would have forwarded for a real player;
 * not asked means it refused, or never ran.
 *
 * <p>The control test is what makes the other two mean something. Before ARM-21 the bus was never started
 * here, so a post called nobody and both cancel tests passed with the forwarder never running. Now the
 * control fails if the forwarder does not run, and the cancel tests fail if it stops refusing cancelled
 * breaks (both its {@code receiveCanceled=false} registration and its {@code isCanceled} check removed).
 *
 * <p>What it cannot hold: the priority half against a real protection mod at higher priority, which needs a
 * live server. A canceller at LOW is covered here.
 */
@DisplayName("cancelled breaks never forward")
class NeoForgeBreakCancelTest {

    private static final AtomicBoolean ARMED = new AtomicBoolean(false);

    @BeforeAll
    static void attach() {
        TestBus.attachOnce();
        // The forwarder returns at once while nothing listens, so something has to.
        ArmatureEvents.BLOCK_BROKEN.register((who, state) -> { });
        // A protection mod cancelling late: LOW runs after everything but our listener, so a
        // forward past this point is a listener that does not listen last.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, true, (BreakBlockEvent event) -> {
            if (ARMED.get()) {
                event.setCanceled(true);
            }
        });
    }

    @Test
    @DisplayName("an uncancelled break gets past the guard, so the tests below are about a listener that runs")
    void anUncancelledBreakIsForwarded() {
        Watched event = new Watched();
        NeoForge.EVENT_BUS.post(event);
        assertTrue(event.playerAsked,
                "the forwarder never looked at the player: the bus is not started, or it is not attached");
    }

    @Test
    @DisplayName("a break cancelled before the post is refused")
    void cancelledBeforePostNeverForwards() {
        Watched event = new Watched();
        event.setCanceled(true);
        NeoForge.EVENT_BUS.post(event);
        assertFalse(event.playerAsked,
                "a cancelled break got past the guard: the listener takes cancelled events or skips its check");
    }

    @Test
    @DisplayName("a break cancelled late is refused")
    void cancelledLateNeverForwards() {
        Watched event = new Watched();
        ARMED.set(true);
        try {
            NeoForge.EVENT_BUS.post(event);
        }
        finally {
            ARMED.set(false);
        }
        assertFalse(event.playerAsked,
                "a late cancellation got past the guard: the listener does not listen last");
    }

    /** A break with no level, state or player, that notes whether its player was asked for. */
    private static final class Watched extends BreakBlockEvent {

        private boolean playerAsked;

        Watched() {
            super(null, BlockPos.ZERO, null, null);
        }

        @Override
        public Player getPlayer() {
            playerAsked = true;
            return super.getPlayer();
        }
    }
}
