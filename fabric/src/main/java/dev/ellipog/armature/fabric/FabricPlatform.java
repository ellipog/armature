package dev.ellipog.armature.fabric;

import dev.ellipog.armature.api.platform.ArmaturePlatform;
import dev.ellipog.armature.api.platform.PlatformKind;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.Optional;

/** {@link ArmaturePlatform} on Fabric. Read-only: it asks FabricLoader questions and reports the answers. */
public final class FabricPlatform implements ArmaturePlatform {

    private final FabricLoader loader = FabricLoader.getInstance();

    @Override
    public PlatformKind kind() {
        return PlatformKind.FABRIC;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return loader.isModLoaded(modId);
    }

    @Override
    public Optional<String> modVersion(String modId) {
        return loader.getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString());
    }

    @Override
    public boolean isClient() {
        return loader.getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return loader.isDevelopmentEnvironment();
    }

    @Override
    public Path gameDir() {
        return loader.getGameDir();
    }

    @Override
    public Path configDir() {
        return loader.getConfigDir();
    }
}
