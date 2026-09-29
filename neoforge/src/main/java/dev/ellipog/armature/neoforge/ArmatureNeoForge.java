package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.Armature;
import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.ArmatureApi;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point: install the platform layer, hook the events, then start Armature.
 *
 * <p>The constructor receives the mod event bus, which the registrar needs — NeoForge keeps
 * one registration queue per mod, on that mod's own bus, so registration cannot be done
 * without it.
 *
 * <p>Runs on both the client and a dedicated server. Nothing here may touch a client class;
 * that is {@link ArmatureNeoForgeClient}'s job, and it is a separate {@code @Mod} with
 * {@code dist = Dist.CLIENT} precisely so the server never loads it.
 */
@Mod(Constants.MOD_ID)
public final class ArmatureNeoForge {

    public ArmatureNeoForge(IEventBus modEventBus) {
        ArmatureApi.install(new NeoForgePlatform(), new NeoForgeRegistrar(modEventBus, Constants.MOD_ID));
        NeoForgeEvents.attach();
        Armature.init();
    }
}
