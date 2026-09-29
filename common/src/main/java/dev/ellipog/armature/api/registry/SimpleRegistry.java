package dev.ellipog.armature.api.registry;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A registry of ordinary Java objects, keyed by {@link ResourceLocation}.
 *
 * <h2>What this is not</h2>
 *
 * <p>Not a game registry. Minecraft's {@code Registry} holds things the game knows about —
 * items, blocks, biomes — and the platform layer's {@link Registrar} is how you write to it. This
 * holds things <i>your</i> API knows about: the set of quest task types, or reward types, where
 * the point is that another mod can add one.
 *
 * <h2>Why a registry and not an enum</h2>
 *
 * <p>Because this is the addon API. A sealed set of task types can never be extended by anyone
 * else, and every task, every saved progress file and every network message is written in terms of
 * the type's identity — so the shape of that identity is not something that can be changed later
 * without migrating all of it. It costs nothing to be a registry now, and it cannot be retrofitted
 * cheaply.
 *
 * <p>Duplicates are rejected rather than silently replacing. Two mods claiming the same id is a
 * bug worth a loud failure, since the alternative is whichever one registered last winning
 * arbitrarily, at runtime, depending on mod load order.
 */
public final class SimpleRegistry<T> {

    private final String what;
    private final Map<ResourceLocation, T> byId = new LinkedHashMap<>();

    private SimpleRegistry(String what) {
        this.what = what;
    }

    /** @param what what this registry holds, for error messages — "quest task types", say */
    public static <T> SimpleRegistry<T> create(String what) {
        return new SimpleRegistry<>(what);
    }

    /**
     * Adds an entry.
     *
     * @throws IllegalStateException if the id is already taken
     */
    public void register(ResourceLocation id, T value) {
        T existing = byId.putIfAbsent(id, value);
        if (existing != null) {
            throw new IllegalStateException("Two " + what + " are registered under " + id
                    + ": " + existing.getClass().getName() + " and " + value.getClass().getName()
                    + ". Ids are namespaced by mod, so this means the same mod registered twice, "
                    + "or shadowed an id it does not own.");
        }
    }

    public Optional<T> get(ResourceLocation id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean contains(ResourceLocation id) {
        return byId.containsKey(id);
    }

    /** Sorted, so error messages listing the options read the same way every time. */
    public Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(new TreeSet<>(byId.keySet()));
    }

    /** Everything registered, in registration order. Used by dispatch that must see the whole set. */
    public Collection<T> values() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }
}
