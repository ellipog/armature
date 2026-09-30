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
 * <p><b>Writing is optional, and refusing is not a failure.</b> The six mutators throw
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
                + operation + " — it provides teams it does not own. Use managesMembership() to ask "
                + "before calling a mutator.");
    }
}
