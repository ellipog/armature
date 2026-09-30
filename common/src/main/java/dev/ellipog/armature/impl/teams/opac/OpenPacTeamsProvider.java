package dev.ellipog.armature.impl.teams.opac;

import dev.ellipog.armature.impl.teams.TeamProvider;

/**
 * The class that holds Open Parties and Claims' id and its adapter — the same shape as the FTB one,
 * for the same reason, and worth reading that one's comment for the full argument.
 *
 * <p>In one line: Open Parties and Claims is a soft dependency, so {@code TeamProviders} only builds
 * its adapter inside an {@code isModLoaded} guard — and a guard alone is not enough, because the JVM
 * verifies a class before any of it runs and verifying a class that constructs {@code OpenPacTeams}
 * means loading it and therefore resolving OPAC's own packages. Here the field is declared as
 * {@link TeamProvider} and {@link #MOD_ID} is a compile-time constant, so this class can be named
 * from anywhere at all and OPAC's classes are loaded only when {@link #INSTANCE} is first read —
 * inside the guard.
 *
 * <p>The OPAC adapter is a <i>consumer</i> and nothing else: {@code OpenPacTeams.provider()} builds
 * something that reads parties, with no registration anywhere, because OPAC's addon gateway offers a
 * listener manager for claims and none for parties. See that class.
 */
public final class OpenPacTeamsProvider {

    /** OPAC's mod id, as its own fabric.mod.json and neoforge toml declare it. */
    public static final String MOD_ID = "openpartiesandclaims";

    /** The adapter, built on first read — see the class comment for why that timing matters. */
    public static final TeamProvider INSTANCE = OpenPacTeams.provider();

    private OpenPacTeamsProvider() {
    }
}
