package dev.ellipog.armature.fabric;

import dev.ellipog.armature.api.registry.Registrar;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/**
 * {@link Registrar} on Fabric, where registration is immediate and has no deferred phase.
 *
 * <p>Which makes this nearly trivial, and that is the point: {@link #register} really does
 * register, so a bug in a mod's registration shows up when the mod declares it rather than
 * at some later point during startup with no obvious cause.
 */
public final class FabricRegistrar implements Registrar {

    private final String modId;

    public FabricRegistrar(String modId) {
        this.modId = modId;
    }

    @Override
    public Registrar forMod(String modId) {
        return new FabricRegistrar(modId);
    }

    @Override
    public <T> void register(Registry<T> registry, ResourceLocation id, Supplier<? extends T> value) {
        if (!modId.equals(id.getNamespace())) {
            throw new IllegalArgumentException("Cannot register " + id + " through the registrar for '" + modId
                    + "'. The namespace of a registry name is the mod that owns it, so those two have to agree.");
        }
        Registry.register(registry, id, value.get());
    }
}
