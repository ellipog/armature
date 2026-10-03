package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.api.event.ArmatureEvents;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The only place NeoForge's event bus is named. Each hook is forwarded straight to the
 * matching {@link ArmatureEvents} event, with no logic in between, so that Armature's events
 * mean exactly what NeoForge's do.
 *
 * <p>Every one of these is on the <b>game</b> bus. NeoForge splits its bus in two: mod
 * lifecycle events on the per-mod bus, everything about a running game on the global one.
 * Getting that wrong produces a silent no-op rather than an error, so it is worth stating.
 */
public final class NeoForgeEvents {

    private NeoForgeEvents() {
    }

    /** Called once, during mod construction. */
    public static void attach() {
        var bus = NeoForge.EVENT_BUS;

        bus.addListener((ServerStartingEvent event) ->
                ArmatureEvents.SERVER_STARTING.invoker().onServerStarting(event.getServer()));

        bus.addListener((ServerStartedEvent event) ->
                ArmatureEvents.SERVER_STARTED.invoker().onServerStarted(event.getServer()));

        bus.addListener((ServerStoppingEvent event) ->
                ArmatureEvents.SERVER_STOPPING.invoker().onServerStopping(event.getServer()));

        // NeoForge fires this per player, which is why Armature can offer a genuine player
        // tick here while Fabric has to assemble one from a server tick.
        bus.addListener((PlayerTickEvent.Post event) -> {
            if (event.getEntity() instanceof ServerPlayer player && ArmatureEvents.PLAYER_TICK.hasListeners()) {
                ArmatureEvents.PLAYER_TICK.invoker().onPlayerTick(player);
            }
        });

        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ArmatureEvents.PLAYER_JOIN.invoker().onPlayerJoin(player);
            }
        });

        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ArmatureEvents.PLAYER_LEAVE.invoker().onPlayerLeave(player);
            }
        });

        // Note the ordering difference from Fabric: NeoForge reports a death *before* it is
        // processed, while it can still be cancelled; Fabric reports it after. The event
        // carries what was killed and by what, which is all a progression engine needs, but
        // anything that has to see the world in one particular state cannot rely on it.
        bus.addListener((LivingDeathEvent event) ->
                ArmatureEvents.ENTITY_DEATH.invoker().onEntityDeath(event.getEntity(), event.getSource()));

        bus.addListener((RegisterCommandsEvent event) ->
                ArmatureEvents.COMMANDS_REGISTER.invoker()
                        .onCommandsRegister(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection()));
    }
}
