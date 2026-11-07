package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.api.event.ArmatureEvents;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
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

    /**
     * The event {@link #postProbe} is posting, or null.
     *
     * <p>A plain field because probing is server-thread code, like the level it asks about. A break
     * posted on another thread meanwhile is never this object, so a read from there cannot mistake it
     * for the probe: the mark can only ever skip the probe itself.
     */
    private static BreakBlockEvent probing;

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
            if (!ArmatureEvents.PLAYER_JOIN.hasListeners()) {
                return;
            }
            if (event.getEntity() instanceof ServerPlayer player) {
                ArmatureEvents.PLAYER_JOIN.invoker().onPlayerJoin(player);
            }
        });

        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (!ArmatureEvents.PLAYER_LEAVE.hasListeners()) {
                return;
            }
            if (event.getEntity() instanceof ServerPlayer player) {
                ArmatureEvents.PLAYER_LEAVE.invoker().onPlayerLeave(player);
            }
        });

        // Note the ordering difference from Fabric: NeoForge reports a death *before* it is
        // processed, while it can still be cancelled; Fabric reports it after. The event
        // carries what was killed and by what, which is all a progression engine needs, but
        // anything that has to see the world in one particular state cannot rely on it.
        bus.addListener((LivingDeathEvent event) -> {
            if (!ArmatureEvents.ENTITY_DEATH.hasListeners()) {
                return;
            }
            ArmatureEvents.ENTITY_DEATH.invoker().onEntityDeath(event.getEntity(), event.getSource());
        });

        // BreakBlockEvent is cancellable and fires when a player attempts the break: a cancelled
        // event is a protected block, so only an uncancelled one reaches
        // listeners — that is the "actually happened" half of the contract
        // Fabric's AFTER side gives for free. Lowest priority and
        // receiveCancelled=false, so a protection mod cancelling at any
        // higher priority has already run before this listener is even
        // considered: without both, a late canceller would leak a
        // BLOCK_BROKEN for a block that never broke. The isCanceled check
        // stays as well, so the guarantee does not silently depend on the
        // flags above surviving a future edit. Fired on both sides, so the
        // ServerPlayer check is what keeps this server-only. And not every
        // post is a break: mayBreak posts one to ask, see postProbe.
        bus.addListener(EventPriority.LOWEST, false, (BreakBlockEvent event) -> {
            if (!ArmatureEvents.BLOCK_BROKEN.hasListeners()) {
                return;
            }
            if (isRealBreak(event) && event.getPlayer() instanceof ServerPlayer player) {
                ArmatureEvents.BLOCK_BROKEN.invoker().onBlockBroken(player, event.getState());
            }
        });

        bus.addListener((RegisterCommandsEvent event) ->
                ArmatureEvents.COMMANDS_REGISTER.invoker()
                        .onCommandsRegister(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection()));
    }

    /**
     * Posts a break that is only a question, for {@link NeoForgePlatform#mayBreak}: may this player
     * break this block?
     *
     * <p>It has to be a real {@link BreakBlockEvent} on the real bus, because that is the event claim
     * mods cancel. But nothing breaks, and without a mark the forwarder above would report every
     * allowed probe as a {@code BLOCK_BROKEN}: a player's trample check counting toward a break
     * task. So the event is held in {@link #probing} for exactly the length of its post, and the
     * forwarder skips that one object.
     *
     * <p>The previous mark is put back rather than cleared, so a listener that asks {@code mayBreak}
     * itself during a probe leaves the outer probe still marked when it reaches the forwarder.
     *
     * @return the event, cancelled if anything refused the break
     */
    static BreakBlockEvent postProbe(BreakBlockEvent event) {
        BreakBlockEvent outer = probing;
        probing = event;
        try {
            return NeoForge.EVENT_BUS.post(event);
        }
        finally {
            probing = outer;
        }
    }

    /**
     * Whether a break event reports a block that actually broke: nothing cancelled it, and it is not
     * the question {@link #postProbe} is asking.
     *
     * <p>The probe is matched by identity rather than by a flag meaning "a probe is running", so a
     * real break posted while one is, from inside a listener, is a different object and still
     * forwards. Package-private so that can be pinned without a player.
     */
    static boolean isRealBreak(BreakBlockEvent event) {
        return !event.isCanceled() && event != probing;
    }
}
