package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.TeamManager;

import net.minecraft.server.MinecraftServer;

/**
 * One place teams can come from, as a mod id and a factory.
 *
 * <p>This is the {@code (modId, factory)} pair {@link TeamProviders} keeps an ordered list of,
 * with the presence test that decides whether the pair applies to this server. The three parts
 * belong together rather than beside the list, because every one of them is about the same mod:
 * the id is what {@code isModLoaded} is asked about, what the resolution log prints, and what the
 * manager itself reports from {@link TeamManager#name()}.
 *
 * <h2>Why {@code isPresent} is asked rather than assumed</h2>
 *
 * <p>A loaded mod is not the same as an <i>available source</i>, and the difference is not pedantry —
 * it is the difference between two parties mods being installed and either of them having a party
 * yet. FTB Teams holds a permanent team per player and creates one on first join, so "the manager is
 * loaded" says nothing about whether anybody has formed a party; OPAC's API is added to the server
 * by a mixin, so a loaded OPAC that has not been injected into <i>this</i> server object will throw
 * on the first call rather than answer.
 *
 * <p>So a source is asked, and a source that cannot be asked is a source that is not used. That is
 * also why {@code TeamProviders} catches whatever the question throws: the honest reading of an
 * exception from {@code isPresent} is "not present", and the alternative is that one badly-behaved
 * integration takes teams away from a server that would otherwise have them.
 *
 * <h2>Public, though it lives under {@code impl/}</h2>
 *
 * <p>Public because the two adapters are in sub-packages of this one, and a package-private
 * interface cannot be implemented across a package boundary — the compiler refuses the
 * {@code implements} clause before it reads a single method. {@code impl/} is not the public
 * surface: the rule is that {@code api/} must not import {@code impl/}, and this is {@code impl/}
 * being used as an SPI among its own parts, which is what a sub-package is for.
 */
public interface TeamProvider {

    /**
     * The mod this source belongs to — {@code "ftbteams"}, {@code "openpartiesandclaims"},
     * {@code "stored"}.
     *
     * <p>An id rather than a display name, because it is also the string {@code isModLoaded} takes
     * and the string the resolution log prints.
     */
    String id();

    /**
     * Whether this source can actually supply teams on this server, right now.
     *
     * <p>Called once per server, on the server thread, and it is allowed to be expensive relative to
     * a boolean: it may enumerate parties. It is not allowed to throw — and if it does,
     * {@link TeamProviders} reads that as {@code false} and says so in the log.
     *
     * @param server the server being resolved for; never null
     */
    boolean isPresent(MinecraftServer server);

    /**
     * Builds the manager for this server. Only called when {@link #isPresent} returned true.
     *
     * <p>Separate from {@code isPresent} because a source that is merely <i>asking</i> whether it
     * applies may not be able to construct anything yet, and because resolution should not pay for a
     * manager it is about to discard.
     */
    TeamManager create(MinecraftServer server);
}
