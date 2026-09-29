package dev.ellipog.armature.api.platform;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Everything that genuinely differs between Fabric and NeoForge, behind one interface.
 *
 * <p>This is deliberately small. Each method here is one that has no vanilla equivalent and
 * no way to be answered from common code alone, which is the only justification for the
 * interface existing. Anything that can be done against vanilla does not belong here.
 *
 * <p>Implemented once per loader, in that loader's subproject. Nothing in {@code common/}
 * may reference an implementation — reach it through
 * {@code ArmatureApi.platform()}.
 */
public interface ArmaturePlatform {

    /** Which loader this is. */
    PlatformKind kind();

    /** {@link PlatformKind#displayName()} — "Fabric" or "NeoForge". */
    default String name() {
        return kind().displayName();
    }

    /**
     * Whether a mod is loaded. Safe to call for any id, including mods that are not
     * installed and ids that do not exist.
     *
     * <p>Asking about a mod other than your own means you are writing a soft integration.
     * Everything on that path must still work when this returns {@code false}.
     */
    boolean isModLoaded(String modId);

    /**
     * The version a loaded mod reports, as it reports it — so it is a string, not a parsed
     * version, because loaders disagree on what a version even is.
     *
     * <p>Empty when the mod is not loaded. Use it for log lines and version checks, never
     * for anything that has to be right.
     */
    Optional<String> modVersion(String modId);

    /**
     * Whether this is the client. On a dedicated server this is {@code false}, and every
     * class that touches {@code net.minecraft.client.*} must be unreachable behind this
     * check — otherwise the class loads on the server and the server dies with a
     * {@link NoClassDefFoundError} that names a class you never wrote.
     */
    boolean isClient();

    /** Whether this is a development environment rather than a player's install. */
    boolean isDevelopmentEnvironment();

    /** "development" or "production" — for log lines. */
    default String environmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /** The instance folder — the one with {@code mods/} and {@code config/} in it. */
    Path gameDir();

    /** The config folder, which every loader puts at {@code gameDir/config}. */
    Path configDir();

    /**
     * A folder for this mod inside the config folder. Not created — call
     * {@link java.nio.file.Files#createDirectories} yourself, because only you know whether
     * you need it.
     */
    default Path configDir(String child) {
        return configDir().resolve(child);
    }
}
