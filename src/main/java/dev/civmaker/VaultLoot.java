package dev.civmaker;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class VaultLoot implements InventoryHolder {
    private static final int LIMIT = 5;
    private final Inventory mask;
    private final ItemStack hidden = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    private final boolean[] taken;
    private int remaining = LIMIT;

    VaultLoot(Inventory source) {
        this(source, false);
    }

    VaultLoot(Inventory source, boolean settlement) {
        mask = settlement
                ? Bukkit.createInventory(this, InventoryType.HOPPER, Component.text("Settlement Vault • Hidden Loot"))
                : Bukkit.createInventory(this, source.getSize(), Component.text("Secure Vault • Hidden Loot"));
        taken = new boolean[mask.getSize()];
        hidden.editMeta(meta -> meta.displayName(Component.text("Hidden loot")));
        refresh(source);
    }

    @Override
    public Inventory getInventory() {
        return mask;
    }

    boolean exhausted() {
        return remaining == 0;
    }

    boolean hasTakenItems() {
        return remaining < LIMIT;
    }

    boolean take(Player player, Inventory source, int slot) {
        if (exhausted() || taken[slot]) {
            return false;
        }
        ItemStack item = source.getItem(slot);
        if (item == null || item.isEmpty()) {
            mask.setItem(slot, null);
            return false;
        }

        if (!receiveStack(player.getInventory(), item)) {
            player.sendMessage("Free up enough inventory space for the whole stack.");
            return false;
        }
        source.setItem(slot, null);
        mask.setItem(slot, null);
        taken[slot] = true;
        remaining--;
        player.sendActionBar(Component.text(remaining + " slot(s) left for this lockpick."));
        return true;
    }

    private static boolean receiveStack(PlayerInventory inventory, ItemStack item) {
        ItemStack[] contents = inventory.getStorageContents().clone();
        // Plan the transfer on copies so a partial fit leaves both inventories untouched.
        if (ItemTransfers.insert(contents, item, inventory.getMaxStackSize()) != 0) {
            return false;
        }
        inventory.setStorageContents(contents);
        return true;
    }

    void refresh(Inventory source) {
        for (int slot = 0; slot < mask.getSize(); slot++) {
            ItemStack item = source.getItem(slot);
            ItemStack displayed = mask.getItem(slot);
            boolean occupied = !taken[slot] && item != null && !item.isEmpty();
            boolean marked = displayed != null && !displayed.isEmpty();
            if (occupied != marked) {
                mask.setItem(slot, occupied ? hidden.clone() : null);
            }
        }
    }
}
