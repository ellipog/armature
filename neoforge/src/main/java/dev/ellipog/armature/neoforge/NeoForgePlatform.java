package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.api.platform.ArmaturePlatform;
import dev.ellipog.armature.api.platform.PlatformKind;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link ArmaturePlatform} on NeoForge. Read-only: it asks NeoForge's loader questions and
 * reports the answers.
 *
 * <p>Note there is no {@code Loader} class in NeoForge 1.21.1 — its replacement arrived
 * later — so each question goes to the specific holder that answers it.
 */
public final class NeoForgePlatform implements ArmaturePlatform {

    @Override
    public PlatformKind kind() {
        return PlatformKind.NEOFORGE;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Optional<String> modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString());
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
