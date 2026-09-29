package dev.ellipog.armature.client;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.client.ArmatureClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The registry of screens that can be opened by id.
 *
 * <p><b>Client only.</b> This class names {@link Screen}, so it must never be loaded on a
 * dedicated server. Nothing loads it except a loader's client initialiser — which is why it
 * lives here rather than beside {@link ArmatureClient}: that class is mentionable from
 * common code, this one is not.
 *
 * <p>Screens are registered rather than referenced so that a mod can open its own screen from
 * common code. An item is a common class and runs on both sides; it says
 * {@code ArmatureClient.openScreen(QUEST_BOOK)} and never learns whether a screen exists.
 */
public final class ArmatureScreens implements ArmatureClient.ScreenOpener {

    private static final Map<ResourceLocation, Supplier<Screen>> FACTORIES = new LinkedHashMap<>();

    private ArmatureScreens() {
    }

    /**
     * Installs the registry and makes {@link ArmatureClient#openScreen} work.
     *
     * <p>Called once, from each loader's client initialiser. <b>Internal.</b>
     */
    public static void install() {
        ArmatureClient.installScreenOpener(new ArmatureScreens());
    }

    /**
     * Registers a screen.
     *
     * <p>The supplier must build a <b>new</b> screen each time: a {@link Screen} holds widget
     * state and a tick counter, and reopening a cached instance produces a screen that
     * remembers where you were, or one that is already closed. Register from the client
     * initialiser, alongside key mappings.
     *
     * @throws IllegalArgumentException if the id is already taken, rather than silently
     *         replacing the existing screen — two mods colliding here is a bug worth hearing
     *         about
     */
    public static void register(ResourceLocation id, Supplier<Screen> factory) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        if (FACTORIES.containsKey(id)) {
            throw new IllegalArgumentException("A screen is already registered under " + id
                    + ". Ids are namespaced by mod, so this means the same mod registered twice.");
        }
        FACTORIES.put(id, factory);
    }

    @Override
    public boolean open(ResourceLocation id) {
        Supplier<Screen> factory = FACTORIES.get(id);
        if (factory == null) {
            Constants.LOG.warn("No screen is registered under {}, so nothing was opened. Known screens: {}",
                    id, FACTORIES.keySet());
            return false;
        }
        Minecraft.getInstance().setScreen(factory.get());
        return true;
    }
}
