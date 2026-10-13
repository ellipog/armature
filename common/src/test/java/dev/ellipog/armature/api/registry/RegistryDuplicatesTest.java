package dev.ellipog.armature.api.registry;

import com.mojang.serialization.Lifecycle;

import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The facts about vanilla that {@code FabricRegistrar}'s duplicate check rests on.
 *
 * <h2>Why this tests Minecraft rather than Armature</h2>
 *
 * <p>Because the guard exists to compensate for a platform behaviour, and a claim about the platform
 * is exactly the kind of thing this project has repeatedly found to be wrong. Same argument as
 * {@code TeamStoreTest} asserting that {@code SavedData.setDirty()} only assigns a boolean: if that
 * changes, the thing built on top of it is wrong, and nothing else would say so.
 *
 * <p>The assertions here are drawn from the decompiled 1.21.1 source, not from a guess about how a
 * registry ought to behave. That distinction is not pedantry: the first version of this file asserted
 * that a duplicate leaves {@code size() == 2}, on the reasonable assumption that a count of entries
 * counts entries. It does not. {@code MappedRegistry.size()} is {@code byKey.size()} — keyed by
 * {@code ResourceKey} — so the count stays at <b>one</b> while the registry quietly holds two raw ids
 * for the same name. See {@link #aDuplicateIsSilentAndTheCountLies()}.
 *
 * <h2>What is deliberately not asserted</h2>
 *
 * <p>Which value the name resolves to afterwards, or what {@code byId} returns for the two slots. Both
 * are consequences of {@code byKey.computeIfAbsent} handing back the <i>existing</i> reference on the
 * second call, so the new value is bound but unreachable. That is interesting for a bug report and
 * irrelevant to the guard, and asserting it would pin behaviour nobody promised — which is how a test
 * becomes an obstacle to a platform upgrade.
 *
 * <h2>What this does not test, which is an honest gap</h2>
 *
 * <p>{@code FabricRegistrar} itself: it lives in the {@code fabric} subproject, which needs Loom's
 * remapped Minecraft to compile and has no test source set. Adding one for three assertions would be a
 * build change rather than a test. The guard is covered indirectly instead — every mod in both repos
 * registers during startup, so a guard that misfired and threw when it should not would take out both
 * entire suites, at 388 and 349 tests. That is how this file's own build proves it does not.
 */
class RegistryDuplicatesTest {

    /**
     * A small registry of strings.
     *
     * <p>Built directly rather than taken from {@code BuiltInRegistries}, which needs
     * {@code Bootstrap.bootStrap()} and about a second of setup. Neither question here is about
     * vanilla's contents; both need a registry that behaves like one.
     */
    private static MappedRegistry<String> freshRegistry() {
        ResourceKey<Registry<String>> key = ResourceKey.createRegistryKey(
                Identifier.fromNamespaceAndPath("armature", "duplicates_test"));
        return new MappedRegistry<>(key, Lifecycle.stable(), false);
    }

    @Test
    @DisplayName("a duplicate id is refused loudly, and the entry count never gets to lie")
    void aDuplicateIsRefusedLoudly() {
        MappedRegistry<String> registry = freshRegistry();
        Identifier id = Identifier.fromNamespaceAndPath("armature", "twice");

        Registry.register(registry, id, "first");
        assertEquals(1, registry.size(), "one entry after one registration");
        assertTrue(registry.get(0).isPresent(), "occupying raw id 0");
        assertFalse(registry.get(1).isPresent(), "and nothing sits at raw id 1 yet");

        // The assertion that matters. On 1.21.1 registering the same id a second time did not
        // throw: `size()` is byKey.size(), keyed by ResourceKey, so the registry reported exactly
        // as many entries as a healthy one while a second raw slot existed -- one name, two ids,
        // and every other side disagreeing about what id a name has (the shape fabric-registry-sync
        // refuses when a client joins a server). That silence is what this project's own guard
        // exists to pre-empt: it refuses the duplicate with a file and a line before vanilla ever
        // sees it.
        //
        // On 26.3 the platform throws itself -- `IllegalStateException: Adding duplicate key ...`.
        // Either way the count never gets to lie, and either way the guard fires first. Written as
        // an assertion on the throw (rather than only on the guard) so that a future Minecraft
        // which goes silent again fails here with a missing throw rather than with a count nobody
        // trusts -- which would read as a bug in this test rather than a change in the platform.
        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> Registry.register(registry, id, "second"));
        assertTrue(thrown.getMessage().contains(id.toString()),
                "and the throw names the id, so the log says which registration collided: "
                        + thrown.getMessage());

        assertEquals(1, registry.size(),
                "the count still says ONE -- and now it is true, because the second registration "
                        + "never landed");
        assertTrue(registry.containsKey(id), "and the id is still reported as held, correctly");
    }

    @Test
    @DisplayName("a Registry<T> can be asked whether it holds an id, which is what the guard needs")
    void aRegistryCanBeAskedWhetherItHoldsAnId() {
        MappedRegistry<String> registry = freshRegistry();
        Identifier id = Identifier.fromNamespaceAndPath("armature", "askable");

        // Held as the interface, not the implementation -- because that is what `Registrar.register`
        // receives. A method that existed only on MappedRegistry would not be callable from the guard
        // and the guard would not compile, which is why this is checked against the interface type
        // rather than against the concrete class it was constructed as.
        Registry<String> asInterface = registry;

        assertFalse(asInterface.containsKey(id), "nothing registered yet");

        Registry.register(registry, id, "value");

        assertTrue(asInterface.containsKey(id),
                "after registering, the interface reports it -- this is the single question the guard "
                        + "asks, and it is what lets a second registration be refused before it leaves "
                        + "a registry that disagrees with every other side about what id a name has");
    }
}
