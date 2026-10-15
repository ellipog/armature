package dev.ellipog.armature.api;

import dev.ellipog.armature.api.platform.ArmaturePlatform;
import dev.ellipog.armature.api.registry.Registrar;

/**
 * The way into Armature.
 *
 * <p>Everything a mod needs from the platform layer is reached from here:
 *
 * <pre>{@code
 * ArmaturePlatform platform = ArmatureApi.platform();
 * ArmatureApi.registrar().forMod("mymod").register(BuiltInRegistries.ITEM, id, MyItem::new);
 * }</pre>
 *
 * <p>{@link #install} is Armature's own business. It is called by Armature's entry point for
 * each loader, and by nothing else. On Fabric the entry point also tolerates being run twice:
 * {@code main} order between mods is not dependency-ordered (loader 0.19 runs a dependent's
 * {@code main} before its dependency's, nondeterministically — observed both ways across
 * back-to-back boots on the same thread and classloader), so a dependent triggers Armature's
 * entry point early through the loader when it has not run yet, and the loader's own later
 * call becomes a no-op. Either order works, and neither can install twice.
 */
public final class ArmatureApi {

    private static ArmaturePlatform platform;
    private static Registrar registrar;

    private ArmatureApi() {
    }

    /**
     * Installs the loader implementation. <b>Internal</b> — called once, by Armature's own
     * entry point, and by nothing else.
     */
    public static void install(ArmaturePlatform platform, Registrar registrar) {
        if (ArmatureApi.platform != null) {
            throw new IllegalStateException("Armature's platform layer was already installed on "
                    + ArmatureApi.platform.name() + "; installing it twice means two loaders are present at once, "
                    + "which cannot happen.");
        }
        ArmatureApi.platform = platform;
        ArmatureApi.registrar = registrar;
    }

    /** Whether a loader has installed its platform yet — the entry point's fallback check. */
    public static boolean isInstalled() {
        return platform != null;
    }

    /**
     * The loader Armature is running on, and the handful of questions that differ between
     * loaders — see {@link ArmaturePlatform}.
     */
    public static ArmaturePlatform platform() {
        if (platform == null) {
            throw new IllegalStateException("Armature's platform layer is not installed. Something is reading it "
                    + "earlier than mod construction, or Armature itself failed to load.");
        }
        return platform;
    }

    /**
     * Registration of game objects. Requires {@link Registrar#forMod} to say which mod the
     * entries belong to — see that method for why.
     */
    public static Registrar registrar() {
        if (registrar == null) {
            throw new IllegalStateException("Armature's platform layer is not installed. Something is reading it "
                    + "earlier than mod construction, or Armature itself failed to load.");
        }
        return registrar;
    }
}
