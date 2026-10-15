package dev.ellipog.armature.neoforge;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.api.client.KeyMappingBackend;
import dev.ellipog.armature.client.ArmatureScreens;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge's client half.
 *
 * <p>Carries {@code dist = Dist.CLIENT}, so NeoForge constructs it only on the client and a
 * dedicated server never loads this class — and therefore never tries to load
 * {@code net.minecraft.client.KeyMapping}. That is why it is a second {@code @Mod} for the
 * same mod id rather than a branch inside the main one.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class ArmatureNeoForgeClient {

    public ArmatureNeoForgeClient(IEventBus modEventBus) {
        NeoForgeKeyMappings backend = new NeoForgeKeyMappings();

        // Mod bus: RegisterKeyMappingsEvent is a startup event, so it belongs there.
        modEventBus.addListener(backend::onRegisterKeyMappings);

        // Game bus: ticking a running client is not a startup concern.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            backend.poll();
        });

        // Installing forwards everything declared during mod construction into the queue
        // above, which is still waiting for RegisterKeyMappingsEvent to fire.
        ArmatureClient.install(backend);

        // And this is what makes opening a screen by id work, for us and for every mod built
        // on Armature — including the common-code path an item takes.
        ArmatureScreens.install();

        // Before the first frame, and here rather than in the common initialiser: this is client
        // state read by client drawing, and a dedicated server has no window to look at.
    }

    /**
     * NeoForge will not accept a {@link KeyMapping} created outside
     * {@link RegisterKeyMappingsEvent} — one made earlier is simply never registered, and the
     * key never appears in Controls with nothing logged to say why. So {@code declare} only
     * records the request, and the objects are built when the event arrives.
     */
    private static final class NeoForgeKeyMappings implements KeyMappingBackend {

        private final List<Request> queue = new ArrayList<>();
        private final List<Bound> bound = new ArrayList<>();
        // One Category per id: `KeyMapping.Category.register` throws on a duplicate, and a
        // mod with two keys in one group declares the same id twice — so the first wins and
        // the rest reuse it, same keep-first shape as the Fabric half.
        private final java.util.Map<Identifier, KeyMapping.Category> categories = new java.util.HashMap<>();

        private record Request(Identifier id, int defaultKeyCode, Identifier category, Runnable onPress) {
        }

        private record Bound(KeyMapping mapping, Runnable onPress) {
        }

        @Override
        public void declare(Identifier id, int defaultKeyCode, Identifier category, Runnable onPress) {
            queue.add(new Request(id, defaultKeyCode, category, onPress));
        }

        void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {            for (Request request : queue) {
                KeyMapping mapping = new KeyMapping(
                        ArmatureClient.translationKey(request.id()),
                        InputConstants.Type.KEYBOARD,
                        request.defaultKeyCode(),
                        categories.computeIfAbsent(request.category(), KeyMapping.Category::register));
                event.register(mapping);
                bound.add(new Bound(mapping, request.onPress()));
            }
            queue.clear();
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
