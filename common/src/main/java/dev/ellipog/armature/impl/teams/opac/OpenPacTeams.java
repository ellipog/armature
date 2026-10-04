package dev.ellipog.armature.impl.teams.opac;

import com.mojang.authlib.GameProfile;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.teams.MutableTeamManager;
import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamFeature;
import dev.ellipog.armature.api.teams.TeamInvite;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.impl.teams.TeamProvider;

import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import xaero.pac.common.parties.party.api.IPartyPlayerInfoAPI;
import xaero.pac.common.parties.party.member.PartyMemberRank;
import xaero.pac.common.parties.party.member.api.IPartyMemberAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.parties.party.api.IPartyManagerAPI;
import xaero.pac.common.server.parties.party.api.IServerPartyAPI;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Open Parties and Claims, read as a source of parties — and, unlike FTB, written to.
 *
 * <h2>A consumer, not a registration</h2>
 *
 * <p>OPAC has an addon gateway for <i>claims</i> — a listener manager an addon can register with —
 * and <b>none for parties</b>. There is no party event to subscribe to. So this does the only thing
 * available: it reads {@code OpenPACServerAPI.get(server)} and asks it questions.
 *
 * <p>That is a real constraint rather than a preference, and it is why this class is a consumer while
 * {@code FtbTeams} is both a consumer and an event bridge. There is nowhere to put a registration, so
 * none is attempted — an addon that implemented OPAC's player-party-system interface in order to look
 * subscribed would be claiming a role in OPAC's own dispatch that does not exist for parties.
 *
 * <h2>{@code firesEvents()} is false, and that is the true answer</h2>
 *
 * <p>A party formed through OPAC's own command does not tell Armature anything, because nothing
 * connects the two. A listener therefore cannot be the only thing keeping state correct on a server
 * running OPAC, and saying so is the whole reason the method exists. Claiming true — because this
 * class implements {@code MutableTeamManager} and its own writes do fire Armature's events — would be
 * exactly the kind of plausible-looking claim the interface was written to prevent.
 *
 * <h2>Three things about this API that have to be handled rather than assumed</h2>
 *
 * <p><b>One — {@code get(server)} hard-casts.</b> OPAC adds {@code IOpenPACMinecraftServer} to
 * Minecraft's server class with a mixin, and {@code OpenPACServerAPI.get} casts the server to it.
 * {@code isModLoaded("openpartiesandclaims")} therefore guarantees nothing about whether that cast
 * succeeds: a mod can be loaded while its mixins failed to apply, and the failure is a
 * {@code ClassCastException} from inside somebody else's code. So resolution goes through a path that
 * catches it and reports a source that is not available, rather than letting the first team lookup of
 * a mod's lifetime crash a server.
 *
 * <p><b>Two — {@code PartyMemberRank} has no OWNER constant.</b> The constants are MEMBER, CLAIMER,
 * MODERATOR and ADMIN, and ownership is a <i>property of a member</i> —
 * {@code IPartyMemberAPI.isOwner()} — rather than a rank. So ownership is inferred from the party's
 * owner member. A mapping that looked for a rank called OWNER would not compile, and one that guessed
 * {@code ADMIN} meant the owner would be quietly wrong on every party whose owner is not an admin.
 *
 * <p><b>Three — the server thread.</b> OPAC resolves a player to a party by consulting its own
 * registry, and nothing documents those objects as thread-safe. So every entry point runs on the
 * server thread and says so loudly if it does not. {@code TeamProviders} resolves on the server thread
 * for the same reason.
 *
 * <h2>What a caller gets that OPAC cannot express</h2>
 *
 * <p>Nothing, deliberately. OPAC has no team of one: {@code getPartyByMember} returns null for a solo
 * player, {@link #realTeamOf} reports empty, and {@code teamOf} synthesises a solo team from the
 * player's own UUID — the same answer every other source gives. A party cannot be renamed through this
 * API either, so {@link #create} reports the name OPAC chose rather than pretending it applied the one
 * it was given.
 *
 * <h2>Where its provider lives, and why that is a separate file</h2>
 *
 * <p>In {@link OpenPacTeamsProvider}, which names no OPAC type at all. See that class for the
 * argument: this class is only loadable where OPAC is present, and something has to be able to ask
 * whether it is, from code that runs everywhere.
 */
public final class OpenPacTeams implements MutableTeamManager {

    /**
     * OPAC's mod id — spelled once, on {@link OpenPacTeamsProvider}.
     *
     * <p>It lives there rather than here because that class names no OPAC type and so is safe to
     * touch from unguarded code, which is exactly what the probe needs. See its comment.
     */
    public static final String MOD_ID = OpenPacTeamsProvider.MOD_ID;

    private final MinecraftServer server;
    private final OpenPACServerAPI api;

    private OpenPacTeams(MinecraftServer server, OpenPACServerAPI api) {
        this.server = server;
        this.api = api;
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * This mod as something {@code TeamProviders} can consider.
     *
     * <p>A provider rather than this class implementing {@link TeamProvider} directly, and the
     * indirection earns its place. The class that answers "should this source be used" has no server
     * to hold, and this class needs one for every question it answers — so the two are genuinely
     * different objects, and a single class would carry a half-initialised state whose every accessor
     * threw.
     *
     * <p>It is also what keeps the mod id somewhere safe to read. {@code OpenPacTeamsProvider} holds
     * it, names no OPAC type, and is therefore loadable on any server at all — which is precisely what
     * a probe needs.
     */
    public static TeamProvider provider() {
        return new TeamProvider() {
            @Override
            public String id() {
                return MOD_ID;
            }

            @Override
            public boolean isPresent(MinecraftServer server) {
                return OpenPacTeams.isPresent(server);
            }

            @Override
            public TeamManager create(MinecraftServer server) {
                return of(server);
            }
        };
    }

    /**
     * Loaded, the API resolves for this server, and there is at least one party.
     *
     * <p>All three, and the last one is load-bearing: OPAC on a server where nobody has formed a party
     * should not be the teams source, because the store behind it is the honest answer and a
     * party-shaped take on a server with no parties helps nobody.
     *
     * <p>Server-thread only, as the class comment explains. Asked off the server thread this reports
     * false rather than throwing, because the question it is answering is "should this be used", and
     * "not knowable right now" and "no" have the same consequence.
     */
    public static boolean isPresent(MinecraftServer server) {
        if (!server.isSameThread()) {
            Constants.LOG.debug("armature: asked whether Open Parties and Claims is present off the "
                    + "server thread; answering no, because its registry is not documented as safe to "
                    + "read from anywhere else");
            return false;
        }
        try {
            OpenPACServerAPI resolved = OpenPACServerAPI.get(server);
            return resolved != null && resolved.getPartyManager().getAllStream().findAny().isPresent();
        }
        catch (ClassCastException e) {
            // get() casts the server to the interface OPAC's mixin adds. isModLoaded says the mod is
            // present, not that the mixin was applied -- so this is expected rather than exotic.
            Constants.LOG.warn("armature: Open Parties and Claims is loaded but its server hook is not "
                    + "present on this server ({}); its parties are not available", e.toString());
            return false;
        }
    }

    /** The manager for this server. Only called once {@link #isPresent} said yes. */
    public static OpenPacTeams of(MinecraftServer server) {
        return new OpenPacTeams(server, OpenPACServerAPI.get(server));
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Override
    public String name() {
        return MOD_ID;
    }

    /** False. See the class comment: OPAC does not announce its parties and there is nothing to listen to. */
    @Override
    public boolean firesEvents() {
        return false;
    }

    /**
     * None of them, and that is OPAC's shape rather than a gap in this adapter: its public API has no
     * rename, no ownership transfer, no party policy and no uninvite, and its own screens and commands
     * own those. A panel asks this and hides the controls, so an OPAC party shows the roster and the
     * actions that work instead of buttons that can only refuse.
     */
    @Override
    public boolean supports(TeamFeature feature) {
        return false;
    }

    @Override
    public Collection<Team> teams() {
        onServerThread();
        return parties().getAllStream().map(this::toTeam).toList();
    }

    @Override
    public Optional<Team> byId(UUID id) {
        onServerThread();
        return Optional.ofNullable(parties().getPartyById(id)).map(this::toTeam);
    }

    @Override
    public Optional<Team> realTeamOf(UUID player) {
        onServerThread();
        // Null rather than empty: getPartyByMember answers null for a player in no party, which is the
        // case teamOf synthesises a solo team for.
        return Optional.ofNullable(parties().getPartyByMember(player)).map(this::toTeam);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Forms a party owned by {@code owner}.
     *
     * <p><b>The name is not applied.</b> OPAC names a party itself — {@code getDefaultName()} is on its
     * API and a setter is not — so the returned team carries the name OPAC chose rather than the one
     * passed in. Said out loud in the log rather than hidden, because the alternative is a caller that
     * believes it created a party called what it asked for, and a panel that shows something else.
     *
     * @throws IllegalStateException if the owner is already in a party, or this server has never seen
     *         them so there is no profile to create one from
     */
    @Override
    public Team create(String name, UUID owner) {
        onServerThread();
        IPartyManagerAPI parties = parties();

        if (parties.getPartyByMember(owner) != null) {
            throw new IllegalStateException("That player is already in a party; leave it first.");
        }

        GameProfile profile = profileOf(owner);
        if (profile == null) {
            // createPartyForOwner takes a GameProfile, so a player this server has no profile for
            // cannot own a party -- and their name has to come from somewhere.
            throw new IllegalStateException("No profile is known for " + owner + ", so Open Parties and "
                    + "Claims cannot create a party for them. They have to have joined this server at "
                    + "least once.");
        }

        IServerPartyAPI party = parties.createPartyForOwner(profile);
        if (party == null) {
            throw new IllegalStateException("Open Parties and Claims declined to create a party for "
                    + profile.getName() + "; it has its own config for who may form one.");
        }

        if (!name.isEmpty() && !name.equals(party.getDefaultName())) {
            Constants.LOG.info("armature: asked for a party named '{}'; Open Parties and Claims named it "
                    + "'{}' instead, as its public API has no rename", name, party.getDefaultName());
        }
        return toTeam(party);
    }

    @Override
    public boolean invite(UUID teamId, UUID player) {
        onServerThread();
        IServerPartyAPI party = parties().getPartyById(teamId);
        if (party == null || party.getMemberInfo(player) != null || party.isInvited(player)) {
            return false;
        }
        String name = nameOf(player);
        if (name == null) {
            // invitePlayer takes a name, and the name is what OPAC shows in its own invite list. There
            // is no name to give for a player this server has never seen.
            return false;
        }
        party.invitePlayer(player, name);
        return true;
    }

    /**
     * Invites on an actor's behalf, to the extent OPAC lets this adapter check.
     *
     * <p>OPAC keeps its own rules for who may invite and publishes no policy through this API, so the
     * only floor enforceable here is that the actor is in the party at all; beyond that the source
     * owns the question, which is exactly why {@link #supports} answers false for the policy and the
     * panel shows its settings as OPAC's rather than as this team's.
     */
    @Override
    public boolean invite(UUID actor, UUID teamId, UUID player) {
        onServerThread();
        IServerPartyAPI party = parties().getPartyById(teamId);
        if (party == null || party.getMemberInfo(actor) == null) {
            return false;
        }
        return invite(teamId, player);
    }

    /**
     * Accepts the first invitation this player holds.
     *
     * <p>Leaves any current party first, matching the stored manager: a player who accepts an
     * invitation while already in a party would otherwise be in two, and OPAC's own model has one party
     * per member.
     */
    @Override
    public Optional<Team> acceptInvite(UUID player) {
        onServerThread();
        Optional<IServerPartyAPI> target = parties().getAllStream()
                .filter(party -> party.isInvited(player))
                .findFirst();
        return target.flatMap(party -> accept(party, player));
    }

    /**
     * Accepts one specific invitation, so a panel's Accept button means the row it is on.
     *
     * <p>A player can hold several invitations and the untargeted form picks the first; without this,
     * accepting the second row's invitation joins the first row's party.
     */
    @Override
    public Optional<Team> acceptInvite(UUID player, UUID teamId) {
        onServerThread();
        IServerPartyAPI party = parties().getPartyById(teamId);
        if (party == null || !party.isInvited(player)) {
            return Optional.empty();
        }
        return accept(party, player);
    }

    /** The shared half of both accept forms: leave what you are in, then join. */
    private Optional<Team> accept(IServerPartyAPI target, UUID player) {
        IPartyManagerAPI parties = parties();

        IServerPartyAPI current = parties.getPartyByMember(player);
        if (current != null && !ownerOf(current).equals(player)) {
            current.removeMember(player);
        }

        String name = nameOf(player);
        if (name == null) {
            return Optional.empty();
        }

        target.addMember(player, PartyMemberRank.MEMBER, name);
        return Optional.of(toTeam(target));
    }

    /**
     * Leaves, unless they own the party.
     *
     * <p>An owner leaving would leave their party ownerless, so OPAC refuses it — {@code
     * OWNER_CANT_LEAVE} is one of its own command errors — and there is no transfer through the public
     * API this could do on their behalf. False, and the caller reports it.
     */
    @Override
    public boolean leave(UUID player) {
        onServerThread();
        IServerPartyAPI party = parties().getPartyByMember(player);
        if (party == null) {
            return false;
        }
        if (ownerOf(party).equals(player)) {
            return false;
        }
        party.removeMember(player);
        return true;
    }

    /**
     * Removes somebody else.
     *
     * <p>Allowed for anyone whose OPAC rank outranks the target's — which is how ADMIN and MODERATOR
     * become Armature's {@code OFFICER} — and never for the party's owner, who cannot be removed by
     * anybody. The owner check is by UUID rather than by rank because there is no OWNER rank to
     * compare, which is the same reason {@link #ownerOf} exists.
     */
    @Override
    public boolean kick(UUID actor, UUID target) {
        onServerThread();
        IServerPartyAPI party = parties().getPartyByMember(target);
        if (party == null || actor.equals(target)) {
            return false;
        }

        IPartyMemberAPI actorMember = party.getMemberInfo(actor);
        IPartyMemberAPI targetMember = party.getMemberInfo(target);
        if (actorMember == null || targetMember == null) {
            return false;
        }
        if (ownerOf(party).equals(target)) {
            return false;
        }
        if (!roleOf(actorMember).outranks(roleOf(targetMember))) {
            return false;
        }

        party.removeMember(target);
        return true;
    }

    @Override
    public boolean disband(UUID actor, UUID teamId) {
        onServerThread();
        IPartyManagerAPI parties = parties();
        IServerPartyAPI party = parties.getPartyById(teamId);
        if (party == null || !ownerOf(party).equals(actor)) {
            return false;
        }
        parties.removePartyById(teamId);
        return true;
    }

    // ------------------------------------------------------------------
    // The mapping
    // ------------------------------------------------------------------

    private IPartyManagerAPI parties() {
        return api.getPartyManager();
    }

    private Team toTeam(IServerPartyAPI party) {
        Map<UUID, TeamRole> members = new LinkedHashMap<>();
        party.getMemberInfoStream().forEach(member ->
                members.put(member.getUUID(), roleOf(member)));

        UUID owner = ownerOf(party);
        // OPAC's invitation list is bare ids, like FTB's: it publishes neither the sender nor the
        // time, so the owner and zero stand in -- see TeamInvite. The policy is the default for the
        // same reason, and supports() false is what keeps a panel from showing it as editable.
        Map<UUID, TeamInvite> invites = new LinkedHashMap<>();
        party.getInvitedPlayersStream().map(IPartyPlayerInfoAPI::getUUID)
                .forEach(invited -> invites.put(invited, new TeamInvite(owner, 0L)));

        // createdAt is zero: OPAC does not expose when a party was formed. persistent is true, as every
        // party here is a real stored one.
        return new Team(party.getId(), party.getDefaultName(), owner, members, invites,
                TeamPolicy.DEFAULT, 0L, true);
    }

    /**
     * Ownership, inferred, because there is no OWNER rank.
     *
     * <p>{@code IPartyMemberAPI.isOwner()} is the flag and it is on the member rather than on the rank,
     * so this asks the party for its owner member and takes the UUID. Falling back to
     * {@code NIL_UUID} for a party with no owner member is right rather than convenient: that is a
     * corrupt party, and a sentinel matching no player is the failure a caller can detect, where a
     * made-up owner would look like data.
     */
    static UUID ownerOf(IServerPartyAPI party) {
        IPartyMemberAPI owner = party.getOwner();
        return owner == null ? Util.NIL_UUID : owner.getUUID();
    }

    /**
     * OPAC's four ranks folded into Armature's three.
     *
     * <p>CLAIMER is a member who may claim land, which is a permission about chunks rather than about
     * people, so it is a plain MEMBER here. ADMIN and MODERATOR can both remove members in OPAC, which
     * is what {@code OFFICER} means in Armature, and folding them together means Armature's own
     * authority comparison works without a second table.
     */
    static TeamRole roleOf(IPartyMemberAPI member) {
        if (member.isOwner()) {
            return TeamRole.OWNER;
        }
        PartyMemberRank rank = member.getRank();
        if (rank == PartyMemberRank.ADMIN || rank == PartyMemberRank.MODERATOR) {
            return TeamRole.OFFICER;
        }
        return TeamRole.MEMBER;
    }

    // ------------------------------------------------------------------
    // Server state
    // ------------------------------------------------------------------

    private GameProfile profileOf(UUID player) {
        return server.getProfileCache().get(player).orElse(null);
    }

    /** A player's name, from the player list when online and the profile cache otherwise. */
    private String nameOf(UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        GameProfile cached = profileOf(player);
        return cached == null ? null : cached.getName();
    }

    /**
     * Every entry point goes through this first.
     *
     * <p>A thrown exception rather than a silent no-op: OPAC's own objects are read here, and a lookup
     * off the server thread is a caller's bug that produces an answer which is sometimes right. A
     * stack trace naming the call site is the only version of this that gets fixed.
     */
    private void onServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Armature's Open Parties and Claims adapter must be used on "
                    + "the server thread; OpenPAC reads its own registry and does not document that as "
                    + "safe from anywhere else.");
        }
    }
}
