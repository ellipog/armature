package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.config.ArmatureConfig;
import dev.ellipog.armature.api.config.TeamSettings;
import dev.ellipog.armature.api.teams.MutableTeamManager;
import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamEvents;
import dev.ellipog.armature.api.teams.TeamFeature;
import dev.ellipog.armature.api.teams.TeamLimits;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.api.teams.TeamPolicy;
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

    /** True for every feature: this source owns its teams and has an implementation below for each. */
    @Override
    public boolean supports(TeamFeature feature) {
        return true;
    }

    /**
     * The configured cap, {@value dev.ellipog.armature.api.config.TeamSettings#MIN_MAX_MEMBERS} to
     * {@value dev.ellipog.armature.api.config.TeamSettings#MAX_MAX_MEMBERS}; eight unless the
     * server's settings file says otherwise.
     *
     * <p>Asked live on every call rather than captured when this manager was built, so a settings
     * re-read takes effect at the next check rather than at the next server — see
     * {@code ArmatureConfig}. The number a panel's header draws and the number these checks refuse
     * past are then the same number by construction, which is the whole reason the limit lives on
     * the manager interface instead of in a header.
     */
    @Override
    public int memberLimit() {
        return settings().maxMembers();
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
        if (!TeamLimits.isValidName(name)) {
            // The command validates first and reports nicely, so reaching here means a caller that
            // skipped the rule -- and a silently stored blank name displays as the owner, which reads
            // as a bug rather than as input.
            throw new IllegalArgumentException("'" + name + "' is not a name a party may be given.");
        }
        Team team = Team.created(UUID.randomUUID(), name.trim(), owner, server.overworld().getGameTime())
                // The server's defaults for the two policy switches, stamped on at creation. An
                // existing party's policy is the party's and is never revisited -- see TeamSettings.
                .withPolicy(settings().defaultPolicy());
        store.put(team);

        Constants.LOG.info("armature: team '{}' created by {}", name, owner);
        TeamEvents.TEAM_CREATED.invoker().onTeamCreated(server, team);
        return team;
    }

    /**
     * Invites, acting as the owner.
     *
     * <p>The actor-aware {@link #invite(UUID, UUID, UUID)} is the real one; this is the form that
     * predates it and it kept its meaning by acting on behalf of the person who can always invite.
     * A caller that knows who is asking should pass them and get the policy check.
     */
    @Override
    public boolean invite(UUID teamId, UUID player) {
        Optional<Team> found = store.byId(teamId);
        return found.isPresent() && invite(found.get().owner(), teamId, player);
    }

    @Override
    public boolean invite(UUID actor, UUID teamId, UUID player) {
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty()) {
            return false;
        }
        Team team = found.get();
        if (!team.canInvite(actor) || team.isMember(player) || team.isInvited(player)) {
            return false;
        }
        if (TeamLimits.isFull(team.size(), memberLimit())) {
            return false;
        }
        Team after = team.withInvite(player, actor, server.overworld().getGameTime());
        store.put(after);
        TeamEvents.INVITE_CHANGED.invoker()
                .onInviteChanged(server, after, player, TeamEvents.InviteKind.SENT);
        return true;
    }

    /**
     * Accepts the first invitation this player holds.
     *
     * <p>A convenience over the targeted form — a player can hold invitations from several parties,
     * and a panel's Accept button sits on one row and must mean that one. Kept because "accept
     * whatever is waiting" is the shape a command had first and a player at a chat prompt still
     * wants.
     */
    @Override
    public Optional<Team> acceptInvite(UUID player) {
        Optional<Team> target = invitesFor(player).stream().findFirst();
        return target.flatMap(team -> acceptInvite(player, team.id()));
    }

    /**
     * Accepts one specific invitation.
     *
     * <p>If the player is in a team already, they leave it first — which fires
     * {@link TeamEvents.Reason#LEFT} so that anything they were carrying, like saved progress, knows
     * to deal with it. Doing that silently is how a player ends up with progress in two teams.
     *
     * <p>The capacity check is repeated here even though the invitation was refused a place when it
     * was sent, because a party can fill up between the invitation and the answer — eight invitations
     * sent to a party of one, and only seven can accept.
     */
    @Override
    public Optional<Team> acceptInvite(UUID player, UUID teamId) {
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty() || !found.get().isInvited(player)) {
            return Optional.empty();
        }
        Team target = found.get();
        if (TeamLimits.isFull(target.size(), memberLimit())) {
            return Optional.empty();
        }

        realTeamOf(player).ifPresent(current -> depart(current, player, TeamEvents.Reason.LEFT));

        Team joined = target.withMember(player, TeamRole.MEMBER);
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

    /**
     * Renames, refusing anything {@link TeamLimits#isValidName} refuses.
     *
     * <p>The validation is here as well as in the command on purpose, and it is the second time this
     * file has made the same argument: the command says why, and the manager is what makes it true. A
     * name that only the command checked is a name a future caller stores blank.
     *
     * <p>A rename to the name the team already has returns true without an event — it is a successful
     * no-op, and a roster push for nothing is a panel rebuilding for nothing.
     */
    @Override
    public boolean rename(UUID actor, UUID teamId, String name) {
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty()) {
            return false;
        }
        Team team = found.get();
        if (!team.isOwner(actor) || !TeamLimits.isValidName(name)) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.equals(team.name())) {
            return true;
        }
        Team after = team.withName(trimmed);
        store.put(after);

        Constants.LOG.info("armature: team '{}' renamed to '{}' by {}", team.name(), trimmed, actor);
        TeamEvents.TEAM_RENAMED.invoker().onTeamRenamed(server, after);
        return true;
    }

    /**
     * Hands ownership to another member, demoting the actor.
     *
     * <p>The demotion is to {@link TeamRole#MEMBER} rather than OFFICER: a transfer made without
     * leaving should leave the previous owner ordinary, and a "former owner" rank that persists is a
     * second authority model nobody asked for. The successor picker's flow transfers and then leaves,
     * so the demotion is not observable there at all.
     */
    @Override
    public boolean transferOwnership(UUID actor, UUID teamId, UUID target) {
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty()) {
            return false;
        }
        Team team = found.get();
        if (!team.canTransfer(actor, target)) {
            return false;
        }
        Team after = team.withOwner(target, TeamRole.MEMBER);
        store.put(after);

        Constants.LOG.info("armature: team '{}' handed from {} to {}", team.name(), actor, target);
        TeamEvents.OWNER_TRANSFERRED.invoker().onOwnerTransferred(server, after, actor);
        return true;
    }

    @Override
    public boolean setPolicy(UUID actor, UUID teamId, TeamPolicy policy) {
        if (policy == null) {
            return false;
        }
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty()) {
            return false;
        }
        Team team = found.get();
        if (!team.isOwner(actor)) {
            return false;
        }
        if (team.policy().equals(policy)) {
            return true;
        }
        Team after = team.withPolicy(policy);
        store.put(after);

        Constants.LOG.info("armature: team '{}' policy is now {}", team.name(), policy);
        TeamEvents.POLICY_CHANGED.invoker().onPolicyChanged(server, after);
        return true;
    }

    /**
     * Joins an open party without an invitation.
     *
     * <p>Refuses a player already in a real team rather than moving them, and that is the deliberate
     * difference from {@link #acceptInvite}: an invitation is addressed to one player and accepting it
     * while in a party is a switch, while this is a browse-and-join list where a press must not
     * silently evacuate the party the player is already in.
     */
    @Override
    public Optional<Team> joinPublic(UUID teamId, UUID player) {
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Team team = found.get();
        if (!team.policy().openJoin() || team.isMember(player)) {
            return Optional.empty();
        }
        if (realTeamOf(player).isPresent()) {
            return Optional.empty();
        }
        if (TeamLimits.isFull(team.size(), memberLimit())) {
            return Optional.empty();
        }
        Team joined = team.withMember(player, TeamRole.MEMBER);
        store.put(joined);

        Constants.LOG.info("armature: {} joined open team '{}'", player, joined.name());
        TeamEvents.MEMBER_JOINED.invoker().onMemberJoined(server, joined, player);
        return Optional.of(joined);
    }

    @Override
    public boolean declineInvite(UUID player, UUID teamId) {
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty() || !found.get().isInvited(player)) {
            return false;
        }
        Team after = found.get().withoutInvite(player);
        store.put(after);
        TeamEvents.INVITE_CHANGED.invoker()
                .onInviteChanged(server, after, player, TeamEvents.InviteKind.DECLINED);
        return true;
    }

    /**
     * Withdraws an invitation.
     *
     * <p>Two permissions rather than one: anyone {@link Team#canInvite} may clean up the party's
     * invitations, and <b>the person who sent this one</b> may always withdraw their own — including
     * after the policy changed under them, which is the case a bare {@code canInvite} would strand.
     */
    @Override
    public boolean cancelInvite(UUID actor, UUID teamId, UUID target) {
        Optional<Team> found = store.byId(teamId);
        if (found.isEmpty()) {
            return false;
        }
        Team team = found.get();
        if (!team.isInvited(target)) {
            return false;
        }
        boolean invitedThem = team.inviteOf(target)
                .map(invite -> invite.inviter().equals(actor))
                .orElse(false);
        if (!team.canInvite(actor) && !invitedThem) {
            return false;
        }
        Team after = team.withoutInvite(target);
        store.put(after);
        TeamEvents.INVITE_CHANGED.invoker()
                .onInviteChanged(server, after, target, TeamEvents.InviteKind.CANCELLED);
        return true;
    }

    // ------------------------------------------------------------------

    /**
     * The settings in force, asked of the one holder rather than captured.
     *
     * <p>A method rather than a field for two reasons that agree: a field would freeze the file's
     * values at whatever moment a server happened to resolve its teams, and this class's store is
     * reached once per server while the settings are a property of the process. The defaults are
     * what answers before any file has been read, so a test JVM and a server whose entry point has
     * not run yet both get the documented behaviour rather than a null.
     */
    private static TeamSettings settings() {
        return ArmatureConfig.current().teams();
    }

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
