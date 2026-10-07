package dev.ellipog.armature;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

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
}
