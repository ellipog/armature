package dev.ellipog.armature.api.teams;

import dev.ellipog.armature.impl.teams.TeamManager;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Teams, from the outside.
 *
 * <p>The whole surface is one call: {@link #of} hands back the {@link TeamManager} for a server,
 * and everything else hangs off that.
 *
 * <p>Reached through a static factory rather than through {@code ArmatureApi} because teams are
 * <b>per server</b>, and there can be more than one server in a process — a client with an
 * integrated server open, for instance. Anything cached in a static field would be the wrong
 * server's teams, which is the kind of bug that only appears in a dev environment with two worlds
 * open and is baffling when it does.
 *
 * <p>The manager reads and writes a {@code SavedData} on the overworld, so it is per-world and
 * survives a restart. Nothing needs to be initialised or registered first.
 */
public final class Teams {

    private Teams() {
    }

    /** The teams on this server. */
    public static TeamManager of(MinecraftServer server) {
        return TeamManager.of(server);
    }

    /**
     * The team {@code player} is in, real or solo.
     *
     * <p>Convenience for the common case, and the reason a caller never has to write
     * {@code Optional<Team>} plus a fallback.
     */
    public static Team teamOf(MinecraftServer server, UUID player) {
        return of(server).teamOf(player);
    }
}
