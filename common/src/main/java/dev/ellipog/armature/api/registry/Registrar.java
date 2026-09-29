package dev.ellipog.armature.api.registry;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/**
 * Registers game objects — items, blocks, sounds, whatever takes a {@link Registry}.
 *
 * <p>The two loaders work in genuinely different ways, and this is the seam over that.
 * Fabric registers immediately; NeoForge forbids touching a registry during construction
 * and wants entries queued on a per-mod bus instead. Common code cannot tell the
 * difference: it calls {@link #register} and the loader decides when that becomes real.
 *
 * <p><b>Timing is not negotiable.</b> Call {@link #register} while your mod is being
 * constructed — from your entry point, before the game finishes loading. On NeoForge,
 * registering later throws, because the registration window has closed by then.
 */
public interface Registrar {

    /**
     * A registrar that files entries under {@code modId}.
     *
     * <p>Necessary rather than fussy: NeoForge keeps one registration queue per mod, on
     * that mod's own event bus, so an entry's namespace and the queue it travels on have to
     * agree. Passing the id makes the mismatch impossible instead of merely unlikely.
     *
     * <p>On Fabric this only records the id to validate against. Either way,
     * {@link #register} rejects an id whose namespace is not {@code modId}, because that is
     * a copy-paste mistake that is otherwise silent until someone tries to
     * {@code /give} the wrong name.
     */
    Registrar forMod(String modId);

    /**
     * Queues {@code value} to be registered as {@code id}.
     *
     * <p>{@code value} is a supplier rather than an instance because NeoForge may need to
     * build the object later than you called this. Do not do anything with the object
     * inside the supplier beyond constructing it — no registration, no lookups, no
     * touching other mods.
     *
     * @throws IllegalArgumentException if {@code id}'s namespace does not match
     *         {@link #forMod}'s mod id
     */
    <T> void register(Registry<T> registry, ResourceLocation id, Supplier<? extends T> value);
}
