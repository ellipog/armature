package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamManager;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link TeamManager} over somebody else's parties, mapped through {@link PartySource}.
 *
 * <h2>Read-only, and that is the design rather than a limitation</h2>
 *
 * <p>This implements {@link TeamManager}, not {@code MutableTeamManager}, so its six mutators are
 * the interface's defaults: each throws, naming the manager. Writes are deliberately absent.
 *
 * <p>The tempting alternative is to create parties in the other mod on the player's behalf, and it
 * is wrong for a reason that has nothing to do with capability. A server running a parties mod has
 * that mod's own commands, its own screens and its own idea of what a party is — roles, allies,
 * claims, a name the player chose. Forming one <i>for</i> them from inside a consumer's command
 * produces a party they did not know they had, in a list they did not look at, and the next thing
 * they do is run the parties mod's own command to leave it. The honest shape is: read somebody
 * else's parties, and own the ones you create. A caller that needs to write asks
 * {@link TeamManager#managesMembership()} and finds out.
 *
 * <h2>Where a solo team comes from</h2>
 *
 * <p>{@code realTeamOf} answers empty for a player in no party, and {@code TeamManager.teamOf}
 * synthesises a solo team from that — a team of one whose id is the player's own UUID. So a server
 * with a parties mod installed gives a lone player exactly the same treatment as a server without
 * one, which is what keeps every caller's code path single.
 *
 * <h2>What it claims about events</h2>
 *
 * <p>{@link #firesEvents()} is <b>false</b> unless the per-mod file has actually bridged that mod's
 * events onto {@link dev.ellipog.armature.api.teams.TeamEvents}. It is a constructor argument rather
 * than a constant here because it is the one fact about an adapter that cannot be read off this
 * file: the mapping has no idea whether the mod underneath announces its own changes, and guessing
 * {@code true} would tell a listener it will hear about a party formed through a screen it never
 * sees. An adapter that has done the work passes true; one that has not passes false, and a caller
 * can tell the difference.
 */
public final class PartyTeamAdapter implements TeamManager {

    private final String name;
    private final PartySource source;
    private final boolean firesEvents;

    /**
     * @param name        what {@code name()} returns — the mod's id
     * @param source      where the parties come from
     * @param firesEvents whether this adapter has bridged that mod's own events onto Armature's.
     *                    See the class comment: false unless the work was actually done
     */
    public PartyTeamAdapter(String name, PartySource source, boolean firesEvents) {
        this.name = name;
        this.source = source;
        this.firesEvents = firesEvents;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean firesEvents() {
        return firesEvents;
    }

    @Override
    public Collection<Team> teams() {
        return source.parties().stream().map(PartyTeamAdapter::toTeam).toList();
    }

    @Override
    public Optional<Team> byId(UUID id) {
        return source.parties().stream()
                .filter(party -> party.id().equals(id))
                .findFirst()
                .map(PartyTeamAdapter::toTeam);
    }

    /**
     * A direct lookup rather than the default walk.
     *
     * <p>Worth overriding even though the default would give the same answer: the default iterates
     * every party on the server, and "which party is this player in" is asked constantly by a mod
     * that keys anything by a team. The source underneath has an index for exactly this question,
     * so asking it is both cheaper and correct on a server with more parties than fit in a glance.
     */
    @Override
    public Optional<Team> realTeamOf(UUID player) {
        return source.forMember(player).map(PartyTeamAdapter::toTeam);
    }

    /**
     * The mapping, and the whole of it.
     *
     * <p>{@code persistent} is true and {@code createdAt} is zero because those are the two things a
     * foreign party cannot tell us: every team in this source is a real stored party, so there is no
     * synthesised case to distinguish, and no parties mod exposes when a party was formed. Zero is
     * the honest value — a caller wanting to sort by age gets a stable answer that is simply not a
     * useful one, rather than a made-up timestamp that looks authoritative.
     *
     * <p>Public rather than package-private because an adapter that bridges its mod's events has to
     * turn a party into a team outside a call to {@code teams()} — and that adapter is in its own
     * package, on purpose. It is the same one mapping either way: a second copy in the adapter file
     * is a second place for the owner or the id to be got wrong.
     */
    public static Team toTeam(PartySource.Party party) {
        return new Team(party.id(), party.name(), party.owner(), party.members(), party.invites(),
                0L, true);
    }
}
