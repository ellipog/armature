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
 *
 * <h2>That last sentence used to be false, and the reason is worth knowing</h2>
 *
 * <p>The promise above was written as though vanilla enforces it. It does not. Vanilla
 * <i>detects</i> a duplicate id and then declines to act on it — verbatim, from the 1.21.1
 * {@code MappedRegistry} this compiles against:
 *
 * <pre>{@code
 * if (this.byLocation.containsKey(key.location())) {
 *     Util.pauseInIde(new IllegalStateException("Adding duplicate key '" + key + "' to registry"));
 * }   // <- no `throw`. The return value is discarded.
 * }</pre>
 *
 * <p>and {@code Util.pauseInIde} <b>returns</b> the exception rather than throwing it:
 * {@code if (SharedConstants.IS_RUNNING_IN_IDE) { LOGGER.error(...); doPause(...); } return t;}. So
 * unless the game is being run from an IDE, the whole check is a no-op, and registration proceeds —
 * {@code this.byId.add(reference)}, a second entry under the same name with a different raw id.
 *
 * <p>What that produces is not a crash here. It is a registry that disagrees with every other side
 * about what id a name has, and the failure lands somewhere that names neither the mod nor the
 * registration. Observed, from a client joining a server: {@code fabric-registry-sync} refusing to
 * remap two <i>equal raw ids for one name</i> — the same item, registered twice, so it occupies id
 * 1334 and id 1335 while every other side knows it by only one of them. The probe's own message
 * names a map inside Minecraft's client and nothing about the mod that filled it in twice.
 *
 * <p>Two entry points calling the same registration is an easy mistake to make — every loader gives
 * a mod one per side, and a client runs both — so the distance between cause and symptom is the real
 * problem. Note the shape of the evidence deliberately rather than quoting it: naming the mod that
 * produced it here would make this file depend on a consumer, which is the one thing Armature may
 * never do. See `.utils/check_layering.py`.
 *
 * <p><b>So the check is done here instead</b>, which is where a general fix belongs rather than in
 * one mod's initialiser: registration on Fabric is immediate, so the registry can be asked directly
 * and the answer is authoritative. A duplicate now fails at construction, naming the mod and the id.
 * That is a behaviour change for any mod that registered twice, and it is the intended one — there
 * is no legitimate reason to register one id twice, and the alternative is silent corruption whose
 * symptom appears in another subsystem.
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

        // Asked of the registry rather than of a set kept here, and the difference matters: forMod
        // hands back a fresh instance each call, so a field on this class would miss a second
        // registration made through a different registrar for the same mod. The registry is the one
        // thing every caller shares.
        //
        // `containsKey` is on the Registry interface itself -- checked, not assumed -- which is why
        // this compiles from a Registrar that holds a Registry<T> rather than a MappedRegistry<T>.
        if (registry.containsKey(id)) {
            throw new IllegalStateException("'"
                    + modId + "' tried to register " + id + " twice. A registry holds one entry per id, so "
                    + "vanilla would have added a second item under the same name with a different raw id "
                    + "— silently, because its own duplicate check only reports inside an IDE. That "
                    + "mismatch is then blamed on whatever tries to use the registry across a network "
                    + "boundary. Register once: the usual cause is two entry points doing the same work, "
                    + "and a client runs one per side.");
        }

        Registry.register(registry, id, value.get());
    }
}
