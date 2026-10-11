package dev.ellipog.armature.api.energy;

import net.minecraft.server.level.ServerPlayer;

/**
 * The energy stored in carried items — batteries, capacitors, charged tools — in Forge Energy
 * units, whatever the loader calls them.
 *
 * <p>Read through the loader's own machinery — NeoForge's energy-storage capability, Team Reborn
 * Energy's item lookup on Fabric — because vanilla has no energy API. TR Energy is optional, so
 * a Fabric instance without it reads zero.
 *
 * <p>Installed once per loader, by that loader's entry point; everything else asks
 * {@link EnergyAccesses}. The first consumer measured carried energy for its own mechanics, and
 * the seam lives here rather than there so any mod on the suite can measure it without depending
 * on a consumer.
 *
 * @see EnergyAccesses
 */
public interface EnergyAccess {

    /**
     * Forge Energy units stored across carried items, each item contributing no more than
     * {@code maxPerItem} when that is positive.
     *
     * <p>The per-item cap is the caller's to define: zero or negative means unlimited. Pure read:
     * measures, changes nothing.
     */
    int storedOf(ServerPlayer player, int maxPerItem);

    /**
     * Drains up to {@code amount} units from carried items, no item giving more than
     * {@code maxPerItem} when that is positive, answering how much was actually drained.
     */
    int drainFrom(ServerPlayer player, int amount, int maxPerItem);
}
