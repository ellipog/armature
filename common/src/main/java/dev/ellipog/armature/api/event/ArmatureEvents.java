package dev.ellipog.armature.api.event;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * The events Armature publishes, and the listeners they take.
 *
 * <p>Eight, chosen because they are the hooks a content mod actually needs and because
 * both loaders can supply every one of them from public API — no mixins.
 *
 * <p>All of these fire on the <b>server</b> thread, on both integrated and dedicated
 * servers. A single-player world is a server; treat it as one, or the mod works in your
 * dev environment and breaks for everyone on a server.
 *
 * <p>Listener interfaces are nested here so that registering one reads as
 * {@code ArmatureEvents.PLAYER_JOIN.register(player -> ...)} without an import per event
 * type.
 *
 * <h2>Two of these fire at genuinely different moments on the two loaders</h2>
 *
 * <p>Observed by booting both loaders' dedicated servers and reading the logs, not inferred:
 *
 * <ul>
 *   <li>{@link #SERVER_STARTING} fires <b>very early on Fabric</b> — before the game even logs
 *       {@code Starting minecraft server version} — and <b>late on NeoForge</b>, after
 *       {@code Done (3.3s)! For help, type "help"}. So on Fabric there is no world yet; on
 *       NeoForge the world exists and a player could already be connecting.</li>
 *   <li>{@link #ENTITY_DEATH} fires <b>after</b> the death on Fabric
 *       ({@code ServerLivingEntityEvents.AFTER_DEATH}) and <b>before</b> it on NeoForge
 *       ({@code LivingDeathEvent}, while it can still be cancelled).</li>
 * </ul>
 *
 * <p>Both events therefore carry only what is immutably true on either side — the server
 * instance, or the entity and its damage source — and say nothing about world state. Anything
 * that needs to know whether the world exists yet must check, not assume.
 *
 * <p>A consequence worth knowing before it bites: <b>do not load persistent data from
 * {@link #SERVER_STARTING}</b> without checking. On Fabric the level is not there yet. Loading
 * from {@link #SERVER_STARTED} instead is well-defined on both, and is what a data store
 * should do.
 */
public final class ArmatureEvents {

    private ArmatureEvents() {
    }

    // ------------------------------------------------------------------
    // Listener shapes
    // ------------------------------------------------------------------

    /**
     * The server is loading. <b>Necessarily vague, because the two loaders disagree.</b>
     *
     * <p>On Fabric this fires before the game has logged {@code Starting minecraft server
     * version} — no world exists. On NeoForge it fires after {@code Done (3.3s)!}, with the
     * world fully loaded and a player possibly already connecting. Verified on both, not
     * assumed.
     *
     * <p>So a listener here may safely do work that needs the server <i>object</i>, and may
     * not assume anything about the world. To load data that depends on a level, use
     * {@link #SERVER_STARTED} instead — that one is well-defined on both.
     */
    @FunctionalInterface
    public interface ServerStarting {
        void onServerStarting(MinecraftServer server);
    }

    /** The server is up and players can join. Fires at a comparable point on both loaders. */
    @FunctionalInterface
    public interface ServerStarted {
        void onServerStarted(MinecraftServer server);
    }

    /** The server is shutting down but still running. Last chance to save anything of your own. */
    @FunctionalInterface
    public interface ServerStopping {
        void onServerStopping(MinecraftServer server);
    }

    /**
     * Once per player, per tick — twenty times a second, per player.
     *
     * <p>This is the single hottest hook in the API. Check a cheap condition first and do
     * the expensive work rarely; a mod that scans every player's inventory here will
     * be blamed for lag it did not cause.
     */
    @FunctionalInterface
    public interface PlayerTick {
        void onPlayerTick(ServerPlayer player);
    }

    /** A player joined. Their player data is loaded by this point. */
    @FunctionalInterface
    public interface PlayerJoin {
        void onPlayerJoin(ServerPlayer player);
    }

    /** A player left, or was disconnected. Fires for a kick and a crash, not just a clean exit. */
    @FunctionalInterface
    public interface PlayerLeave {
        void onPlayerLeave(ServerPlayer player);
    }

    /**
     * Something died.
     *
     * <p>Note the ordering differs by loader: Fabric reports the death after it has been
     * processed, NeoForge before, while it can still be cancelled. Anything that must see
     * the world in one particular state has to cope with both — the event itself carries
     * immutably what was killed and by what, which is usually all a caller needs.
     */
    @FunctionalInterface
    public interface EntityDeath {
        void onEntityDeath(LivingEntity entity, DamageSource source);
    }

    /**
     * The server is collecting commands. Register a {@code /} command here.
     *
     * <p>Fires on server start and again on every datapack reload, so register each time
     * it fires rather than once at construction.
     */
    @FunctionalInterface
    public interface CommandsRegister {
        void onCommandsRegister(CommandDispatcher<CommandSourceStack> dispatcher,
                               CommandBuildContext context,
                               Commands.CommandSelection selection);
    }

    // ------------------------------------------------------------------
    // The events
    // ------------------------------------------------------------------

    public static final Event<ServerStarting> SERVER_STARTING = new Event<>(listeners ->
            (MinecraftServer server) -> {
                for (ServerStarting listener : listeners) {
                    listener.onServerStarting(server);
                }
            });

    public static final Event<ServerStarted> SERVER_STARTED = new Event<>(listeners ->
            (MinecraftServer server) -> {
                for (ServerStarted listener : listeners) {
                    listener.onServerStarted(server);
                }
            });

    public static final Event<ServerStopping> SERVER_STOPPING = new Event<>(listeners ->
            (MinecraftServer server) -> {
                for (ServerStopping listener : listeners) {
                    listener.onServerStopping(server);
                }
            });

    public static final Event<PlayerTick> PLAYER_TICK = new Event<>(listeners ->
            (ServerPlayer player) -> {
                for (PlayerTick listener : listeners) {
                    listener.onPlayerTick(player);
                }
            });

    public static final Event<PlayerJoin> PLAYER_JOIN = new Event<>(listeners ->
            (ServerPlayer player) -> {
                for (PlayerJoin listener : listeners) {
                    listener.onPlayerJoin(player);
                }
            });

    public static final Event<PlayerLeave> PLAYER_LEAVE = new Event<>(listeners ->
            (ServerPlayer player) -> {
                for (PlayerLeave listener : listeners) {
                    listener.onPlayerLeave(player);
                }
            });

    public static final Event<EntityDeath> ENTITY_DEATH = new Event<>(listeners ->
            (LivingEntity entity, DamageSource source) -> {
                for (EntityDeath listener : listeners) {
                    listener.onEntityDeath(entity, source);
                }
            });

    public static final Event<CommandsRegister> COMMANDS_REGISTER = new Event<>(listeners ->
            (CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
             Commands.CommandSelection selection) -> {
                for (CommandsRegister listener : listeners) {
                    listener.onCommandsRegister(dispatcher, context, selection);
                }
            });
}
