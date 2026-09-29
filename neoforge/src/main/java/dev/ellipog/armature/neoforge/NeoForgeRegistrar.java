package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.api.registry.Registrar;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link Registrar} on NeoForge, where registration is deferred rather than immediate.
 *
 * <p>Modifying a vanilla registry during mod construction is forbidden on NeoForge — the
 * game is still assembling them, and a write now is either ignored or fatal depending on
 * which registry it is. So every call here goes into a {@link DeferredRegister}, which
 * queues the entry and creates it during NeoForge's registration phase. That is why
 * {@link Registrar#register} takes a supplier: the object may not exist until much later.
 *
 * <p>One {@code DeferredRegister} per registry per mod, created on first use and kept, since
 * NeoForge wants a single register call per registry rather than one per entry.
 */
public final class NeoForgeRegistrar implements Registrar {

    private final IEventBus modEventBus;
    private final String modId;
    private final Map<Registry<?>, DeferredRegister<?>> byRegistry = new LinkedHashMap<>();

    public NeoForgeRegistrar(IEventBus modEventBus, String modId) {
        this.modEventBus = modEventBus;
        this.modId = modId;
    }

    @Override
    public Registrar forMod(String modId) {
        return new NeoForgeRegistrar(modEventBus, modId);
    }

    @Override
    public <T> void register(Registry<T> registry, ResourceLocation id, Supplier<? extends T> value) {
        if (!modId.equals(id.getNamespace())) {
            throw new IllegalArgumentException("Cannot register " + id + " through the registrar for '" + modId
                    + "'. The namespace of a registry name is the mod that owns it, so those two have to agree.");
        }
        deferredFor(registry).register(id.getPath(), value);
    }

    @SuppressWarnings("unchecked")
    private <T> DeferredRegister<T> deferredFor(Registry<T> registry) {
        DeferredRegister<?> existing = byRegistry.get(registry);
        if (existing != null) {
            return (DeferredRegister<T>) existing;
        }

        // Registry#key is the registry's own name, and is what DeferredRegister wants. The
        // cast is unavoidable: the map is keyed by the registry instance, so the value type
        // is erased, but it was created from this very registry.
        ResourceKey<? extends Registry<T>> key = (ResourceKey<? extends Registry<T>>) registry.key();

        DeferredRegister<T> created = DeferredRegister.create(key, modId);
        created.register(modEventBus);
        byRegistry.put(registry, created);
        return created;
    }
}
