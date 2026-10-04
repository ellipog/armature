package dev.ellipog.armature.api.teams;

import dev.ellipog.armature.api.event.Event;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Team membership changes, as they happen.
 *
 * <p>Armature does not care what a team is <i>for</i>. A caller uses one to share something, and
 * that means somebody has to be told when the membership changes: a player joining a team has to
 * bring their share of that state with them, or leave it behind, and only the mod that owns the
 * state can decide which.
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
     * The team's name changed.
     *
     * <p>Fires after the store is updated, so the team passed in carries the new name — the old one is
     * not in the event because every reader that cares can see what the name is now, and a listener
     * that wanted the previous name could only use it to undo the change.
     */
    @FunctionalInterface
    public interface TeamRenamed {
        void onTeamRenamed(MinecraftServer server, Team team);
    }

    /**
     * Ownership moved to another member.
     *
     * <p>Fires after the store is updated. The previous owner is named because it cannot be read off
     * the team any more — the whole point of the event is that the field changed.
     */
    @FunctionalInterface
    public interface OwnerTransferred {
        void onOwnerTransferred(MinecraftServer server, Team team, UUID previousOwner);
    }

    /**
     * The team's {@link TeamPolicy} changed.
     *
     * <p>Fires after the store is updated. A listener that mirrors a party panel wants this because a
     * toggled switch has to reach the other members, and the payload the panel draws from carries the
     * policy.
     */
    @FunctionalInterface
    public interface PolicyChanged {
        void onPolicyChanged(MinecraftServer server, Team team);
    }

    /**
     * An invitation was sent, withdrawn or declined.
     *
     * <p>One event rather than three because every listener does the same thing with all of them —
     * push the affected rosters — and three interfaces would be three chances for a source to fire
     * two of the three. Fires after the store is updated, so {@code team} is the team as it now
     * stands: {@code target} is still in {@code team.invites()} for {@link InviteKind#SENT} and gone
     * from it for the other two.
     */
    @FunctionalInterface
    public interface InviteChanged {
        void onInviteChanged(MinecraftServer server, Team team, UUID target, InviteKind kind);
    }

    /** What happened to an invitation. See {@link InviteChanged}. */
    public enum InviteKind {
        /** An invitation was sent to {@code target}. */
        SENT,
        /** An invitation was withdrawn by somebody who may invite. */
        CANCELLED,
        /** The invitee said no. */
        DECLINED
    }

    /**
     * Why somebody is no longer in a team.
     *
     * <p>Worth distinguishing because a listener may want to say something different — a kick is
     * announced to the removed player, a leave is not — and because the three arrive at different
     * moments: a disband reaches a listener once per member, after the team is gone from the store.
     *
     * <p>It is <b>not</b> a licence to withhold state. A player who was removed keeps everything
     * they earned while in the team exactly as one who left on purpose does: a kick is a statement
     * about behaviour, and confiscating progression is not a moderation tool.
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

    public static final Event<TeamRenamed> TEAM_RENAMED = new Event<>(listeners ->
            (MinecraftServer server, Team team) -> {
                for (TeamRenamed listener : listeners) {
                    listener.onTeamRenamed(server, team);
                }
            });

    public static final Event<OwnerTransferred> OWNER_TRANSFERRED = new Event<>(listeners ->
            (MinecraftServer server, Team team, UUID previousOwner) -> {
                for (OwnerTransferred listener : listeners) {
                    listener.onOwnerTransferred(server, team, previousOwner);
                }
            });

    public static final Event<PolicyChanged> POLICY_CHANGED = new Event<>(listeners ->
            (MinecraftServer server, Team team) -> {
                for (PolicyChanged listener : listeners) {
                    listener.onPolicyChanged(server, team);
                }
            });

    public static final Event<InviteChanged> INVITE_CHANGED = new Event<>(listeners ->
            (MinecraftServer server, Team team, UUID target, InviteKind kind) -> {
                for (InviteChanged listener : listeners) {
                    listener.onInviteChanged(server, team, target, kind);
                }
            });
}
