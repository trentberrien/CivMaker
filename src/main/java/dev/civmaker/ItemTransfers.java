package dev.civmaker;

import org.bukkit.inventory.ItemStack;

final class ItemTransfers {
    private ItemTransfers() {}

    // Replace stacks in the destination array so callers can plan a transfer on a shallow copy.
    static int insert(ItemStack[] destination, ItemStack item, int inventoryLimit) {
        int remaining = item.getAmount();
        int limit = Math.min(item.getMaxStackSize(), inventoryLimit);
        for (int slot = 0; slot < destination.length && remaining > 0; slot++) {
            ItemStack existing = destination[slot];
            if (existing != null && existing.isSimilar(item) && existing.getAmount() < limit) {
                int moved = Math.min(remaining, limit - existing.getAmount());
                destination[slot] = existing.asQuantity(existing.getAmount() + moved);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < destination.length && remaining > 0; slot++) {
            if (destination[slot] == null || destination[slot].isEmpty()) {
                int moved = Math.min(remaining, limit);
                destination[slot] = item.asQuantity(moved);
                remaining -= moved;
            }
        }
        return remaining;
    }
}
