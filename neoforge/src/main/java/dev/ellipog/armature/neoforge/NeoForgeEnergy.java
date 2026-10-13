package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.api.energy.EnergyAccess;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * NeoForge's stored energy: batteries, capacitors and charged tools through the transfer energy
 * item capability, over every slot the inventory reports — armour included, where jetpacks live.
 *
 * <p>Each item contributes no more than {@code maxPerItem} when that is positive, in both
 * directions: the measurement caps and the drain caps in the same slot order, so what one counted
 * is what the other takes. Reads are plain getters; the drain opens one transaction on first
 * successful extraction and commits it once, mirroring the Fabric twin exactly.
 *
 * <p>Fabric's twin reads the same slots through Team Reborn Energy's item lookup and caps the
 * same way; what the two promise each other is written on {@link EnergyAccess}.
 */
public final class NeoForgeEnergy implements EnergyAccess {

    @Override
    public int storedOf(ServerPlayer player, int maxPerItem) {
        long found = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                continue;
            }
            EnergyHandler handler =
                    ItemAccess.forPlayerSlot(player, slot).getCapability(Capabilities.Energy.ITEM);
            if (handler == null) {
                continue;
            }
            long stored = handler.getAmountAsLong();
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
        var inventory = player.getInventory();
        // Opened on first successful extraction rather than up front: a drain that finds nothing
        // stored pays no transaction at all, and the common case on a server without a single
        // charged item is exactly that. Committed once iff opened, so the all-or-nothing
        // semantics are unchanged -- extraction still lands or it all aborts together.
        Transaction transaction = null;
        try {
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                if (inventory.getItem(slot).isEmpty()) {
                    continue;
                }
                EnergyHandler handler =
                        ItemAccess.forPlayerSlot(player, slot).getCapability(Capabilities.Energy.ITEM);
                if (handler == null) {
                    continue;
                }
                long capped = maxPerItem > 0
                        ? Math.min(handler.getAmountAsLong(), maxPerItem)
                        : handler.getAmountAsLong();
                if (capped <= 0) {
                    continue;
                }
                if (transaction == null) {
                    transaction = Transaction.openRoot();
                }
                remaining -= handler.extract((int) Math.min(remaining, capped), transaction);
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
        if (remaining != amount) {
            inventory.setChanged();
        }
        return (int) (amount - remaining);
    }
}
