package dev.ellipog.armature.fabric;

import dev.ellipog.armature.api.event.ArmatureEvents;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * The only place Fabric's event API is named. Each hook is forwarded straight to the
 * matching {@link ArmatureEvents} event, with no logic in between, so that Armature's
 * events mean exactly what the loader's do.
 *
 * <p>Fabric has no event for "a player ticked" or for a death that has finished being
 * processed, so both are assembled here from what it does offer — see the comments on the
 * individual registrations.
 */
public final class FabricEvents {

    private FabricEvents() {
    }

    /** Called once, during mod construction. */
    public static void attach() {
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                ArmatureEvents.SERVER_STARTING.invoker().onServerStarting(server));

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                ArmatureEvents.SERVER_STARTED.invoker().onServerStarted(server));

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                ArmatureEvents.SERVER_STOPPING.invoker().onServerStopping(server));

        // Fabric fires a server tick, not a player tick. Walking the player list once per
        // tick is cheap and is the honest way to offer the same event as NeoForge, where it
        // is a real hook. The hasListeners check means a server where nothing listens
        // pays nothing at all for it.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!ArmatureEvents.PLAYER_TICK.hasListeners()) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ArmatureEvents.PLAYER_TICK.invoker().onPlayerTick(player);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ArmatureEvents.PLAYER_JOIN.invoker().onPlayerJoin(handler.player));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ArmatureEvents.PLAYER_LEAVE.invoker().onPlayerLeave(handler.player));

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) ->
                ArmatureEvents.ENTITY_DEATH.invoker().onEntityDeath(entity, source));

        // Fires on server start and again on every datapack reload, which is what the
        // NeoForge equivalent does too — so listeners register their commands each time
        // rather than once.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ArmatureEvents.COMMANDS_REGISTER.invoker()
                        .onCommandsRegister(dispatcher, registryAccess, environment));
    }
}
