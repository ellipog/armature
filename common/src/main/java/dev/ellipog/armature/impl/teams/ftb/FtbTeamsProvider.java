package dev.ellipog.armature.impl.teams.ftb;

import dev.ellipog.armature.impl.teams.TeamProvider;

/**
 * The class that holds FTB Teams' id and its adapter, and names no FTB type while doing it.
 *
 * <h2>What would happen without it</h2>
 *
 * <p>FTB Teams is a <b>soft</b> dependency: a server without it must lose nothing. So
 * {@code TeamProviders} only builds this adapter inside an {@code isModLoaded("ftbteams")} branch,
 * which is the standard shape and reads as obviously safe. It is not, quite.
 *
 * <p>The JVM loads and <i>verifies</i> a class before any method in it runs, and verifying a method
 * that constructs a {@code FtbTeams} means confirming it is assignable to {@link TeamProvider} —
 * which means loading {@code FtbTeams}. Loading {@code FtbTeams} means verifying <i>its</i> methods,
 * and those have FTB's own types in their signatures. So on a server with no FTB Teams, verifying
 * the branch that was never taken would still be the thing that tries to load an FTB class, and the
 * result is a {@code NoClassDefFoundError} naming a mod that is not installed, thrown from a branch
 * that correctly decided not to run.
 *
 * <p>Whether that happens is a question about one JVM's verifier — a thing not to reason about and
 * hope, and not to discover from a player's crash report either. This class removes the question.
 *
 * <h2>How it removes it</h2>
 *
 * <p>Three things, and each one is load-bearing:
 *
 * <ul>
 *   <li>{@link #INSTANCE} is declared as {@link TeamProvider}, not as {@code FtbTeams}. Resolving a
 *       field only needs the type it is <i>declared</i> as, so the field itself costs nothing.</li>
 *   <li>The initialiser runs {@code new FtbTeams()} — so {@code FtbTeams} is loaded by the static
 *       initialiser of <i>this</i> class, on first read of {@link #INSTANCE}, which happens inside
 *       the guard. Class loading is triggered by the access, not by this class being verified.</li>
 *   <li>{@link #MOD_ID} is a compile-time constant, so {@code javac} inlines it into every caller.
 *       Reading the id therefore never touches this class at all — which is what lets
 *       {@code TeamProviders} name the probe string without loading anything. The `javap` on a
 *       compiled caller shows a {@code ldc} of the string and no {@code getstatic}; that is the
 *       whole reason this id is not a `static final` on the adapter.</li>
 * </ul>
 *
 * <p>This class is deliberately <b>not</b> a {@code TeamProvider} itself. It would be possible, and
 * the delegating methods would be dead code: what goes into the resolution chain is
 * {@link #INSTANCE}, and every caller of a provider already has one. A holder that also implements
 * the interface it holds is a second path to the same object, and the second path is the one that
 * would drift.
 */
public final class FtbTeamsProvider {

    /** FTB Teams' mod id, as its own {@code fabric.mod.json} and neoforge toml declare it. */
    public static final String MOD_ID = "ftbteams";

    /** The adapter, built on first read — see the class comment for why that timing matters. */
    public static final TeamProvider INSTANCE = new FtbTeams();

    private FtbTeamsProvider() {
    }
}
