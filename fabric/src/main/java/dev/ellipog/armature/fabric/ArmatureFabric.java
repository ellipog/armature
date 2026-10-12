package dev.ellipog.armature.fabric;

import dev.ellipog.armature.Armature;
import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.energy.EnergyAccesses;

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

        // Stored energy through Team Reborn Energy's item lookup -- and only when it is there.
        // TR Energy is a soft dependency, so the adapter is read through its holder inside the
        // guard: the holder names no TR type, and a Fabric instance without the library never
        // loads the adapter and keeps the seam's silent zero. See TrEnergyProvider for why the
        // holder exists rather than a direct construction.
        if (energyPresent()) {
            try {
                EnergyAccesses.install(TrEnergyProvider.INSTANCE);
            }
            catch (RuntimeException | LinkageError e) {
                // A library we cannot link against is a library we do not have. Logged rather
                // than thrown, because the fallback -- the seam reading zero -- is the
                // documented behaviour for an instance without it.
                Constants.LOG.warn("TR Energy is present but its adapter could not be "
                        + "installed ({}); carried energy reads zero on this instance", e.toString());
            }
        }
    }

    /**
     * Whether TR Energy is loaded. The platform was installed above, so the ask cannot fail
     * for lack of one -- but a guard that throws is no guard, and the id comes from the holder
     * so there is one spelling of it either way.
     */
    private static boolean energyPresent() {
        try {
            return ArmatureApi.platform().isModLoaded(TrEnergyProvider.MOD_ID);
        }
        catch (RuntimeException e) {
            Constants.LOG.debug("cannot ask whether '{}' is loaded ({}); assuming not",
                    TrEnergyProvider.MOD_ID, e.toString());
            return false;
        }
    }
}
