package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.MutableTeamManager;
import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamRole;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
/**
 * A {@link MutableTeamManager} over a map, for tests.
 *
 * <h2>Why this exists rather than a mock</h2>
 *
 * <p>The six mutators have behaviour worth asserting on — accepting an invitation has to leave the
 * team you were in, a kick has to compare authority, a disband has to be refused for a non-owner — and
 * a mock that returns {@code true} proves only that a method was called. This is the smallest thing
 * that has the same <i>rules</i> as the stored manager, so a test can ask a real question about them
 * without a server, a world or a {@code SavedData}.
 *
 * <p>It is deliberately not a copy of {@code StoredTeamManager}. What it shares is the contract, and
 * the parts it does not share are the parts that need a server: the id's timestamp, the events, and
 * the store. Two implementations that agree because they were written from the same interface are the
 * point — the moment they are kept in step by hand, this has stopped earning its place.
 *
 * <p>{@code LinkedHashMap} rather than a sorted map, on purpose: insertion order is exactly the order
 * that is <i>not</i> a stable listing, so {@code TeamManager.allTeams}' sort has something to sort and
 * a test can tell the sorted answer from the raw one.
 */
final class MapTeamManager implements MutableTeamManager {

    private final Map<UUID, Team> teams = new LinkedHashMap<>();
    private final String name;

    MapTeamManager() {
        this("map");
    }

    MapTeamManager(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Collection<Team> teams() {
        return List.copyOf(teams.values());
    }

    @Override
    public Optional<Team> byId(UUID id) {
        return Optional.ofNullable(teams.get(id));
    }

    @Override
    public Team create(String name, UUID owner) {
        if (realTeamOf(owner).isPresent()) {
            throw new IllegalStateException("That player is already in a team; leave it first.");
        }
        Team team = Team.created(UUID.randomUUID(), name, owner, 0L);
        teams.put(team.id(), team);
        return team;
    }

    @Override
    public boolean invite(UUID teamId, UUID player) {
        Team team = teams.get(teamId);
        if (team == null || team.isMember(player) || team.isInvited(player)) {
            return false;
        }
        teams.put(teamId, team.withInvite(player));
        return true;
    }

    @Override
    public Optional<Team> acceptInvite(UUID player) {
        Optional<Team> target = invitesFor(player).stream().findFirst();
        if (target.isEmpty()) {
            return Optional.empty();
        }

        // Straight to the same helper leave uses, because "accepting while in a team" and "leaving"
        // have to agree about what happens when the last member goes. The stored manager makes the
        // same call for the same reason.
        realTeamOf(player).ifPresent(current -> depart(current, player));

        Team joined = target.get().withMember(player, TeamRole.MEMBER);
        teams.put(joined.id(), joined);
        return Optional.of(joined);
    }

    @Override
    public boolean leave(UUID player) {
        Optional<Team> current = realTeamOf(player);
        if (current.isEmpty()) {
            return false;
        }
        depart(current.get(), player);
        return true;
    }

    @Override
    public boolean kick(UUID actor, UUID target) {
        Optional<Team> found = realTeamOf(target);
        if (found.isEmpty()) {
            return false;
        }
        Team team = found.get();
        if (!team.isMember(actor) || !team.roleOrMember(actor).outranks(team.roleOrMember(target))) {
            return false;
        }
        depart(team, target);
        return true;
    }

    @Override
    public boolean disband(UUID actor, UUID teamId) {
        Team team = teams.get(teamId);
        if (team == null || !team.owner().equals(actor)) {
            return false;
        }
        teams.remove(teamId);
        return true;
    }

    /** One departure, so that leaving, being kicked and accepting elsewhere cannot drift apart. */
    private void depart(Team team, UUID player) {
        Team after = team.withoutMember(player);
        if (after.members().isEmpty()) {
            teams.remove(team.id());
        }
        else {
            teams.put(team.id(), after);
        }
    }
}
