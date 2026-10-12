package dev.ellipog.armature.fabric;

import dev.ellipog.armature.api.energy.EnergyAccess;

import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.PlayerInventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.server.level.ServerPlayer;

import team.reborn.energy.api.EnergyStorage;

/**
 * Fabric's stored energy: batteries, capacitors and charged tools through Team Reborn Energy's
 * item lookup, over the player's inventory slots.
 *
 * <p>{@link PlayerInventoryStorage}'s slots, which wrap the whole inventory including armour,
 * where jetpacks live. Each item contributes no more than {@code maxPerItem} when that is
 * positive, in both directions: the measurement caps and the drain caps in the same slot order,
 * so what one counted is what the other takes.
 *
 * <p>Only ever loaded behind the presence check — see {@link TrEnergyProvider} for why the
 * holder exists — so a Fabric instance without the library never loads this class and the seam
 * keeps its silent zero.
 *
 * <p>NeoForge's twin reads the same slots through the energy-storage capability and caps the
 * same way; what the two promise each other is written on {@link EnergyAccess}.
 */
public final class FabricEnergy implements EnergyAccess {

    @Override
    public int storedOf(ServerPlayer player, int maxPerItem) {
        long found = 0;
        PlayerInventoryStorage inventory = PlayerInventoryStorage.of(player);
        for (SingleSlotStorage<ItemVariant> slot : inventory.getSlots()) {
            if (slot.getResource().isBlank()) {
                continue;
            }
            EnergyStorage storage =
                    ContainerItemContext.ofPlayerSlot(player, slot).find(EnergyStorage.ITEM);
            if (storage == null) {
                continue;
            }
            long stored = storage.getAmount();
            found += maxPerItem > 0 ? Math.min(stored, maxPerItem) : stored;
        }
        return (int) Math.min(found, Integer.MAX_VALUE);
    }

    @Override
    public int drainFrom(ServerPlayer player, int amount, int maxPerItem) {
        if (amount <= 0) {
            return 0;
        }
        long remaining = amount;
        PlayerInventoryStorage inventory = PlayerInventoryStorage.of(player);
        // Opened on first successful extraction rather than up front: a drain that finds nothing
        // stored pays no transaction at all, and the common case on a server without a single
        // charged item is exactly that. Committed once iff opened, so the all-or-nothing
        // semantics are unchanged -- extraction still lands or it all aborts together.
        Transaction transaction = null;
        try {
            for (SingleSlotStorage<ItemVariant> slot : inventory.getSlots()) {
                if (remaining <= 0) {
                    break;
                }
                if (slot.getResource().isBlank()) {
                    continue;
                }
                EnergyStorage storage =
                        ContainerItemContext.ofPlayerSlot(player, slot).find(EnergyStorage.ITEM);
                if (storage == null) {
                    continue;
                }
                long capped = maxPerItem > 0
                        ? Math.min(storage.getAmount(), maxPerItem)
                        : storage.getAmount();
                if (capped <= 0) {
                    continue;
                }
                if (transaction == null) {
                    transaction = Transaction.openOuter();
                }
                remaining -= storage.extract(Math.min(remaining, capped), transaction);
            }
            if (transaction != null) {
                transaction.commit();
            }
        }
        finally {
            if (transaction != null) {
                transaction.close();
            }
        }
        return (int) (amount - remaining);
    }
}
