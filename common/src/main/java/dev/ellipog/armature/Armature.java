package dev.ellipog.armature;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.platform.ArmaturePlatform;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

/**
 * Common entry point, shared by the Fabric and NeoForge builds.
 *
 * <p>Compiled against vanilla only — nothing under this package may name a loader.
 *
 * <p>By the time this runs, the loader's entry point has already installed the platform
 * layer and attached its events, so everything in {@link ArmatureApi} is ready.
 */
public final class Armature {

    private Armature() {
    }

    public static void init() {
        ArmaturePlatform platform = ArmatureApi.platform();

        Constants.LOG.info("Armature loaded on {} ({})", platform.name(), platform.environmentName());

        // Reaching into a vanilla registry here is the cheapest possible proof that common
        // code is running for real and not merely being class-loaded. It is also the first
        // thing that breaks if registration is wired up in the wrong order on one loader,
        // which is the classic NeoForge trap — so it is checked at startup rather than
        // assumed. See plan.md, Stage 1.
        Constants.LOG.info("registry check: {}", BuiltInRegistries.ITEM.getKey(Items.DIAMOND));

        Constants.LOG.info("config dir: {}", platform.configDir());

        if (platform.isModLoaded("tasked")) {
            Constants.LOG.info("Tasked is present ({})", platform.modVersion("tasked").orElse("unknown"));
        }
    }
}
