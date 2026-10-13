package dev.ellipog.armature;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Starts enough of vanilla for a test that has to build a real item.
 *
 * <h2>Why a library's own suite needs this</h2>
 *
 * <p>Because {@code new ItemStack(Items.STONE)} builds a {@code ResourceKey} inside a class initialiser
 * vanilla owns, and without the bootstrap flag that throws {@code Not bootstrapped (called from registry
 * ... minecraft:game_event)} — a message naming a registry that has nothing to do with the test, out of a
 * class nobody wrote. The recorder tests that drive a control's drawing need a real item, so they need this.
 *
 * <p>Costs about a second, once per JVM, and only the classes that build an item pay it.
 */
public final class MinecraftTestBootstrap {

    private static boolean done;

    private MinecraftTestBootstrap() {
    }

    /**
     * Idempotent, so every test class can call it without coordination.
     *
     * <p>Both calls, in this order: {@code tryDetectVersion()} first because {@code bootStrap()} reads the
     * game version and throws {@code Game version not set} without it, and {@code bootStrap()} second
     * because it is what sets the flag {@code Registry} checks — {@code BuiltInRegistries.bootStrap()}
     * alone looks equivalent and sets nothing.
     */
    public static synchronized void boot() {
        if (done) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        done = true;
    }

    /**
     * A real item stack without a world.
     *
     * <p>On 26.x a registry holder's components are not bound until datapacks load, so
     * {@code new ItemStack(Items.STONE)} throws "Components not bound yet" even bootstrapped --
     * and so does {@code Items.STONE.components()}, which resolves through the same holder. A
     * direct holder carries the components explicitly instead, so the stack is built with an
     * empty map: the tests that take one never read components (the recorder only records the
     * call), and emptiness is item-and-count, not components. If a test ever needs real
     * components, it needs a world, and this helper must not pretend otherwise.
     */
    public static ItemStack stackOf(Item item) {
        return new ItemStack(Holder.direct(item,
                net.minecraft.core.component.DataComponentMap.EMPTY));
    }
}
