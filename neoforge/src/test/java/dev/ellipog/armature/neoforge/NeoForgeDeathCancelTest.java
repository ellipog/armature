package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.api.event.ArmatureEvents;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A death NeoForge reports can still be cancelled, so Armature reports it only once every other listener
 * has had the chance (ARM-22).
 *
 * <p>The entity is never read by the forwarder, so null stands in for it; the control test shows the
 * forwarder runs at all, so the cancel tests fail when it stops listening last rather than passing because
 * nothing was posted to it.
 */
@DisplayName("cancelled deaths never forward")
class NeoForgeDeathCancelTest {

    private static final List<LivingEntity> FORWARDED = new ArrayList<>();
    private static final AtomicBoolean ARMED = new AtomicBoolean(false);

    @BeforeAll
    static void attach() {
        TestBus.attachOnce();
        ArmatureEvents.ENTITY_DEATH.register((entity, source) -> FORWARDED.add(entity));
        // A revive at LOW: below NORMAL, where the forwarder used to listen, so this cancelled after the
        // death had already been reported.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, true, (LivingDeathEvent event) -> {
            if (ARMED.get()) {
                event.setCanceled(true);
            }
        });
    }

    @Test
    @DisplayName("an uncancelled death forwards")
    void anUncancelledDeathForwards() {
        FORWARDED.clear();
        NeoForge.EVENT_BUS.post(new LivingDeathEvent(null, null));
        assertEquals(1, FORWARDED.size(), "the forwarder did not run");
    }

    @Test
    @DisplayName("a death cancelled at lower priority never forwards")
    void cancelledLateNeverForwards() {
        FORWARDED.clear();
        ARMED.set(true);
        try {
            NeoForge.EVENT_BUS.post(new LivingDeathEvent(null, null));
        }
        finally {
            ARMED.set(false);
        }
        assertTrue(FORWARDED.isEmpty(), "a death a later listener cancelled was already reported");
    }
}
