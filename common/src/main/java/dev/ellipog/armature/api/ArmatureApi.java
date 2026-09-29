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
 * each loader, which runs before any other mod's code, so the accessors are always ready by
 * the time your mod's entry point runs.
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
