package dev.ellipog.armature.api.client;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Client-side plumbing: key mappings, and screens opened by name.
 *
 * <p>Two jobs, both of them about letting common code say what should happen on the client
 * without itself touching a client class.
 *
 * <h2>Key mappings</h2>
 * A mod declares a key with {@link #registerKeyMapping} and says what to do when it is
 * pressed. Armature creates the loader's key mapping at whatever moment that loader requires
 * and calls the callback on the client thread.
 *
 * <p><b>Declare keys before the loader's key-mapping phase</b>, which in practice means from
 * your client entry point or earlier — not from a screen, not from a first tick. NeoForge
 * will only accept a key mapping inside one specific startup event; declaring early and
 * letting Armature place it is the only ordering that works on both loaders.
 *
 * <h2>Screens</h2>
 * <p>{@link #openScreen} opens a screen by id, and {@link #installScreenOpener} is how the
 * client half supplies the thing that actually does it. This indirection is the whole point:
 * an item is a common class, present on a dedicated server, and it must be able to say
 * "open the book screen" without the class it calls naming {@code net.minecraft.client}. So
 * this class mentions no client type at all, and the real registry lives in
 * {@code dev.ellipog.armature.client.ArmatureScreens}, which only a client ever loads.
 *
 * <p>Calling {@link #openScreen} on a server is safe and returns {@code false} — no client
 * ever installed an opener. It is still better to guard on {@code level.isClientSide} than
 * to rely on that.
 */
public final class ArmatureClient {

    private ArmatureClient() {
    }

    /**
     * Opens a screen by id. Implemented by the client half; deliberately names no client type
     * so that this interface can be mentioned from common code.
     */
    public interface ScreenOpener {
        /**
         * @return true if a screen was registered under this id and was opened
         */
        boolean open(ResourceLocation id);
    }

    private record Declaration(ResourceLocation id, int defaultKeyCode, String category, Runnable onPress) {
        Declaration {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(onPress, "onPress");
        }
    }

    /** Declared but not yet handed to a backend: the server, or the client is still starting. */
    private static final List<Declaration> PENDING = new ArrayList<>();

    private static KeyMappingBackend backend;
    private static ScreenOpener screenOpener;

    // ------------------------------------------------------------------
    // Key mappings
    // ------------------------------------------------------------------

    /**
     * Declares a key mapping.
     *
     * @param id             names the key: the translation key becomes
     *                       {@code key.<namespace>.<path>}, and the namespace is expected to
     *                       be your mod id
     * @param defaultKeyCode a GLFW key code — read {@code InputConstants.KEY_B} in your client
     *                       code, since it is a client class
     * @param category       the group it appears under in Controls, itself a translation key
     * @param onPress        runs on the client thread, once per press
     */
    public static void registerKeyMapping(ResourceLocation id, int defaultKeyCode, String category, Runnable onPress) {
        Declaration declaration = new Declaration(id, defaultKeyCode, category, onPress);
        KeyMappingBackend installed = backend;
        if (installed == null) {
            PENDING.add(declaration);
        } else {
            hand(installed, declaration);
        }
    }

    /**
     * Hands over the loader's key-mapping system and forwards everything declared so far.
     *
     * <p>Called once, by the loader's client initialiser. <b>Internal.</b>
     */
    public static void install(KeyMappingBackend backend) {
        if (ArmatureClient.backend != null) {
            throw new IllegalStateException("Armature's key-mapping backend was already installed; the client "
                    + "initialiser ran twice.");
        }
        ArmatureClient.backend = Objects.requireNonNull(backend, "backend");
        for (Declaration declaration : PENDING) {
            hand(backend, declaration);
        }
        PENDING.clear();
    }

    /**
     * Polls every declared key. Called once per client tick by the loader. <b>Internal.</b>
     */
    public static void tick() {
        KeyMappingBackend installed = backend;
        if (installed != null) {
            installed.poll();
        }
    }

    /** The translation key a mapping id maps to — {@code key.example.open_my_screen}. */
    public static String translationKey(ResourceLocation id) {
        return "key." + id.getNamespace() + "." + id.getPath();
    }

    // ------------------------------------------------------------------
    // Screens
    // ------------------------------------------------------------------

    /** Called once, by the loader's client initialiser. <b>Internal.</b> */
    public static void installScreenOpener(ScreenOpener opener) {
        if (ArmatureClient.screenOpener != null) {
            throw new IllegalStateException("Armature's screen opener was already installed; the client initialiser "
                    + "ran twice.");
        }
        ArmatureClient.screenOpener = Objects.requireNonNull(opener, "opener");
    }

    /**
     * Opens the screen registered under {@code id}.
     *
     * @return true if there was one. False on a server, and false if no screen is registered —
     *         which is a bug in the calling mod rather than something to handle quietly, so it
     *         is also logged.
     */
    public static boolean openScreen(ResourceLocation id) {
        ScreenOpener opener = screenOpener;
        if (opener == null) {
            return false;
        }
        return opener.open(id);
    }

    private static void hand(KeyMappingBackend backend, Declaration declaration) {
        backend.declare(declaration.id(), declaration.defaultKeyCode(), declaration.category(), declaration.onPress());
    }
}
