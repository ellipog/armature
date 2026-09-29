package dev.ellipog.armature.api.client;

import net.minecraft.resources.ResourceLocation;

/**
 * A loader's key-mapping system, reduced to the two things Armature needs from it.
 *
 * <p>Deliberately dumb: it takes declarations and polls them, and owns no state that
 * common code can see. The reason it is an interface at all is that the two loaders
 * disagree about <b>when</b> a key mapping may be created, and that disagreement cannot be
 * papered over:
 *
 * <ul>
 *   <li><b>Fabric</b> lets you construct a {@code KeyMapping} and hand it to
 *       {@code KeyBindingHelper} at any point during client init.</li>
 *   <li><b>NeoForge</b> requires the object to be created inside its
 *       {@code RegisterKeyMappingsEvent}, well after mod construction. Creating one earlier
 *       does not register it, and the key silently never appears in Controls.</li>
 * </ul>
 *
 * <p>So {@link #declare} receives a declaration and is free to hold it until the loader's
 * own moment arrives. Common code calls it once, during construction, and never learns
 * which of the two happened.
 *
 * <p>Only ever loaded on the client, from the loader's client subproject. The {@code Object}
 * -free signatures are possible because a declaration carries no game type at all — just
 * strings, an int, and a callback.
 */
public interface KeyMappingBackend {

    /**
     * Takes a key mapping declaration. The implementation registers it whenever its loader
     * permits, and must eventually call {@code onPress} once per press.
     *
     * @param id                     gives the key its name — {@code key.<namespace>.<path>} —
     *                               which is also where the translatable text goes
     * @param defaultKeyCode         a GLFW key code; the player can rebind it afterwards
     * @param categoryTranslationKey the group it appears under in Controls
     * @param onPress                runs on the client thread, once per press
     */
    void declare(ResourceLocation id, int defaultKeyCode, String categoryTranslationKey, Runnable onPress);

    /**
     * Called once per client tick. Must fire {@code onPress} for every key pressed since the
     * last call, consuming the press so one keypress never runs a callback twice.
     */
    void poll();
}
