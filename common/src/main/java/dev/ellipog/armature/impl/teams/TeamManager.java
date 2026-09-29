package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamEvents;
import dev.ellipog.armature.api.teams.TeamRole;

import net.minecraft.server.MinecraftServer;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Teams for one server: the queries, the mutations, and the events.
 *
 * <h2>Two things this deliberately does not do</h2>
 *
 * <p><b>It does not know what a team is for.</b> There is not a word here about progress, rewards or
 * anything else a caller might key by a team — that is the caller's business, and it attaches
 * through {@link TeamEvents}. This is the primitive the plan calls for: the reason FTB had to ship a
 * third mod is that progress-sharing was tangled into the code that owned the progress, and
 * untangling it afterwards is far more expensive than keeping it separate now.
 *
 * <p><b>It does not cache.</b> Every lookup goes to the store, which is a {@code LinkedHashMap}
 * lookup over a handful of teams. A cache means an invalidation bug, and there is nothing here
 * expensive enough to be worth one.
 *
 * <h2>Saving</h2>
 *
 * <p>{@link TeamStore} is a {@code SavedData}, so Minecraft writes it at world save.
 * <b>{@code TeamStore.put} and {@code TeamStore.remove} call {@code setDirty()} themselves</b> —
 * that is deliberate rather than tidy. Forgetting the dirty flag produces a team that works all
 * session and is gone tomorrow, and the surest way to never forget it is to have no call site that
 * could.
 */
public final class TeamManager {

    private final MinecraftServer server;
    private final TeamStore store;

    private TeamManager(MinecraftServer server, TeamStore store) {
        this.server = server;
        this.store = store;
    }

    /** The manager for this server, finding or creating the store. */
    public static TeamManager of(MinecraftServer server) {
        return new TeamManager(server, TeamStore.of(server));
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /**
     * The team a player is in — their real team, or a solo team of one.
     *
     * <p>Never empty, which is the point: a caller does not have to decide what to do about a player
     * with no team, because there is no such player.
     */
    public Team teamOf(UUID player) {
        return realTeamOf(player).orElseGet(() -> Team.solo(player));
    }

    /** The real team a player is in, if any. Empty for a solo player. */
    public Optional<Team> realTeamOf(UUID player) {
        for (Team team : store.all()) {
            if (team.isMember(player)) {
                return Optional.of(team);
            }
        }
        return Optional.empty();
    }

    /**
     * The teams a player has been invited to.
     *
     * <p>A list, not one, because a player may be invited to two and choose — and because silently
     * keeping only the most recent invitation is a way to lose one without telling anybody.
     */
    public List<Team> invitesFor(UUID player) {
        return store.all().stream().filter(team -> team.isInvited(player)).toList();
    }

    public Optional<Team> byId(UUID id) {
        return store.byId(id);
    }

    /** Every real team, sorted by name so a listing reads the same way twice. */
    public Collection<Team> allTeams() {
        return store.all().stream()
                .sorted(Comparator.comparing(team -> team.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    public int teamCount() {
        return store.size();
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    /**
     * Forms a team, with {@code owner} as its only member.
     *
     * <p>If the owner is already in one, that is refused rather than silently moving them — moving
     * would strand their progress with no warning.
     *
     * @throws IllegalStateException if the owner is already in a team
     */
    public Team create(String name, UUID owner) {
        if (realTeamOf(owner).isPresent()) {
            throw new IllegalStateException("That player is already in a team; leave it first.");
        }
        Team team = Team.created(UUID.randomUUID(), name, owner, server.overworld().getGameTime());
        store.put(team);

        Constants.LOG.info("armature: team '{}' created by {}", name, owner);
        TeamEvents.TEAM_CREATED.invoker().onTeamCreated(server, team);
        return team;
    }

    /** Invites a player. False if they are already in the team or already invited. */
    public boolean invite(UUID teamId, UUID player) {
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty()) {
            return false;
        }
        Team team = found.get();
        if (team.isMember(player) || team.isInvited(player)) {
            return false;
        }
        store.put(team.withInvite(player));
        return true;
    }

    /**
     * Accepts an invitation.
     *
     * <p>If the player is in a team already, they leave it first — which fires
     * {@link TeamEvents.Reason#LEFT} so that anything they were carrying, like quest progress, knows
     * to deal with it. Doing that silently is how a player ends up with progress in two teams.
     *
     * @return the team they joined, or empty if there was no invitation to accept
     */
    public Optional<Team> acceptInvite(UUID player) {
        Optional<Team> target = invitesFor(player).stream().findFirst();
        if (target.isEmpty()) {
            return Optional.empty();
        }

        realTeamOf(player).ifPresent(current -> depart(current, player, TeamEvents.Reason.LEFT));

        Team joined = target.get().withMember(player, TeamRole.MEMBER);
        store.put(joined);

        Constants.LOG.info("armature: {} joined team '{}'", player, joined.name());
        // Fires after the store is updated, so a listener reading the team sees the new membership.
        TeamEvents.MEMBER_JOINED.invoker().onMemberJoined(server, joined, player);
        return Optional.of(joined);
    }

    /**
     * Leaves the current team.
     *
     * <p>A solo player leaving is a no-op rather than an error — there is nothing to leave, and a
     * command that fails for the commonest case is a command nobody can use.
     *
     * @return true if a real team was left
     */
    public boolean leave(UUID player) {
        Optional<Team> current = realTeamOf(player);
        if (current.isEmpty()) {
            return false;
        }
        return depart(current.get(), player, TeamEvents.Reason.LEFT);
    }

    /**
     * Removes somebody else.
     *
     * @return false if the actor has no authority over the target, which the caller should report
     *         rather than swallow
     */
    public boolean kick(UUID actor, UUID target) {
        Optional<Team> teamOfTarget = realTeamOf(target);
        if (teamOfTarget.isEmpty()) {
            return false;
        }
        Team team = teamOfTarget.get();

        if (!team.isMember(actor) || !team.roleOrMember(actor).outranks(team.roleOrMember(target))) {
            return false;
        }
        return depart(team, target, TeamEvents.Reason.KICKED);
    }

    /**
     * Disbands a team entirely.
     *
     * @return false if the actor is not the owner
     */
    public boolean disband(UUID actor, UUID teamId) {
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty()) {
            return false;
        }
        Team team = found.get();
        if (!team.owner().equals(actor)) {
            return false;
        }

        List<UUID> members = List.copyOf(team.memberIds());
        store.remove(teamId);

        Constants.LOG.info("armature: team '{}' disbanded by {}", team.name(), actor);
        TeamEvents.TEAM_DISBANDED.invoker().onTeamDisbanded(server, team);
        for (UUID member : members) {
            TeamEvents.MEMBER_LEFT.invoker().onMemberLeft(server, team, member, TeamEvents.Reason.DISBANDED);
        }
        return true;
    }

    // ------------------------------------------------------------------

    private boolean depart(Team team, UUID player, TeamEvents.Reason reason) {
        Team after = team.withoutMember(player);

        if (after.members().isEmpty()) {
            // Nobody left, so there is no team -- the same outcome as a disband, reached by a
            // different route. Reported as a disband so a listener has one case to handle.
            store.remove(team.id());
            TeamEvents.TEAM_DISBANDED.invoker().onTeamDisbanded(server, team);
        }
        else {
            store.put(after);
        }

        TeamEvents.MEMBER_LEFT.invoker().onMemberLeft(server, after, player, reason);
        return true;
    }
}
