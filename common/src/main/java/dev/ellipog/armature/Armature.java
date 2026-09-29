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

        // Nothing below this line, and nothing above it, names a mod that uses Armature.
        //
        // This is the one rule the project has — see rule 1 in plan.md, and
        // `.utils/check_layering.py`, which fails the build if the rule is broken. A library that
        // looks a consumer up by name depends on it, and then it can be neither compiled nor
        // released without it.
        //
        // If a boot log should list which mods are present, that belongs in the mod that cares.
    }
}
