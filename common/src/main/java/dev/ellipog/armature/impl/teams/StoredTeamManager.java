package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.teams.MutableTeamManager;
import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamEvents;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.api.teams.TeamRole;

import net.minecraft.server.MinecraftServer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Armature's own teams: the queries, the mutations, and the events.
 *
 * <h2>What it is now, and what it was</h2>
 *
 * <p>This was {@code TeamManager}, and it owned the <i>name</i> as well as the behaviour. Teams are
 * no longer necessarily Armature's: a server running a parties mod has parties already, and the whole
 * point of {@link TeamProviders} is that a caller asking for teams gets whichever of them this server
 * resolved to. So the interface took the name — {@code api/teams/TeamManager} — and this class is
 * what that interface looks like when the answer is a {@code SavedData} in the world.
 *
 * <p>It is the <b>fallback</b>, and the one that is always available: a server with nothing else
 * installed, and every test JVM, resolves here. Which is what makes it safe for {@code TeamProviders}
 * to place it last and treat it as unconditionally present.
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
 *
 * <p>The reads — {@code teamOf}, {@code realTeamOf}, {@code invitesFor}, {@code allTeams},
 * {@code teamCount} — are no longer written here. They are default methods on
 * {@code TeamManager}, which is the point of the split: a source that can only read gets the whole
 * read surface for nothing, and this class does not carry a second copy of it.
 */
public final class StoredTeamManager implements MutableTeamManager {

    /** What {@link #name()} returns, and what the resolution log names. */
    public static final String NAME = "stored";

    private final MinecraftServer server;
    private final TeamStore store;

    private StoredTeamManager(MinecraftServer server, TeamStore store) {
        this.server = server;
        this.store = store;
    }

    /** The manager for this server, finding or creating the store. */
    public static StoredTeamManager of(MinecraftServer server) {
        return new StoredTeamManager(server, TeamStore.of(server));
    }

    /**
     * This manager as something {@link TeamProviders} can consider.
     *
     * <p>The presence test is {@code true} unconditionally, and that is not laziness: a store is
     * created on demand in the overworld's data storage and needs nothing installed. So this is the
     * source that cannot decline, which is what lets the resolution chain end here rather than
     * needing a special case for "nothing was chosen".
     */
    public static TeamProvider provider() {
        return new TeamProvider() {
            @Override
            public String id() {
                return NAME;
            }

            @Override
            public boolean isPresent(MinecraftServer server) {
                return true;
            }

            /**
             * True, and it is the only source for which this is true.
             *
             * <p>Which is the point: this source answers on every server, so naming it in the
             * "another source is present and losing" warning would fire on every server and tell the
             * operator about two parties mods they do not have. See {@link TeamProvider#isFallback}.
             */
            @Override
            public boolean isFallback() {
                return true;
            }

            @Override
            public TeamManager create(MinecraftServer server2) {
                return of(server2);
            }
        };
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Collection<Team> teams() {
        return store.all();
    }

    @Override
    public Optional<Team> byId(UUID id) {
        return store.byId(id);
    }

    /**
     * True, and it is true on behalf of its own mutations only.
     *
     * <p>Every write below fires the matching {@link TeamEvents} event, so a listener on this manager
     * sees everything that happens through it. What it cannot do is see a change made by something
     * else, because nothing else can change this store — which is a stronger position than it sounds,
     * since it means {@code false} here is only ever about a foreign source.
     */
    @Override
    public boolean firesEvents() {
        return true;
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
    @Override
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

    @Override
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
     * {@link TeamEvents.Reason#LEFT} so that anything they were carrying, like saved progress, knows
     * to deal with it. Doing that silently is how a player ends up with progress in two teams.
     */
    @Override
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
     */
    @Override
    public boolean leave(UUID player) {
        Optional<Team> current = realTeamOf(player);
        if (current.isEmpty()) {
            return false;
        }
        return depart(current.get(), player, TeamEvents.Reason.LEFT);
    }

    @Override
    public boolean kick(UUID actor, UUID target) {
        Optional<Team> teamOfTarget = realTeamOf(target);
        if (teamOfTarget.isEmpty()) {
            return false;
        }
        Team team = teamOfTarget.get();

        // The rule is the team's, not this method's. It used to be written out here as three
        // conditions, and it was the *only* copy -- so a command wanting to say why it refused, or a
        // panel wanting to know whether to draw a Remove button, had nowhere to ask and would have
        // restated it. See Team.canActOn for the three readers and why that matters.
        if (!team.canActOn(actor, target)) {
            return false;
        }
        return depart(team, target, TeamEvents.Reason.KICKED);
    }

    @Override
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
