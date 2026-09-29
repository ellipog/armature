package dev.ellipog.armature.api.teams;

import dev.ellipog.armature.api.event.Event;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Team membership changes, as they happen.
 *
 * <p>Armature does not care what a team is <i>for</i>. Tasked uses one to share quest progress, and
 * that means somebody has to be told when the membership changes: a player joining a team has to
 * bring their progress with them, or leave it behind, and only the mod that owns the progress can
 * decide which.
 *
 * <p>So these events are the extension point. {@link #MEMBER_JOINED} fires <b>after</b> the team is
 * updated, so a listener reading the team sees the new membership — which is the useful direction,
 * because the common case is "make the arriving player's state agree with the team's".
 *
 * <p>All fire on the server thread.
 */
public final class TeamEvents {

    private TeamEvents() {
    }

    /** @param server the server it happened on, for a listener that needs to read or write world state */
    @FunctionalInterface
    public interface TeamCreated {
        void onTeamCreated(MinecraftServer server, Team team);
    }

    /** Fires after the team has been updated, so {@code team} already includes the new member. */
    @FunctionalInterface
    public interface MemberJoined {
        void onMemberJoined(MinecraftServer server, Team team, UUID player);
    }

    /** Fires after the team has been updated, so {@code team} no longer includes the player. */
    @FunctionalInterface
    public interface MemberLeft {
        void onMemberLeft(MinecraftServer server, Team team, UUID player, Reason reason);
    }

    /** @param team the team as it was immediately before it was removed */
    @FunctionalInterface
    public interface TeamDisbanded {
        void onTeamDisbanded(MinecraftServer server, Team team);
    }

    /**
     * Why somebody is no longer in a team.
     *
     * <p>Worth distinguishing because the sensible response differs. A player who left on purpose
     * keeps their progress; a player who was removed may not deserve to.
     */
    public enum Reason {
        /** They ran the command. */
        LEFT,
        /** Somebody with authority removed them. */
        KICKED,
        /** The team no longer exists. */
        DISBANDED
    }

    public static final Event<TeamCreated> TEAM_CREATED = new Event<>(listeners ->
            (MinecraftServer server, Team team) -> {
                for (TeamCreated listener : listeners) {
                    listener.onTeamCreated(server, team);
                }
            });

    public static final Event<MemberJoined> MEMBER_JOINED = new Event<>(listeners ->
            (MinecraftServer server, Team team, UUID player) -> {
                for (MemberJoined listener : listeners) {
                    listener.onMemberJoined(server, team, player);
                }
            });

    public static final Event<MemberLeft> MEMBER_LEFT = new Event<>(listeners ->
            (MinecraftServer server, Team team, UUID player, Reason reason) -> {
                for (MemberLeft listener : listeners) {
                    listener.onMemberLeft(server, team, player, reason);
                }
            });

    public static final Event<TeamDisbanded> TEAM_DISBANDED = new Event<>(listeners ->
            (MinecraftServer server, Team team) -> {
                for (TeamDisbanded listener : listeners) {
                    listener.onTeamDisbanded(server, team);
                }
            });
}
