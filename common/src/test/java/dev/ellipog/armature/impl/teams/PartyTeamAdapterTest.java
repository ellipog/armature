package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamInvite;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapping, which is the part of an adapter that actually has bugs in it.
 *
 * <h2>Why this needs no mod installed, and why that is the point</h2>
 *
 * <p>{@link PartyTeamAdapter} is the piece that turns somebody else's party into a {@link Team} —
 * deciding what a rank means, what happens for a player in no party, whether the owner is
 * believable. Those are questions about <i>meaning</i> rather than about anybody's API, and every one
 * of them can be answered wrongly in a way that still looks like a working adapter: a party
 * silently reporting the wrong owner is not a crash, it is a bug somebody reports a month later.
 *
 * <p>So the deciding lives here, behind {@link PartySource}, and this test drives it with a fake
 * source. No FTB Teams, no Open Parties and Claims, no server, no world. That is the same argument as
 * {@code check_kit.py}'s game-free layout half: <b>a class that needs a running game cannot be
 * asserted on</b>, and the way to fix that is to move the arithmetic, not to try harder to look at
 * it.
 *
 * <p>What this therefore does <i>not</i> cover is the per-mod file that reads the real objects —
 * {@code FtbTeams} and {@code OpenPacTeams}. Those need their mod on the classpath and, for a party to
 * exist at all, a player to have formed one. See TESTING.md for what was and was not seen on a real
 * boot, said plainly rather than implied by this file's existence.
 */
class PartyTeamAdapterTest {

    private static final String MOD_ID = "someteamsmod";

    @Test
    @DisplayName("every party is mapped, in the order the source gave them")
    void everyPartyIsMappedInSourceOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        FakeParties source = new FakeParties()
                .with(party(first, "alpha", UUID.randomUUID()))
                .with(party(second, "beta", UUID.randomUUID()));

        TeamManager adapter = new PartyTeamAdapter(MOD_ID, source, false);

        // Order preserved, not sorted: this is `teams()`, and sorting is `allTeams()`'s job. An
        // adapter that sorted here would make the two indistinguishable, and a caller that wanted the
        // source's own order would have no way to ask for it.
        assertEquals(List.of(first, second),
                adapter.teams().stream().map(Team::id).toList(),
                "teams() maps the source's list and does not reorder it");

        // And asking twice gives the same thing, because the mapping is a pure function of the
        // source's snapshot rather than a view that mutates underneath the caller.
        assertEquals(adapter.teams(), adapter.teams());
    }

    @Test
    @DisplayName("the mapping copies every field, and reports the two a foreign party cannot know")
    void theMappingCopiesEveryField() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID officer = UUID.randomUUID();
        UUID invited = UUID.randomUUID();

        Map<UUID, TeamRole> members = new LinkedHashMap<>();
        members.put(owner, TeamRole.OWNER);
        members.put(officer, TeamRole.OFFICER);

        PartySource.Party source = new PartySource.Party(id, "the crew", owner, members, Set.of(invited));
        Team mapped = PartyTeamAdapter.toTeam(source);

        assertEquals(id, mapped.id());
        assertEquals("the crew", mapped.name());
        assertEquals(owner, mapped.owner());
        assertEquals(members, mapped.members());
        // A foreign invitation is a bare id: the owner stands in as the sender and zero as the time,
        // because the source publishes neither -- see TeamInvite on why zero rather than a made-up
        // time. The policy is the same kind of answer: this source has no policy API to read.
        assertEquals(Map.of(invited, new TeamInvite(owner, 0L)), mapped.invites());
        assertEquals(TeamPolicy.DEFAULT, mapped.policy());

        // The two honest unknowns, asserted rather than left to a comment. `persistent` is true
        // because a party read out of somebody's store is a real team, whatever produced it, so there
        // is no synthesised case for a caller to distinguish. `createdAt` is zero because no parties
        // mod exposes a formation time, and zero is a stable answer that is simply not a useful one,
        // where a made-up timestamp would look authoritative.
        assertTrue(mapped.persistent(), "a mapped party is a real team and says so");
        assertEquals(0L, mapped.createdAt(), "and carries no invented creation time");

        // `persistent` is the discriminator, and for a mapped party it is true even when the party has
        // exactly one member -- which is the whole reason `Team.isSolo()` was deleted. It asked
        // `members.size() <= 1`, so it would have called this party solo. Size and storedness are
        // different questions.
        PartySource.Party alone = new PartySource.Party(id, "just me", owner,
                Map.of(owner, TeamRole.OWNER), Set.of());
        assertEquals(1, PartyTeamAdapter.toTeam(alone).size(),
                "a one-member party has a size of one");
        assertTrue(PartyTeamAdapter.toTeam(alone).persistent(),
                "and is still a real stored party, which `members.size() <= 1` could not have told you");
    }

    @Test
    @DisplayName("a party is found by id, and an unknown id is empty rather than a new party")
    void byIdFindsOrReportsNothing() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        TeamManager adapter = new PartyTeamAdapter(MOD_ID,
                new FakeParties().with(party(id, "alpha", UUID.randomUUID())), false);

        assertEquals(id, adapter.byId(id).orElseThrow().id());
        assertTrue(adapter.byId(other).isEmpty(),
                "an id nobody has must not resolve to something -- a caller using byId to decide " +
                        "whether a stored key is still live depends on this being empty");
    }

    @Test
    @DisplayName("realTeamOf asks the source's index, and reports empty rather than synthesising")
    void realTeamOfDelegatesAndDoesNotSynthesise() {
        UUID member = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();
        FakeParties source = new FakeParties().with(party(partyId, "alpha", member));
        PartyTeamAdapter adapter = new PartyTeamAdapter(MOD_ID, source, false);

        assertEquals(partyId, adapter.realTeamOf(member).orElseThrow().id());
        assertEquals(1, source.forMemberCalls,
                "the direct lookup, not a walk over every party -- 'which team is this player in' is " +
                        "asked constantly by anything keying state by a team");

        // The assertion that matters most in this file. `realTeamOf` means *the real team they are
        // in*, and a lone player is in none. An implementation that synthesised a solo team here
        // instead would make every player persistent, put every player in every team listing, and
        // hand a stranger's progress to whoever asked -- and it would look like it worked, because
        // `teamOf` would still return something.
        assertTrue(adapter.realTeamOf(stranger).isEmpty(),
                "a player in no party is in no party, and this is the method that says so");

        // The synthesis belongs to `teamOf`, which is the interface's own default and the whole
        // reason a caller needs no branch for "no team".
        Team solo = adapter.teamOf(stranger);
        assertEquals(stranger, solo.id(), "synthesised from the player's own uuid");
        assertFalse(solo.persistent(), "and marked as not stored, so it never reaches a listing");
    }

    @Test
    @DisplayName("an adapter over somebody else's parties is read-only, and refuses all six by name")
    void anAdapterIsReadOnly() {
        PartyTeamAdapter adapter = new PartyTeamAdapter(MOD_ID, new FakeParties(), true);
        UUID actor = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        // This is the design decision the brief turns on: the FTB adapter implements TeamManager, not
        // MutableTeamManager, because forming a party *for* a player inside that mod's model produces
        // a party they did not know they had. So the flag is false and all six refuse.
        assertFalse(adapter.managesMembership(),
                "a reader does not manage membership, and says so before a caller tries");

        assertRefuses(adapter, "create", () -> adapter.create("party", actor));
        assertRefuses(adapter, "invite", () -> adapter.invite(actor, other));
        assertRefuses(adapter, "acceptInvite", () -> adapter.acceptInvite(other));
        assertRefuses(adapter, "leave", () -> adapter.leave(other));
        assertRefuses(adapter, "kick", () -> adapter.kick(actor, other));
        assertRefuses(adapter, "disband", () -> adapter.disband(actor, actor));
    }

    @Test
    @DisplayName("firesEvents is exactly what the adapter was told, in both directions")
    void firesEventsIsWhatItWasTold() {
        // Not a constant, and this is why. It is the one fact about an adapter that cannot be read off
        // the mapping: whether the per-mod file actually bridged that mod's own events. FTB's adapter
        // passes true because it registered its two party events; OPAC's passes false because OPAC
        // hands out a listener manager for claims and none for parties. A caller that cannot tell the
        // difference would treat a silent source as a chatty one and keep state that never updates.
        assertTrue(new PartyTeamAdapter(MOD_ID, new FakeParties(), true).firesEvents(),
                "a bridged adapter says it announces changes");
        assertFalse(new PartyTeamAdapter(MOD_ID, new FakeParties(), false).firesEvents(),
                "and an unbridged one does not");

        // manageMembership is NOT implied by either, which is the reason the two are separate methods.
        assertFalse(new PartyTeamAdapter(MOD_ID, new FakeParties(), true).managesMembership(),
                "announcing somebody else's changes does not mean you can make them");
    }

    @Test
    @DisplayName("the adapter reports the mod id it was built for")
    void nameIsTheModId() {
        assertEquals(MOD_ID, new PartyTeamAdapter(MOD_ID, new FakeParties(), false).name(),
                "the name goes into the resolution log and into every refusal message, so it is the " +
                        "mod's id rather than a sentence");
    }

    // ------------------------------------------------------------------

    private static PartySource.Party party(UUID id, String name, UUID owner) {
        return new PartySource.Party(id, name, owner,
                Map.of(owner, TeamRole.OWNER), Set.of());
    }

    private static void assertRefuses(TeamManager manager, String operation, Runnable call) {
        UnsupportedOperationException thrown =
                assertThrows(UnsupportedOperationException.class, call::run,
                        "a read-only adapter must refuse " + operation + " rather than do nothing");

        String message = thrown.getMessage() == null ? "" : thrown.getMessage();
        assertTrue(message.contains(MOD_ID),
                () -> "the refusal should name the source, because a log is where this arrives and the "
                        + "stack trace does not say which one. It said: " + message);
        assertTrue(message.contains(operation),
                () -> "and name the operation. It said: " + message);
    }

    /**
     * A party source with no mod behind it, which is the whole reason {@link PartySource} exists.
     *
     * <p>It counts {@code forMember} calls, because one of the properties worth pinning is that the
     * adapter uses the source's index rather than walking every party — a fact no assertion about the
     * returned value can show.
     */
    private static final class FakeParties implements PartySource {

        private final Map<UUID, Party> parties = new LinkedHashMap<>();
        private int forMemberCalls;

        FakeParties with(Party party) {
            parties.put(party.id(), party);
            return this;
        }

        @Override
        public Collection<Party> parties() {
            return List.copyOf(parties.values());
        }

        @Override
        public Optional<Party> forMember(UUID player) {
            forMemberCalls++;
            return parties.values().stream()
                    .filter(party -> party.members().containsKey(player))
                    .findFirst();
        }
    }
}
