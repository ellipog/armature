package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.TeamRole;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a mapping needs from somebody else's parties mod, in Armature's own terms.
 *
 * <h2>Why this exists instead of the mapping calling the API directly</h2>
 *
 * <p>Because then the mapping is <i>testable</i>, and the part of an adapter that has bugs in it is
 * the mapping. Turning a foreign party into a {@code Team} means deciding what a rank means, what
 * happens when the owner is unknown, whether an invited player counts as a member — questions about
 * <i>meaning</i>, not about anybody's API. Every one of those can be answered wrongly, and the wrong
 * answer looks like a working adapter: one player's party silently reporting the wrong owner is not
 * a crash, it is a bug somebody reports a month later.
 *
 * <p>With this seam, {@link PartyTeamAdapter} is a plain mapping from records to a record and can be
 * asserted on with no mod installed at all. What is left in the per-mod file is the part that cannot
 * be tested without the mod — reading the real objects — and that part is deliberately thin enough
 * to read in one sitting.
 *
 * <p>It is also what keeps the integration packages out of every other file's way:
 * {@code check_integration.py} fails on a third-party package name anywhere but the one file per mod
 * that has to spell it, and this interface is the reason that file can be twenty lines of reading
 * rather than a hundred of deciding.
 *
 * <h2>Third-party-free, deliberately</h2>
 *
 * <p>Nothing here may name a mod. It is a description of a party as Armature understands one, and if
 * it ever needs a type from a specific mod then the seam has stopped doing its job.
 *
 * <h2>Public, though it lives under {@code impl/}</h2>
 *
 * <p>For the same reason {@link TeamProvider} is: the adapter that implements it is in a
 * sub-package, and a package-private interface cannot be implemented across a package boundary.
 * {@code impl/} is not the public surface — {@code api/} is — and this is {@code impl/} being used
 * as an SPI among its own parts.
 */
public interface PartySource {

    /**
     * A party, read out of somebody else's model and into Armature's.
     *
     * <p>Field for field what {@code Team} holds, minus what a foreign source cannot know:
     * {@code persistent} is always true for a real party, and {@code createdAt} is zero because no
     * parties mod exposes a creation time.
     *
     * @param id      the party's id. For FTB this is {@code getTeamId()}, not {@code getId()} — see
     *                {@code FtbTeams}, where the difference is the whole reason the adapter filters
     * @param name    the party's name as its own mod shows it
     * @param owner   who owns it. Never {@code null}, and never a sentinel: a source that cannot say
     *                must say so by leaving the owner out of {@code members} instead, because an id
     *                that is not a player is worse than a missing field
     * @param members everyone in it, with the role Armature understands
     * @param invites players invited but not yet joined
     */
    record Party(UUID id,
                 String name,
                 UUID owner,
                 Map<UUID, TeamRole> members,
                 Set<UUID> invites) {
    }

    /**
     * Every party this source knows about, in whatever order it keeps them.
     *
     * <p>An unmodifiable list rather than a live collection: the caller maps and sorts it, and a
     * stream that is consumed twice, or a collection that mutates underneath a walk, is how this
     * becomes a heisenbug on a busy server.
     */
    Collection<Party> parties();

    /**
     * The party {@code player} is in, if any.
     *
     * <p>Empty means "in no party", and that is a real answer rather than a failure — the caller
     * synthesises a solo team from it. A source that returns something for a player who is in no
     * party makes the solo fallback meaningless and hands a lone player a party's progress.
     */
    Optional<Party> forMember(UUID player);
}
