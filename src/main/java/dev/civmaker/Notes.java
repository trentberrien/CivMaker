package dev.civmaker;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

final class Notes implements Listener {
    static final long VALUE = 1000;
    private static final int MAX_DEATH_DROP_STACKS = 64;
    private final NamespacedKey key;
    private final NamespacedKey faceValueKey;

    Notes(CivMaker plugin) {
        key = new NamespacedKey(plugin, "banknote_v1");
        faceValueKey = new NamespacedKey(plugin, "banknote_value");
    }

    ItemStack stack(int amount) {
        return stack(amount, VALUE);
    }

    ItemStack stack(int amount, long faceValue) {
        ItemStack note = new ItemStack(Material.PAPER, amount);
        note.editMeta(meta -> {
            meta.displayName(Component.text(String.format(Locale.US, "CivMaker Banknote ($%,d)", faceValue)));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            if (faceValue != VALUE) {
                meta.getPersistentDataContainer().set(faceValueKey, PersistentDataType.LONG, faceValue);
                meta.setMaxStackSize(1);
            }
        });
        return note;
    }

    void addDeathDrops(List<ItemStack> drops, long noteCount) {
        int maxStackSize = Material.PAPER.getMaxStackSize();
        while (noteCount > MAX_DEATH_DROP_STACKS * maxStackSize) {
            long batch = Math.min(noteCount, Long.MAX_VALUE / VALUE);
            drops.add(stack(1, batch * VALUE));
            noteCount -= batch;
        }
        while (noteCount > 0) {
            int batch = (int) Math.min(noteCount, maxStackSize);
            drops.add(stack(batch));
            noteCount -= batch;
        }
    }

    boolean isNote(ItemStack item) {
        return item != null && item.getType() == Material.PAPER
                && item.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BYTE, (byte) 0) == 1;
    }

    long largeNoteValue(ItemStack item) {
        if (!isNote(item) || item.getAmount() != 1) {
            return 0;
        }
        return item.getPersistentDataContainer().getOrDefault(faceValueKey, PersistentDataType.LONG, 0L);
    }

    long faceValue(ItemStack note) {
        return note.getPersistentDataContainer().getOrDefault(faceValueKey, PersistentDataType.LONG, VALUE);
    }

    int count(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (isNote(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    long value(Inventory inventory, int amount) {
        long total = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (!isNote(item)) {
                continue;
            }
            int take = Math.min(amount, item.getAmount());
            long faceValue = faceValue(item);
            if (take > (Long.MAX_VALUE - total) / faceValue) {
                return Long.MAX_VALUE;
            }
            total += faceValue * take;
            amount -= take;
            if (amount == 0) {
                break;
            }
        }
        return total;
    }

    void remove(Inventory inventory, int amount) {
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && amount > 0; slot++) {
            ItemStack item = contents[slot];
            if (!isNote(item)) {
                continue;
            }
            int take = Math.min(amount, item.getAmount());
            item.setAmount(item.getAmount() - take);
            inventory.setItem(slot, item.getAmount() == 0 ? null : item);
            amount -= take;
        }
    }

    int capacity(Inventory inventory) {
        int space = 0;
        ItemStack note = stack(1);
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                space += note.getMaxStackSize();
            } else if (note.isSimilar(item)) {
                space += item.getMaxStackSize() - item.getAmount();
            }
        }
        return space;
    }

    void give(Inventory inventory, int amount) {
        int maxStackSize = stack(1).getMaxStackSize();
        while (amount > 0) {
            int batch = Math.min(amount, maxStackSize);
            inventory.addItem(stack(batch));
            amount -= batch;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void preventCraftingWithMoney(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (isNote(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}
