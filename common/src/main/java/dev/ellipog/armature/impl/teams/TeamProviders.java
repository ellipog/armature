package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.event.ArmatureEvents;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.impl.teams.ftb.FtbTeamsProvider;
import dev.ellipog.armature.impl.teams.opac.OpenPacTeamsProvider;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Which teams a server actually has, and how that gets decided.
 *
 * <h2>The question this answers</h2>
 *
 * <p>Teams are the primitive a caller keys shared state by. On a server with nothing else installed
 * they are Armature's own, stored in the world. On a server running a parties mod they are already
 * somebody else's, and the player chose that: they formed the party with that mod's command, through
 * that mod's screen, and it is in that mod's data file. Creating a <i>second</i>, invisible set of
 * teams underneath means the party panel and the parties mod disagree about who is together, and
 * whichever one the player looks at is right.
 *
 * <p>So there is one resolution per server, made once, and every caller asks for the result.
 *
 * <h2>The order, and why it is that order</h2>
 *
 * <ol>
 *   <li><b>An explicit registration.</b> A mod that has registered an adapter has said something
 *       this file cannot know: that <i>its</i> parties are the parties. {@link #register} is the
 *       escape hatch for a mod Armature ships no adapter for, and an explicit statement beats a
 *       guess.</li>
 *   <li><b>FTB Teams</b>, if it is loaded, its manager is up, and it holds at least one party.</li>
 *   <li><b>Open Parties and Claims</b>, on the same three conditions.</li>
 *   <li><b>Armature's own store</b>, which is always available and needs nothing installed.</li>
 * </ol>
 *
 * <p><b>Ties go to FTB</b>, because it is first, and the tie is a real possibility: both mods can be
 * installed at once, and neither defers to the other. The honest statement is that this is a
 * <b>heuristic</b> — see the note at the end of this comment — and it is reported rather than
 * silently resolved, so a server operator reading the log knows which one their players are getting.
 *
 * <p>"Holds at least one party" is the third condition of each, and it is not a detail. FTB Teams
 * keeps a permanent team per player, created on first join and never removed, and
 * {@code isManagerLoaded()} becomes true as soon as its manager is up — on a server where everybody
 * plays alone. Resolving to FTB there would mean a server with no parties in it answering every team
 * question with a party-shaped team, so a source that has nothing to offer is passed over rather
 * than preferred. Each adapter also filters its own list, for the same reason: an unfiltered FTB
 * {@code getTeams()} reports every player who has ever logged in as a party.
 *
 * <h2>The two built-ins are named by a factory, not by an instance</h2>
 *
 * <p>{@link Builtin} holds a mod id and a {@link Supplier} rather than a ready-made provider, and
 * that is load-bearing rather than stylistic. Both adapters name third-party types in their method
 * signatures, and the JVM loads and verifies a class before any of it runs — so a field or a local
 * holding a {@code new FtbTeams()} would load FTB's classes while verifying the branch that
 * correctly decided not to use them. With a factory, the only names this class puts in its own
 * bytecode are the two provider classes, whose fields are declared as {@link TeamProvider} and
 * which therefore resolve nothing third-party at all. Each adapter's own class is loaded on first
 * read of its provider's {@code INSTANCE}, which happens inside the guard.
 *
 * <p>The mod ids come from those provider classes, so there is one spelling of each, and neither
 * compiles to a reference that loads anything.
 *
 * <h2>Lazy, and why that is not an optimisation</h2>
 *
 * <p>{@link #of} resolves on first ask and remembers it. The probe at
 * {@link ArmatureEvents#SERVER_STARTED} exists to get the answer into the log early and to clear it
 * on shutdown — <b>not to be a prerequisite</b>. Server lifecycle events are fired by loader code in
 * each loader's subproject, so <b>no test JVM ever fires them</b>, and a resolver that waited for one
 * would hand every test a server with no teams. The lifecycle events are weather, not a contract.
 *
 * <h2>The ordering of the two failures this arrangement can have</h2>
 *
 * <p>A source that throws when asked whether it is present is treated as absent, and the log says
 * which source and why. The alternative — letting it propagate — means that a parties mod which
 * throws from its own API during a server's startup takes teams away from a server that has a
 * perfectly good fallback sitting behind it.
 *
 * <h2>The caveat, stated plainly</h2>
 *
 * <p><b><a id="heuristic">When both parties mods are installed, this choice is a heuristic the public
 * API cannot resolve.</a></b> OPAC exposes its parties through an addon gateway that hands out a
 * listener manager for claims and nothing for parties, and the one call that <i>would</i> settle the
 * question — {@code PlayerPartySystemManager.getPrimarySystemName()}, which names which of its
 * registered systems is primary, and would also reveal that OPAC can read FTB's parties through its
 * own bridge — lives on an internal type rather than on the API. Preferring FTB is a reasonable guess
 * when neither mod has told us anything, and a config key is the real answer: the server operator is
 * the only one who knows which mod their players actually use.
 */
public final class TeamProviders {

    /**
     * One built-in source: the mod to ask about, and how to build the provider once it is there.
     *
     * <p>The factory is deferred so that loading this class does not load either adapter — see the
     * class comment. A record rather than a field pair because the two travel together and there will
     * be no third thing.
     */
    private record Builtin(String modId, Supplier<TeamProvider> factory) {
    }

    /**
     * The built-in sources, in precedence order, ahead of the stored one.
     *
     * <p>The mod ids are constants on the provider classes, so a probe string and the id the chain
     * reports cannot drift apart.
     */
    private static final List<Builtin> BUILTINS = List.of(
            new Builtin(FtbTeamsProvider.MOD_ID, () -> FtbTeamsProvider.INSTANCE),
            new Builtin(OpenPacTeamsProvider.MOD_ID, () -> OpenPacTeamsProvider.INSTANCE));

    /**
     * One resolved manager per server.
     *
     * <p>Keyed by the server instance rather than held in a single field, because a process can have
     * more than one server — a client with an integrated world open, then a disconnect and a fresh
     * world — and a static field would be the previous server's teams. Cleared on
     * {@link ArmatureEvents#SERVER_STOPPING} so a stopped server's manager is not retained by the key
     * that can never be asked again.
     */
    private static final Map<MinecraftServer, TeamManager> RESOLVED = new ConcurrentHashMap<>();

    /** Registrations, in order, ahead of the built-ins. See {@link #register}. */
    private static final List<TeamProvider> REGISTERED = new CopyOnWriteArrayList<>();

    private static volatile boolean watching;

    private TeamProviders() {
    }

    /**
     * Adds a source, ahead of the built-ins, for a mod Armature ships no adapter for.
     *
     * <p>The escape hatch, and deliberately public: Armature can only ever ship an adapter for the
     * parties mods somebody has asked it to support, and the mod that knows about its own parties is
     * the one that should be writing this adapter. It goes first because a mod registering one has
     * said something no presence check can discover — that its parties are the answer.
     *
     * <p>Clears what has already been resolved, because a registration arriving after a server has
     * been asked is not something to ignore: the whole point of registering is to change the answer.
     */
    public static void register(TeamProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("a team provider cannot be null");
        }
        REGISTERED.add(provider);
        RESOLVED.clear();
        Constants.LOG.info("armature: team provider '{}' registered", provider.id());
    }

    /**
     * The teams on this server, resolving on first ask.
     *
     * <p>Callable from the server thread; also callable <i>before</i> a server exists in a test, where
     * it resolves to the stored manager because nothing else is loaded. Neither is a special case in
     * the code — both fall out of asking the sources in order.
     */
    public static TeamManager of(MinecraftServer server) {
        watch();
        return RESOLVED.computeIfAbsent(server, TeamProviders::resolve);
    }

    /**
     * The source that <i>would</i> be chosen for a server, out of a given list.
     *
     * <p>Separated from {@link #of} because it is the whole decision and it takes no server state:
     * a test can hand it two fakes and assert which one wins, which is the only way to pin the
     * precedence down without installing two mods. See {@code TeamProvidersTest}.
     *
     * @return the first provider that says it is present; never null while the list is non-empty,
     *         which is why {@link #providers} puts the stored source last and that source always
     *         says yes
     * @throws IllegalStateException if nothing in the list is present, which means the caller built a
     *         list with no fallback in it
     */
    static TeamManager choose(MinecraftServer server, List<TeamProvider> candidates) {
        TeamProvider winner = null;
        List<String> alsoPresent = new ArrayList<>();

        for (TeamProvider candidate : candidates) {
            if (!present(candidate, server)) {
                continue;
            }
            if (winner == null) {
                winner = candidate;
            }
            else {
                alsoPresent.add(candidate.id());
            }
        }

        if (winner == null) {
            throw new IllegalStateException("no team provider is available for this server, and the "
                    + "list has no fallback in it. The stored provider says yes unconditionally, so "
                    + "it belongs at the end of every chain.");
        }

        TeamManager manager = winner.create(server);
        Constants.LOG.info("armature: teams come from '{}'", manager.name());
        for (String loser : alsoPresent) {
            // The both-installed case, said out loud. It is not merely information: the choice above
            // is a heuristic, and this is the only place it becomes visible to anybody. See the class
            // comment's note on getPrimarySystemName for what the API would need in order to answer it
            // properly.
            Constants.LOG.warn("armature: '{}' is also present and lower in precedence, so its teams "
                    + "are not being used. Both parties mods installed is a choice this cannot make "
                    + "for you -- see TeamProviders", loser);
        }
        return manager;
    }

    /**
     * The chain for this server: registrations, then the built-ins in order, then the store.
     *
     * <p>Each built-in is asked whether its mod is loaded before its factory runs, which is what
     * keeps the adapter classes unloaded on a server that has neither mod — see {@link Builtin}.
     */
    static List<TeamProvider> providers() {
        List<TeamProvider> chain = new ArrayList<>(REGISTERED);

        for (Builtin builtin : BUILTINS) {
            if (loaded(builtin.modId())) {
                chain.add(builtin.factory().get());
            }
        }

        chain.add(StoredTeamManager.provider());
        return chain;
    }

    private static TeamManager resolve(MinecraftServer server) {
        return choose(server, providers());
    }

    /**
     * Installs the two lifecycle hooks, once.
     *
     * <p>Both are conveniences and neither is on the path to an answer — see the class comment. The
     * start hook resolves early so the log says which source a server is using before anybody asks,
     * because the useful moment to find out is startup rather than the first time a player runs a
     * command. The stop hook drops the cache so a stopped server is not retained.
     */
    private static void watch() {
        if (watching) {
            return;
        }
        synchronized (TeamProviders.class) {
            if (watching) {
                return;
            }
            watching = true;
            ArmatureEvents.SERVER_STARTED.register(TeamProviders::of);
            ArmatureEvents.SERVER_STOPPING.register(server -> {
                RESOLVED.remove(server);
                Constants.LOG.info("armature: teams cache cleared for a stopping server");
            });
        }
    }

    private static boolean present(TeamProvider provider, MinecraftServer server) {
        try {
            return provider.isPresent(server);
        }
        catch (RuntimeException | LinkageError e) {
            // A source we cannot ask is a source we do not use. Logged rather than swallowed, because
            // the interesting case is a parties mod whose API throws during startup -- and the person
            // who can do something about that is reading the log, not the stack trace of a crash.
            //
            // LinkageError as well as RuntimeException, and that is not defensive padding: the whole
            // reason this file defers the factories is that loading an adapter can fail to link when
            // its mod is absent or its mixin did not apply, and the answer is the same either way.
            Constants.LOG.warn("armature: team provider '{}' could not be asked whether it is present "
                    + "({}); treating it as absent", provider.id(), e.toString());
            return false;
        }
    }

    /**
     * Whether a mod is loaded, without assuming the platform layer is installed.
     *
     * <p>{@code ArmatureApi.platform()} throws when nothing has installed it, and one legitimate
     * caller of {@link #of} is a test that installs only what it needs — so a platform that is not
     * there is read as "no mods are loaded", which is exactly true. It is also the honest answer for
     * the real case: nothing can be loaded if the loader has not reached Armature yet.
     */
    private static boolean loaded(String modId) {
        try {
            return ArmatureApi.platform().isModLoaded(modId);
        }
        catch (RuntimeException e) {
            Constants.LOG.debug("armature: cannot ask whether '{}' is loaded ({}); assuming not",
                    modId, e.toString());
            return false;
        }
    }
}
