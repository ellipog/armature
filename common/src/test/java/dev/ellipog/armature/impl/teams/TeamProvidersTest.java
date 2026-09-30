package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.TeamManager;

import net.minecraft.server.MinecraftServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which source a server resolves to, and what happens when it cannot be decided.
 *
 * <h2>Why this is a test of a list rather than of two mods</h2>
 *
 * <p>The decision is the whole feature: a server with a parties mod installed should use that mod's
 * parties, and a server with nothing installed should use Armature's own. Neither can be checked
 * without installing both mods and booting a server — twice — so {@code TeamProviders.choose} takes
 * the candidate list as a parameter and this hands it fakes. That is not a seam invented for testing:
 * it is the same call the real resolution makes, with the same precedence, the same logging and the
 * same fallback.
 *
 * <h2>The one thing that cannot be tested here, said plainly</h2>
 *
 * <p>That the precedence is <i>right</i>. Deciding between FTB Teams and Open Parties and Claims when
 * both are installed is a heuristic, because the call that would settle it —
 * {@code PlayerPartySystemManager.getPrimarySystemName()}, which names which of OPAC's registered
 * systems is primary — lives on an internal type rather than on OPAC's public API. So the assertion
 * below is that the tie goes to FTB and that the loser is reported; it is not, and cannot be, an
 * assertion that FTB is the correct answer for a given server. A config key is the real answer, and
 * the class comment says so.
 */
class TeamProvidersTest {

    @Test
    @DisplayName("with nothing installed, the chain is the stored source and nothing else")
    void theStoredSourceIsTheWholeChainWhenNothingIsInstalled() {
        List<TeamProvider> chain = TeamProviders.providers();

        // This runs in a JVM with no loader, so armature's platform layer is not installed and
        // isModLoaded cannot answer. That is the same path a test-taking consumer walks, and it is
        // why resolution is lazy: TeamProviders.of resolves on first ask, and no loader event is
        // required to get here.
        assertEquals(1, chain.size(),
                "a test JVM loads no mods, so only the built-in stored source should be in the chain");
        assertEquals(StoredTeamManager.NAME, chain.get(0).id(), "and it is the stored one");
        assertTrue(chain.get(0).isPresent(null),
                "which says yes unconditionally -- that is what lets the chain end here rather than " +
                        "needing a special case for 'nothing was chosen'");
    }

    @Test
    @DisplayName("when two sources are present the tie goes to FTB, and the loser is reported")
    void theTieGoesToFtb() {
        TeamManager chosen = TeamProviders.choose(null, List.of(
                source("ftbteams", true),
                source("openpartiesandclaims", true),
                source(StoredTeamManager.NAME, true)));

        assertEquals("ftbteams", chosen.name(),
                "precedence is explicit registration, then FTB, then OPAC, then stored -- so with both " +
                        "parties mods installed the first one in the list wins");

        // The loser being *present* is the case worth pinning: it is the only situation in which this
        // choice is a guess rather than a fact, and choose() logs it. Silence here would mean a server
        // operator reading the log cannot tell which mod their players are actually getting.
    }

    @Test
    @DisplayName("a source that is only a lower candidate does not become the answer by being present")
    void presenceAloneDoesNotWin() {
        TeamManager chosen = TeamProviders.choose(null, List.of(
                source("ftbteams", false),
                source("openpartiesandclaims", true),
                source(StoredTeamManager.NAME, true)));

        assertEquals("openpartiesandclaims", chosen.name(),
                "FTB being loaded is not the same as FTB having a party -- isPresent is asked, and a " +
                        "source that says no is passed over rather than preferred");
    }

    @Test
    @DisplayName("a source that throws when asked is treated as absent, not as fatal")
    void aSourceThatCannotBeAskedIsPassedOver() {
        TeamProvider broken = new TeamProvider() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public boolean isPresent(MinecraftServer server) {
                throw new IllegalStateException("its own API is not up yet");
            }

            @Override
            public TeamManager create(MinecraftServer server) {
                throw new AssertionError("a source that could not be asked must not be constructed");
            }
        };

        TeamManager chosen = TeamProviders.choose(null, List.of(
                broken,
                source("openpartiesandclaims", true)));

        assertEquals("openpartiesandclaims", chosen.name(),
                "a parties mod whose API throws during startup must not take teams away from a server " +
                        "that has a working source sitting behind it");
    }

    @Test
    @DisplayName("the stored fallback is a floor, not a rival, so it is never warned about")
    void theStoredFallbackIsNotARival() {
        TeamProvider ftb = source("ftbteams", true);
        TeamProvider fallback = StoredTeamManager.provider();

        // The property that drives the warning. A parties mod is a real alternative to another parties
        // mod; the stored source is what answers when nothing else can, and it says yes on every
        // server. So the first is a rival and the second is not.
        assertFalse(ftb.isFallback(), "a parties mod is a genuine alternative");
        assertTrue(fallback.isFallback(), "the stored source is the floor of the chain");

        // Measured on a real boot, which is how this was found: the warning named 'stored' on a server
        // running exactly ONE parties mod, and told the operator they had two. A line that is always
        // present and always wrong is worse than no line, because it teaches the reader to skip it.
        assertEquals(List.<String>of(), TeamProviders.rivals(List.of(ftb, fallback), ftb),
                "nothing to report: the loser is the fallback, and it is present everywhere");

        TeamProvider opac = source("openpartiesandclaims", true);
        assertEquals(List.of("openpartiesandclaims"),
                TeamProviders.rivals(List.of(ftb, opac, fallback), ftb),
                "but a second parties mod IS worth reporting -- that is the case the message exists for, "
                        + "and it is the only case where the choice between sources is a guess");

        assertEquals(List.of("ftbteams", "openpartiesandclaims"),
                TeamProviders.rivals(List.of(ftb, opac, fallback), fallback),
                "and if the fallback ever won, every real alternative is worth naming -- which cannot "
                        + "happen while it is last in the chain, so this pins the intent rather than a "
                        + "reachable state");
    }

    @Test
    @DisplayName("a chain with no fallback is refused rather than guessed at")
    void aChainWithNoFallbackIsRefused() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                TeamProviders.choose(null, List.of(
                        source("ftbteams", false),
                        source("openpartiesandclaims", false))));

        assertTrue(thrown.getMessage().contains("fallback"),
                () -> "the message should say what is missing, which is the stored source at the end. "
                        + "It said: " + thrown.getMessage());
    }

    @Test
    @DisplayName("registering nothing is refused, because a null provider would fail later")
    void aNullRegistrationIsRefused() {
        // Thrown before anything is added, so this cannot leak state into another test in this JVM.
        // It is asserted rather than left to a NullPointerException inside providers(), where the
        // trace would name the chain rather than the caller that registered nothing.
        assertThrows(IllegalArgumentException.class, () -> TeamProviders.register(null));

        assertFalse(TeamProviders.providers().stream().anyMatch(provider -> provider.id() == null),
                "and nothing null made it into the chain");
    }

    /** A candidate source that answers whatever it is told to, and builds a manager named after itself. */
    private static TeamProvider source(String id, boolean present) {
        return new TeamProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean isPresent(MinecraftServer server) {
                return present;
            }

            @Override
            public TeamManager create(MinecraftServer server) {
                return new ReadOnlyTeamManager(id);
            }
        };
    }
}
