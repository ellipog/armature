package dev.ellipog.armature.api.teams;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The teams on one server — whoever is actually providing them.
 *
 * <p>This is an interface rather than a class because Armature is not the only thing that can hold a
 * party. A third-party parties mod already has the teams, the invites and the roles, and a player who
 * runs one has already decided where their party lives. So a caller asks for {@link Teams#of} and gets
 * whichever source this server resolved to, without ever naming one. See
 * {@code impl/teams/TeamProviders} for how that choice is made and what it prefers.
 *
 * <h2>What every implementation must answer, and what it may refuse</h2>
 *
 * <p><b>Reading is mandatory.</b> {@link #teams}, {@link #byId} and {@link #name} are abstract: any
 * source of teams can list them. {@link #teamOf}, {@link #realTeamOf}, {@link #invitesFor},
 * {@link #allTeams} and {@link #teamCount} are default methods written on top of those three, so an
 * implementation that can only read gets the whole read surface for nothing.
 *
 * <p><b>Writing is optional, and refusing is not a failure.</b> The six structural mutators throw
 * {@link UnsupportedOperationException} by default, naming the manager in the message. That is
 * deliberate rather than lazy: a read-only source is a legitimate answer — a mod can expose parties
 * that its own commands create — and the alternative to a loud refusal is a silent no-op, which
 * presents as "the button does nothing". A caller that needs to write asks
 * {@link #managesMembership()} first, and one that needs to be told about somebody else's changes
 * asks {@link #firesEvents()}.
 *
 * <p>Implementations that <i>do</i> write should implement {@link MutableTeamManager} instead, which
 * redeclares those six as abstract so "this one really can" is checkable at compile time rather than
 * a promise in a javadoc.
 *
 * <h2>Beyond the six: named capabilities, because sources differ</h2>
 *
 * <p>{@link #rename}, {@link #transferOwnership}, {@link #setPolicy}, {@link #joinPublic},
 * {@link #declineInvite} and {@link #cancelInvite} are newer operations a source may or may not be
 * able to offer — Open Parties and Claims cannot rename a party at all, and FTB Teams is read-only
 * here by design. They throw by default like the six, but "can this source do it" is a different
 * question from "does this source write at all", so it is asked with {@link #supports} rather than
 * folded into {@link #managesMembership()}. A caller offers a control only where the answer is yes;
 * a control that can only ever be refused is worse than a missing one.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method here is called on the server thread, and a third-party implementation may assume
 * that. Armature's own resolution runs on the server thread for exactly this reason — see
 * {@code TeamProviders}.
 */
public interface TeamManager {

    // ------------------------------------------------------------------
    // The three a source has to answer
    // ------------------------------------------------------------------

    /**
     * Every team, in whatever order this source finds natural.
     *
     * <p>Deliberately not sorted here: a source that has a real order — creation order, a file's own
     * order — should be free to give it, and {@link #allTeams} is the method for a caller who wants
     * one that reads the same way twice.
     */
    Collection<Team> teams();

    /** The team with this id, if there is one. Solo teams are synthesised, never found. */
    Optional<Team> byId(UUID id);

    /**
     * What is providing these teams, for a log line and for the refusal message above.
     *
     * <p>Short and lowercase: {@code "stored"}, {@code "ftbteams"}, {@code "openpartiesandclaims"}.
     * It is an id rather than a sentence because it ends up in both.
     */
    String name();

    // ------------------------------------------------------------------
    // Reads, built on the three above
    // ------------------------------------------------------------------

    /**
     * The team a player is in — their real team, or a solo team of one.
     *
     * <p>Never empty, which is the point: a caller does not have to decide what to do about a player
     * with no team, because there is no such player.
     */
    default Team teamOf(UUID player) {
        return realTeamOf(player).orElseGet(() -> Team.solo(player));
    }

    /**
     * The real team a player is in, if any. Empty for a solo player.
     *
     * <p>The default walks {@link #teams}. An implementation with a direct lookup should override this,
     * because a player who is in no team is the common case and the walk finds nothing.
     */
    default Optional<Team> realTeamOf(UUID player) {
        for (Team team : teams()) {
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
    default List<Team> invitesFor(UUID player) {
        return teams().stream().filter(team -> team.isInvited(player)).toList();
    }

    /**
     * Every team, sorted by name so a listing reads the same way twice.
     *
     * <p>The tie-break on id is not decoration. Team names are not unique — two players both called
     * their party "the crew" — and a sort that leaves equal names in the source's own order makes the
     * listing depend on something the caller cannot see, which is the difference between a stable
     * answer and one that changes when the source changes how it iterates.
     */
    default Collection<Team> allTeams() {
        return teams().stream()
                .sorted(Comparator.comparing((Team team) -> team.name().toLowerCase(Locale.ROOT))
                        .thenComparing(Team::id))
                .toList();
    }

    default int teamCount() {
        return teams().size();
    }

    // ------------------------------------------------------------------
    // Writes, refused by default
    // ------------------------------------------------------------------

    /**
     * Forms a team, with {@code owner} as its only member.
     *
     * @throws UnsupportedOperationException unless this source can write
     * @throws IllegalStateException if the owner is already in a team
     */
    default Team create(String name, UUID owner) {
        throw unsupported("create");
    }

    /** Invites a player. False if they are already in the team or already invited. */
    default boolean invite(UUID teamId, UUID player) {
        throw unsupported("invite");
    }

    /** @return the team they joined, or empty if there was no invitation to accept */
    default Optional<Team> acceptInvite(UUID player) {
        throw unsupported("acceptInvite");
    }

    /** @return true if a real team was left; false for a solo player, who has nothing to leave */
    default boolean leave(UUID player) {
        throw unsupported("leave");
    }

    /**
     * Removes somebody else.
     *
     * @return false if the actor has no authority over the target, which the caller should report
     *         rather than swallow
     */
    default boolean kick(UUID actor, UUID target) {
        throw unsupported("kick");
    }

    /** @return false if the actor is not the owner */
    default boolean disband(UUID actor, UUID teamId) {
        throw unsupported("disband");
    }

    // ------------------------------------------------------------------
    // Optional operations, one capability each
    // ------------------------------------------------------------------

    /**
     * Invites a player, on {@code actor}'s behalf.
     *
     * <p>The actor-aware form of {@link #invite(UUID, UUID)}, and the one a command should use: the
     * permission rule is {@link Team#canInvite}, which the policy switch feeds, and enforcing it needs
     * to know who is asking. A source that cannot tell who may invite (a foreign parties mod owns
     * that rule) refuses when the actor is not a member at all, and leaves the rest to itself.
     *
     * @return false when the actor may not invite, the target is already in or invited, or the team
     *         is at {@link #memberLimit()}
     */
    default boolean invite(UUID actor, UUID teamId, UUID player) {
        throw unsupported("invite");
    }

    /**
     * Accepts one specific invitation.
     *
     * <p>A player can hold invitations from several parties, so the untargeted
     * {@link #acceptInvite(UUID)} picking the first is a convenience rather than the whole story: a
     * panel's Accept button sits on one invitation's row and must mean <i>that</i> one, or a click on
     * the second row joins the first row's party.
     *
     * @return the team they joined, or empty when there is no invitation for that team, or the team
     *         filled up before they answered
     */
    default Optional<Team> acceptInvite(UUID player, UUID teamId) {
        throw unsupported("acceptInvite");
    }

    /**
     * Renames a team. Owner only.
     *
     * <p>{@link TeamLimits#isValidName} decides what a name may be; the manager applies the same rule
     * the command reports, so "that name is too long" and the refusal cannot disagree.
     *
     * @return false when the actor is not the owner, the team is gone, or the name is not one a party
     *         may be given. True includes the no-op case of renaming to the name it already has
     */
    default boolean rename(UUID actor, UUID teamId, String name) {
        throw unsupported("rename");
    }

    /**
     * Hands ownership to another member. The previous owner becomes an ordinary member.
     *
     * @return false unless {@link Team#canTransfer} says the actor may and the target is a member
     */
    default boolean transferOwnership(UUID actor, UUID teamId, UUID target) {
        throw unsupported("transferOwnership");
    }

    /**
     * Changes what members may do. Owner only.
     *
     * @return false when the actor is not the owner or the team is gone
     */
    default boolean setPolicy(UUID actor, UUID teamId, TeamPolicy policy) {
        throw unsupported("setPolicy");
    }

    /**
     * Joins a public team, without an invitation.
     *
     * <p>The one path that does not start from an invitation, so it carries the checks invitations
     * make implicit: the party must actually be open ({@link TeamPolicy#openJoin}), there must be
     * room, and the player must not already be in a real team — joining is for the solo screen, and
     * silently moving somebody out of their current party is the one behaviour this refuses.
     *
     * @return the team they joined, or empty when any of the three conditions fails
     */
    default Optional<Team> joinPublic(UUID teamId, UUID player) {
        throw unsupported("joinPublic");
    }

    /**
     * Declines an invitation. The invitee's own act, so no actor parameter.
     *
     * @return true if there was an invitation from that team to decline
     */
    default boolean declineInvite(UUID player, UUID teamId) {
        throw unsupported("declineInvite");
    }

    /**
     * Withdraws an invitation before it is answered.
     *
     * <p>The inviter's side of {@link #declineInvite}. Permitted to anyone who may invite —
     * {@link Team#canInvite} — so an officer can clean up their own invitation, and the owner can
     * always clean up anybody's. The person who sent this particular invitation may always withdraw
     * it, including after a policy change took their permission away.
     *
     * @return false when the target is not invited or the actor may not invite
     */
    default boolean cancelInvite(UUID actor, UUID teamId, UUID target) {
        throw unsupported("cancelInvite");
    }

    // ------------------------------------------------------------------
    // What a caller may assume
    // ------------------------------------------------------------------

    /**
     * Whether the six mutators do anything other than throw.
     *
     * <p>False by default, and {@link MutableTeamManager} fixes it to true. A method rather than
     * {@code instanceof MutableTeamManager} because the interface is what a caller holds, and asking
     * a question of the interface is honest about the fact that the answer is per-implementation.
     */
    default boolean managesMembership() {
        return false;
    }

    /**
     * Whether this source can perform an optional operation.
     *
     * <p>False by default, and deliberately not tied to {@link #managesMembership()}: Open Parties
     * and Claims writes the structural six but cannot rename a party, and a source that reads but
     * cannot write may still be able to answer. The caller that draws a control asks this first —
     * see {@link TeamFeature} for what each value gates and why a capability exists at all.
     */
    default boolean supports(TeamFeature feature) {
        return false;
    }

    /**
     * How many members a team may hold, or <b>zero</b> when the source cannot say.
     *
     * <p>Zero rather than a large number, because the two mean different things to a caller drawing
     * a header: {@code 3/8} where the limit is known, {@code 3 members} where it is not. A source
     * with its own limit — a foreign parties mod — answers zero here and refuses a join itself when
     * it wants to.
     */
    default int memberLimit() {
        return 0;
    }

    /**
     * Whether membership changes that this source did not route through Armature still reach
     * {@link TeamEvents}.
     *
     * <p>This is the field that decides whether a shared-progress feature works on a server running a
     * parties mod, and it is false by default because the default source — Armature's own — fires the
     * events itself from its own mutations, so a listener always runs. An adapter over somebody else's
     * parties mod can only be honest about this if it has bridged that mod's own events, which takes
     * real work per mod and cannot be assumed.
     *
     * <p><b>A false here is not a bug and must not be treated as one.</b> It means "a party formed
     * through that mod's own commands and screens will not tell you", so a listener cannot be the only
     * thing keeping state correct.
     */
    default boolean firesEvents() {
        return false;
    }

    /**
     * The refusal, in one place so every implementation words it the same way.
     *
     * <p>Names the manager, because the useful question when this arrives in a log is "which one is
     * this server using" — and the answer is not otherwise in the stack trace.
     */
    private UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException("the '" + name() + "' team manager cannot "
                + operation + " — it provides teams it does not own. Use managesMembership() or "
                + "supports(TeamFeature) to ask before calling a mutator.");
    }
}
