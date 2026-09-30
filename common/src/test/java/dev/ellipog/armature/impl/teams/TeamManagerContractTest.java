package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract every source of teams is held to, asserted against the two shapes it can take.
 *
 * <h2>What is being tested, and why it is the interface rather than an implementation</h2>
 *
 * <p>{@code TeamManager} was extracted out of a concrete class so that teams could come from somewhere
 * other than Armature's own store. That makes the interface the thing two very different
 * implementations have to agree about — a {@code SavedData} in the world, and a party already owned by
 * somebody else's mod — and it makes the answers below the ones that matter:
 *
 * <ul>
 *   <li>a player on their own is in a team of one, keyed by their own id, so no caller needs a branch
 *       for "no team";</li>
 *   <li>a source that only reads is a legitimate source, and says so by throwing a message that names
 *       it rather than by silently doing nothing;</li>
 *   <li>a listing reads the same way twice, whatever order the source happens to iterate in.</li>
 * </ul>
 *
 * <p>All three are properties of the <i>interface's own</i> default methods and of the contract they
 * document, so they are asserted against the defaults — a {@link ReadOnlyTeamManager} for the first
 * two, and {@link MapTeamManager} for the third, whose insertion order is the one thing its
 * {@code LinkedHashMap} can guarantee is unstable.
 */
class TeamManagerContractTest {

    @Test
    @DisplayName("a player on their own is a team of one, keyed by their own UUID")
    void aSoloPlayerIsATeamOfOneKeyedByTheirOwnId() {
        UUID player = UUID.randomUUID();
        TeamManager manager = new ReadOnlyTeamManager();

        Team solo = manager.teamOf(player);

        // The id IS the player's -- see Team.solo, and the javadoc on Team's `id` field. It is what
        // makes a lone player's state addressable without storing anything, and it is the reason
        // "playing alone" and "playing in a party" are one code path rather than two.
        assertEquals(player, solo.id(), "a solo team's id is the player's own uuid");

        // And it is synthesised, not stored -- `persistent` is the discriminator. That is the whole of
        // why Team.isSolo had to go: it asked `members.size() <= 1`, which answers *true* for a brand
        // new stored team of one, so it called a real team solo.
        //
        // The two questions are not the same question, and this asserts that they differ. A test for
        // the other reading would be the one below: a stored team of one is persistent and has a size
        // of one, and the pair of assertions is what makes "persistent is the discriminator" mean
        // something rather than being a comment.
        assertFalse(solo.persistent(), "a solo team is synthesised and must not claim to be stored");
        assertEquals(1, solo.size(), "it is a team of exactly one, which is why size could not be the "
                + "discriminator: a stored team of one has the same size and is a real team");
    }

    @Test
    @DisplayName("a real team wins over the solo fallback, and empties back into it")
    void aRealTeamWinsOverTheSoloFallback() {
        MapTeamManager manager = new MapTeamManager();
        UUID owner = UUID.randomUUID();

        Team created = manager.create("the crew", owner);

        // The real team, not the solo fallback -- and asserted on the whole record, so a field the
        // fallback happens to share cannot make this pass.
        assertEquals(created, manager.teamOf(owner),
                "once in a real team, that team is what teamOf answers");
        assertTrue(manager.teamOf(owner).persistent(), "and it says it is stored");
        assertEquals(1, manager.teamOf(owner).size(),
                "with a size of one -- the same size as the solo player above, and unmistakably not the "
                        + "same thing. That pair is the reason `persistent` had to become the "
                        + "discriminator rather than `members.size() <= 1`.");

        assertTrue(manager.leave(owner), "and leaving it is a real departure");
        assertFalse(manager.teamOf(owner).persistent(),
                "after which the same player is a solo team again -- the fallback is not a state, it is " +
                "what is left when the source has nothing");
        assertEquals(owner, manager.teamOf(owner).id(),
                "still keyed by their own id, so nothing they did alone is orphaned");
    }

    @Test
    @DisplayName("a read-only manager refuses all six mutators, naming itself")
    void aReadOnlyManagerRefusesAllSixMutators() {
        TeamManager manager = new ReadOnlyTeamManager();
        UUID actor = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        // First, the two flags, which are how a caller is supposed to find this out before it tries.
        assertFalse(manager.managesMembership(), "a read-only source does not manage membership");
        assertFalse(manager.firesEvents(), "and it has not bridged anybody's events, so it says so");

        // Then all six, because 'the button does nothing' is the failure mode the throw exists to
        // prevent -- a silent no-op would present to a player as a command that worked.
        assertRefuses(manager, "create", () -> manager.create("party", actor));
        assertRefuses(manager, "invite", () -> manager.invite(actor, other));
        assertRefuses(manager, "acceptInvite", () -> manager.acceptInvite(other));
        assertRefuses(manager, "leave", () -> manager.leave(other));
        assertRefuses(manager, "kick", () -> manager.kick(actor, other));
        assertRefuses(manager, "disband", () -> manager.disband(actor, actor));
    }

    @Test
    @DisplayName("a mutable manager says so, and so does a bridged one")
    void aMutableManagerSaysSo() {
        TeamManager mutable = new MapTeamManager();
        assertTrue(mutable.managesMembership(),
                "MutableTeamManager fixes this to true, so an implementation cannot get it wrong");

        // firesEvents is the one flag that is NOT implied by being able to write. The stored manager
        // fires Armature's own events from its own mutations; this double does not fire any, so the
        // interface's default of false is the correct answer for it. That asymmetry is the reason the
        // two are separate methods rather than one.
        assertFalse(mutable.firesEvents(),
                "a manager that can write is not thereby a manager that announces somebody else's writes");
    }

    @Test
    @DisplayName("a listing reads the same way twice, whatever order the source iterates in")
    void aListingIsStableRegardlessOfInsertionOrder() {
        MapTeamManager manager = new MapTeamManager();
        Team zulu = manager.create("zulu", UUID.randomUUID());
        Team alpha = manager.create("alpha", UUID.randomUUID());
        Team mike = manager.create("mike", UUID.randomUUID());

        assertEquals(List.of(zulu.id(), alpha.id(), mike.id()),
                manager.teams().stream().map(Team::id).toList(),
                "the source's own order is insertion order, which is exactly what a listing must not expose");

        List<UUID> first = manager.allTeams().stream().map(Team::id).toList();
        assertEquals(List.of(alpha.id(), mike.id(), zulu.id()), first, "allTeams sorts by name");
        assertEquals(first, manager.allTeams().stream().map(Team::id).toList(),
                "and asking twice gives the same answer");

        assertNotEquals(first, manager.teams().stream().map(Team::id).toList(),
                "so the sorted answer is distinguishable from the raw one, which is what makes the " +
                "assertion above mean something");
    }

    @Test
    @DisplayName("two teams with the same name are ordered by id, not by whatever the source felt like")
    void equalNamesStillHaveOneOrder() {
        MapTeamManager manager = new MapTeamManager();
        Team first = manager.create("the crew", UUID.randomUUID());
        Team second = manager.create("the crew", UUID.randomUUID());

        // Team names are not unique -- two players both called their party "the crew" -- and a sort
        // that leaves equal names in the source's own order makes the listing depend on something the
        // caller cannot see. UUID's natural order is arbitrary and stable, which is the whole point:
        // an arbitrary-but-stable choice is reproducible when somebody reports a bug.
        List<UUID> expected = List.of(first.id(), second.id()).stream().sorted().toList();
        assertEquals(expected, manager.allTeams().stream().map(Team::id).toList(),
                "equal names fall back to the id, which cannot change");
    }

    /**
     * Asserts one refusal, and asserts what it says.
     *
     * <p>The message is part of the contract: when this arrives in a log the useful question is which
     * source is in use, and the stack trace does not say. So the assertion is on the manager's own name
     * and on the operation, because a refusal that says "not supported" sends the reader to the
     * caller.
     */
    private static void assertRefuses(TeamManager manager, String operation, Runnable call) {
        UnsupportedOperationException thrown =
                assertThrows(UnsupportedOperationException.class, call::run,
                        "the read-only manager must refuse " + operation + " rather than do nothing");

        String message = thrown.getMessage() == null ? "" : thrown.getMessage();
        assertTrue(message.contains(manager.name()),
                () -> "the refusal for " + operation + " should name the manager '" + manager.name()
                        + "' so a log says which source is in use. It said: " + message);
        assertTrue(message.contains(operation),
                () -> "the refusal should name the operation '" + operation + "'. It said: " + message);
        assertTrue(message.contains("managesMembership"),
                () -> "and it should point at the question a caller can ask first. It said: " + message);
    }
}
