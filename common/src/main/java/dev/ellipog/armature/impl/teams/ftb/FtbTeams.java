package dev.ellipog.armature.impl.teams.ftb;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.teams.TeamEvents;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.impl.teams.PartySource;
import dev.ellipog.armature.impl.teams.PartyTeamAdapter;
import dev.ellipog.armature.impl.teams.TeamProvider;
import dev.ellipog.armature.impl.teams.TeamProviders;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;

import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * FTB Teams, read as a source of parties.
 *
 * <h2>This is the only file in Armature that names FTB</h2>
 *
 * <p>Together with {@link OpenPacTeams} it is one of exactly two, and that is what
 * {@code .utils/check_integration.py} enforces: a third-party package name anywhere else under either
 * repo fails the build. Everything decidable lives in {@link PartyTeamAdapter} — what a rank means,
 * what a solo team is, how a party becomes a {@code Team} — so what is left here is the part that
 * cannot be decided anywhere else, which is reading the real objects.
 *
 * <p>The other reason it matters: FTB Teams is a <b>soft</b> dependency in the only sense worth
 * having. Nothing Armature ships requires it, a server without it loses nothing, and this file is
 * never even loaded unless {@code ftbteams} is.
 *
 * <h2>The three things that make this less obvious than it looks</h2>
 *
 * <p><b>One — {@code getTeams()} is not a list of parties.</b> FTB keeps a permanent team per player,
 * created on that player's first join and never removed, and {@code PlayerTeam.isPlayerTeam()} is
 * what marks one. An unfiltered list therefore reports every player who has <i>ever</i> logged in as
 * a party — a server with two friends playing alone shows two "parties" of one, and a caller asking
 * for {@code allTeams()} gets a party list that grows and never shrinks. So every read here filters
 * on {@code isPartyTeam()}, and the filter is not an optimisation.
 *
 * <p><b>Two — {@code getTeamId()} is the effective id, not {@code getId()}.</b> A {@code PlayerTeam}'s
 * id is that player's id, because it is their personal team; {@code getTeamId()} returns the id of
 * the team the player is <i>effectively</i> in, which is the party once they join one. For a
 * {@code PartyTeam} the two agree. Using {@code getId()} would mean a player's party reported an id
 * that changes the moment they join something else, and anything keyed by a team id would silently
 * lose its data on the way in. Verified against the bytecode rather than the javadoc:
 * {@code PlayerTeam.getTeamId()} is {@code effectiveTeam.getId()}.
 *
 * <p><b>Three — {@code getOwner()} is a lie on a player team, and the truth on a party team.</b>
 * {@code AbstractTeam.getOwner()} returns {@code Util.NIL_UUID} unconditionally — not "unset",
 * a real value that will happily be used as an owner — and only {@code PartyTeam} overrides it with
 * something meaningful. So it is only trustworthy because of decision one: we never look at a team
 * that is not a party. {@link #ownerOf} does not take that on faith, because a future FTB release
 * could move the override; it treats {@code NIL_UUID} as "cannot say" and falls back to reading the
 * ranks, which is the only other place ownership is recorded.
 *
 * <h2>What it bridges, and what it therefore claims</h2>
 *
 * <p>FTB announces a player joining or leaving a party through
 * {@code TeamEvent.PLAYER_JOINED_PARTY} and {@code PLAYER_LEFT_PARTY}, and those are forwarded onto
 * Armature's {@link TeamEvents}. That is what makes {@code firesEvents()} true here while it is false
 * on the OPAC adapter: a listener on this manager hears about a party formed through FTB's own screen,
 * which is the only way a party is formed on a server running FTB.
 *
 * <p>Two events and not four. FTB fires {@code CREATED}, {@code DELETED}, {@code PLAYER_CHANGED},
 * {@code OWNERSHIP_TRANSFERRED} and more, and mapping all of them onto Armature's four would mean
 * inventing a correspondence that is not there — Armature's {@code MEMBER_JOINED} means "the team
 * now contains this player", and the two party events are exactly that. What is not bridged is
 * therefore not bridged <i>silently</i>: a party being renamed or disbanded through FTB's screen
 * does not fire Armature's {@code TEAM_DISBANDED}, and a listener that needs that is looking at the
 * wrong event source. Said here rather than discovered from a bug report.
 */
public final class FtbTeams implements TeamProvider, PartySource {

    /**
     * FTB Teams' mod id — spelled once, on {@link FtbTeamsProvider}.
     *
     * <p>It lives there rather than here because that class names no FTB type and so is safe to
     * touch from unguarded code, which is exactly what the probe needs. See its comment.
     */
    public static final String MOD_ID = FtbTeamsProvider.MOD_ID;

    /** The adapter is stateless apart from the source, so one is enough. */
    private final PartyTeamAdapter adapter = new PartyTeamAdapter(MOD_ID, this, true);

    /** The event bridge is process-wide, and registering it twice would fire Armature's events twice. */
    private static boolean bridged;

    // ------------------------------------------------------------------
    // TeamProvider
    // ------------------------------------------------------------------

    @Override
    public String id() {
        return MOD_ID;
    }

    /**
     * Loaded, manager up, and holding at least one real party.
     *
     * <p>The third condition is the one that matters and the one that is easy to leave out. See the
     * class comment: FTB has a team per player from first join, so this is the difference between
     * "FTB is running" and "somebody has formed a party".
     */
    @Override
    public boolean isPresent(MinecraftServer server) {
        if (!FTBTeamsAPI.api().isManagerLoaded()) {
            return false;
        }
        for (Team team : FTBTeamsAPI.api().getManager().getTeams()) {
            if (team.isPartyTeam()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Subscribes to FTB's party events, whether or not FTB ends up being the chosen source.
     *
     * <p><b>This is the fix for a real deadlock, and the shape of it is worth stating.</b> The bridge
     * used to be installed from {@link #create}, which is only called for the source that <i>wins</i>
     * the resolution — and FTB only wins when it already holds a party. So on a server with FTB
     * installed and nobody yet in a party, FTB lost to the stored source, `create` was never called,
     * and the bridge was therefore never installed. Which meant that the one event that could have
     * told us a party had appeared was the event we had not subscribed to. A party created in that
     * session announced itself to nobody, and the server's log stayed silent.
     *
     * <p>Installing it here breaks the loop, because this runs for every loaded source before the
     * resolution decides anything. FTB hears about the party, and the bridge clears the cache so the
     * <i>next</i> ask re-resolves and finds FTB's party waiting — see the handlers below.
     */
    @Override
    public void attachListeners(MinecraftServer server) {
        bridge();
    }

    @Override
    public TeamManager create(MinecraftServer server) {
        // Belt and braces: attachListeners has normally run already, from TeamProviders.resolve. Kept
        // because create() is the other path that reaches this adapter, and a bridge that depends on
        // which path ran first is exactly the kind of ordering assumption that produced the bug above.
        bridge();
        return adapter;
    }

    // ------------------------------------------------------------------
    // PartySource
    // ------------------------------------------------------------------

    @Override
    public Collection<Party> parties() {
        var manager = FTBTeamsAPI.api().getManager();
        Collection<Team> raw = manager.getTeams();

        Collection<Party> out = new ArrayList<>(raw.size());
        for (Team team : raw) {
            if (team.isPartyTeam()) {
                out.add(partyOf(team));
            }
        }
        return out;
    }

    @Override
    public Optional<Party> forMember(UUID player) {
        // getTeamForPlayerID, not getPlayerTeamForPlayerID. The first resolves through the player's
        // PlayerTeam to whatever team is effective -- the party, once they are in one -- and the
        // second returns the personal team, which exists for every player and is never a party unless
        // the server is configured to make solo play party-shaped.
        return FTBTeamsAPI.api().getManager().getTeamForPlayerID(player)
                .filter(Team::isPartyTeam)
                .map(this::partyOf);
    }

    // ------------------------------------------------------------------
    // The mapping, driven from the adapter so there is only one of it
    // ------------------------------------------------------------------

    Party partyOf(Team team) {
        Map<UUID, TeamRole> members = new LinkedHashMap<>();
        for (UUID member : team.getMembers()) {
            members.put(member, role(team.getRankForPlayer(member)));
        }

        Set<UUID> invites = new LinkedHashSet<>(team.getPlayersByRank(TeamRank.INVITED).keySet());

        return new Party(team.getTeamId(), team.getName().getString(), ownerOf(team), members, invites);
    }

    /**
     * FTB's ranks, folded into Armature's three.
     *
     * <p>{@code TeamRank} has seven, and they are two different things wearing one name: ranks of
     * membership (invited, member, officer, owner) and non-membership relations (enemy, none, ally).
     * Only the first kind can appear in {@code getMembers()}, so the fold is two questions — is it
     * the owner, and is it senior — rather than a table.
     *
     * <p>ALLY is deliberately not OFFICER: an ally is a different party, not a senior member of this
     * one, and mapping it into the member list would put another team's players in this team.
     */
    static TeamRole role(TeamRank rank) {
        if (rank.isOwner()) {
            return TeamRole.OWNER;
        }
        if (rank.isOfficerOrBetter()) {
            return TeamRole.OFFICER;
        }
        return TeamRole.MEMBER;
    }

    /**
     * The owner, believed only when FTB says something believable.
     *
     * <p>{@code NIL_UUID} is what {@code AbstractTeam} returns for anybody who does not override it,
     * so it is a real value rather than a marker — and it is a value that would otherwise be stored
     * as a team's owner and never match a player. Falling back to the ranks is the only other place
     * ownership is recorded, and a party with nobody ranked as owner is a corrupted file rather than
     * a case to handle gracefully; returning the sentinel there loses nothing a caller could use.
     */
    static UUID ownerOf(Team team) {
        UUID declared = team.getOwner();
        if (declared != null && !Util.NIL_UUID.equals(declared)) {
            return declared;
        }
        for (UUID member : team.getMembers()) {
            if (team.getRankForPlayer(member).isOwner()) {
                return member;
            }
        }
        return declared == null ? Util.NIL_UUID : declared;
    }

    // ------------------------------------------------------------------
    // The event bridge
    // ------------------------------------------------------------------

    /**
     * FTB's party events, forwarded onto Armature's.
     *
     * <p>Registered once per process, on the first {@code create} — which is the first moment FTB's
     * manager is known to be up, and the earliest point at which a party can exist to be announced.
     *
     * <p>Both handlers need a {@code MinecraftServer} and FTB's events do not carry one, so it comes
     * from the player the event names. That is complete for the join event and <i>almost</i> complete
     * for the leave event: {@code PlayerLeftPartyTeamEvent} carries a player id and a nullable
     * player, because a player can be removed while offline, and when the player is null the server
     * is not knowable from the event. So that case is skipped rather than guessed — a bridged event
     * with the wrong server is worse than a missing one, because a listener would write to the wrong
     * world's state.
     */
    private void bridge() {
        if (bridged) {
            return;
        }
        bridged = true;

        TeamEvent.PLAYER_JOINED_PARTY.register(event -> {
            if (event.getPlayer() == null || !event.getTeam().isPartyTeam()) {
                return;
            }
            MinecraftServer server = event.getPlayer().getServer();
            if (server == null) {
                return;
            }

            // BEFORE the event, and the order is deliberate rather than incidental: a listener that
            // asks Teams.of(server) should see the source this party belongs to, and it would see the
            // stale answer if the two were the other way round. The party now exists, so the reason
            // FTB was passed over no longer holds.
            TeamProviders.invalidate(server);

            TeamEvents.MEMBER_JOINED.invoker().onMemberJoined(server,
                    PartyTeamAdapter.toTeam(partyOf(event.getTeam())), event.getPlayer().getUUID());
        });

        TeamEvent.PLAYER_LEFT_PARTY.register(event -> {
            if (event.getPlayer() == null) {
                // Removed while offline. The server is not in the event, so there is nothing honest
                // to pass a listener. See this method's comment.
                Constants.LOG.debug("armature: {} left an FTB party while offline; not bridged to "
                        + "Armature's TeamEvents because the event does not name a server",
                        event.getPlayerId());
                return;
            }
            MinecraftServer server = event.getPlayer().getServer();
            if (server == null || !event.getTeam().isPartyTeam()) {
                return;
            }

            // Symmetric with the join handler, and it matters for the same reason in reverse: if the
            // party that made FTB the source is gone, the source should be re-decided rather than
            // remembered. Leaving it cached would keep answering with a party that no longer exists.
            TeamProviders.invalidate(server);

            TeamEvents.MEMBER_LEFT.invoker().onMemberLeft(server,
                    PartyTeamAdapter.toTeam(partyOf(event.getTeam())), event.getPlayerId(),
                    TeamEvents.Reason.LEFT);
        });

        // The word "whether or not" is the load-bearing part: this line appears on a server where FTB
        // is NOT the chosen source, and it has to. See attachListeners.
        Constants.LOG.info("armature: listening for FTB Teams' party events (whether or not FTB is the "
                + "chosen source on this server)");
    }
}
