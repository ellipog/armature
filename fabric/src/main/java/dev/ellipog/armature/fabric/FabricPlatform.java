package dev.ellipog.armature.fabric;

import dev.ellipog.armature.api.platform.ArmaturePlatform;
import dev.ellipog.armature.api.platform.PlatformKind;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;

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
    public boolean isFakePlayer(net.minecraft.server.level.ServerPlayer player) {
        // No reliable marker: vanilla marks nothing, and a name-pattern guess would misfire on a
        // player wearing the wrong name. False and said out loud, rather than a guess that can
        // only be wrong in one direction — see the interface note.
        return false;
    }

    @Override
    public Path gameDir() {
        return loader.getGameDir();
    }

    @Override
    public Path configDir() {
        return loader.getConfigDir();
    }

    @Override
    public boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, LivingEntity breaker) {
        if (breaker instanceof Player player) {
            // The same three questions a hand break answers: adventure and spectator rules,
            // spawn protection, then the event claim mods already hook.
            return player.mayBuild()
                    && level.mayInteract(player, pos)
                    && PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(
                            level, player, pos, state, level.getBlockEntity(pos));
        }
        return level.getGameRules().get(GameRules.MOB_GRIEFING);
    }
}
