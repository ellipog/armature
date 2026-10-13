package dev.ellipog.armature.fabric;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.api.client.KeyMappingBackend;
import dev.ellipog.armature.client.ArmatureScreens;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric's client half: a {@link KeyMappingBackend} and the tick that polls it.
 *
 * <p>A separate entry point from {@link ArmatureFabric} so that a dedicated server never
 * loads this class, and therefore never tries to load {@code net.minecraft.client.KeyMapping}.
 */
public final class FabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ArmatureClient.install(new FabricKeyMappings());
        ArmatureScreens.install();

        // Before the first frame, and here rather than in the common initialiser: this is client
        // state read by client drawing, and a dedicated server has no window to look at.

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ArmatureClient.tick();
        });
    }

    /**
     * Fabric is the easy case: a {@link KeyMapping} can be built and registered at any point
     * during client init, so {@code declare} does the work immediately and there is nothing
     * to defer.
     */
    private static final class FabricKeyMappings implements KeyMappingBackend {

        private final List<Bound> bound = new ArrayList<>();

        private record Bound(KeyMapping mapping, Runnable onPress) {
        }

        @Override
        public void declare(Identifier id, int defaultKeyCode, Identifier category, Runnable onPress) {
            // Keyboard scancodes on SDL (the old KEYSYM type is gone with GLFW): the int our API
            // carries is already one, read from `InputConstants.KEY_*` at the call site. The category
            // registers for Controls ordering; its label is `key.category.<namespace>.<path>`.
            KeyMapping mapping = new KeyMapping(
                    ArmatureClient.translationKey(id),
                    InputConstants.Type.KEYBOARD,
                    defaultKeyCode,
                    KeyMapping.Category.register(category));
            KeyMappingHelper.registerKeyMapping(mapping);
            bound.add(new Bound(mapping, onPress));
        }

        @Override
        public void poll() {
            // Indexed, not for-each: a press callback may declare another key.
            for (int i = 0; i < bound.size(); i++) {
                Bound entry = bound.get(i);
                if (entry.mapping().consumeClick()) {
                    entry.onPress().run();
                }
            }
        }
    }
}
