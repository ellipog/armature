package dev.ellipog.armature.fabric;

import dev.ellipog.armature.api.energy.EnergyAccess;

/**
 * The class that holds Team Reborn Energy's id and its adapter, and names no TR type while
 * doing it — the same holder shape as the parties providers, for the same JVM reason.
 *
 * <p>TR Energy is a <b>soft</b> dependency: a Fabric instance without it keeps the seam's
 * silent zero, not a crash. So the entry point only reads {@link #INSTANCE} inside an
 * {@code isModLoaded} guard — and a guard alone is not enough, because the JVM verifies a
 * class before any of it runs and verifying a method that constructs {@link FabricEnergy}
 * means loading it and therefore resolving TR's own packages.
 *
 * <p>Three things, and each one is load-bearing:
 *
 * <ul>
 *   <li>{@link #INSTANCE} is declared as {@link EnergyAccess}, not as {@code FabricEnergy}.
 *       Resolving a field only needs the type it is <i>declared</i> as, so the field itself
 *       costs nothing.</li>
 *   <li>The initialiser runs {@code new FabricEnergy()} — so TR's classes are loaded by the
 *       static initialiser of <i>this</i> class, on first read of {@link #INSTANCE}, which
 *       happens inside the guard. Class loading is triggered by the access, not by this class
 *       being verified.</li>
 *   <li>{@link #MOD_ID} is a compile-time constant, so {@code javac} inlines it into every
 *       caller. Reading the id therefore never touches this class at all — which is what lets
 *       the entry point name the probe string without loading anything.</li>
 * </ul>
 */
public final class TrEnergyProvider {

    /** TR Energy's mod id, as its own {@code fabric.mod.json} declares it. */
    public static final String MOD_ID = "team_reborn_energy";

    /** The adapter, built on first read — see the class comment for why that timing matters. */
    public static final EnergyAccess INSTANCE = new FabricEnergy();

    private TrEnergyProvider() {
    }
}
