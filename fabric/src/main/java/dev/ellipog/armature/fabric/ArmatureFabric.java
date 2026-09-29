package dev.ellipog.armature.fabric;

import dev.ellipog.armature.Armature;
import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.ArmatureApi;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry point: install the platform layer, hook the events, then start Armature.
 *
 * <p>Runs on both the client and a dedicated server. Nothing in here may touch a client
 * class — that is {@link FabricClient}'s job and it is a separate entry point precisely so
 * the server never loads it.
 */
public final class ArmatureFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        ArmatureApi.install(new FabricPlatform(), new FabricRegistrar(Constants.MOD_ID));
        FabricEvents.attach();
        Armature.init();
    }
}
