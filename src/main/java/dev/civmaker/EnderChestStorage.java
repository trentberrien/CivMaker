package dev.civmaker;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

final class EnderChestStorage implements Listener {
    static final NamespacedKey PURCHASED = new NamespacedKey("civmaker", "shop_purchase");
    private static final String MESSAGE = "Settlement currency, banknotes, and shop items cannot be stored in ender chests, including inside containers.";
    private final Notes notes;
    private final RaidEquipment equipment;
    private final SettlementResources resources;

    EnderChestStorage(Notes notes, RaidEquipment equipment, SettlementResources resources) {
        this.notes = notes;
        this.equipment = equipment;
        this.resources = resources;
    }

    boolean restricted(ItemStack item) { return restricted(item, 0); }

    private boolean restricted(ItemStack item, int depth) {
        if (item == null || item.isEmpty()) return false;
        if (depth > 64) return true;
        if (notes.isNote(item) || resources.isCurrency(item) || equipment.identify(item) != null
                || item.getPersistentDataContainer().has(FoundingBeacons.BEACON_KEY, PersistentDataType.BYTE)
                || item.getPersistentDataContainer().has(PURCHASED, PersistentDataType.BYTE)) return true;
        var meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundle)
            return bundle.getItems().stream().anyMatch(nested -> restricted(nested, depth + 1));
        if (meta instanceof BlockStateMeta block && block.getBlockState() instanceof InventoryHolder holder)
            return Arrays.stream(holder.getInventory().getContents()).anyMatch(nested -> restricted(nested, depth + 1));
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void click(InventoryClickEvent event) {
        var top = event.getView().getTopInventory();
        var clicked = event.getClickedInventory();
        if (!top.equals(event.getWhoClicked().getEnderChest()) || clicked == null) return;
        boolean blocked = switch (event.getAction()) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR, PLACE_FROM_BUNDLE,
                    PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE -> clicked == top && restricted(event.getCursor());
            case MOVE_TO_OTHER_INVENTORY -> clicked != top && restricted(event.getCurrentItem());
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                ItemStack incoming = event.getClick() == ClickType.SWAP_OFFHAND
                        ? event.getWhoClicked().getInventory().getItemInOffHand()
                        : event.getHotbarButton() < 0 ? null : event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
                yield clicked == top && restricted(incoming);
            }
            default -> false;
        };
        if (blocked) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(MESSAGE);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void drag(InventoryDragEvent event) {
        var top = event.getView().getTopInventory();
        if (top.equals(event.getWhoClicked().getEnderChest()) && restricted(event.getOldCursor())
                && event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < top.getSize())) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(MESSAGE);
        }
    }
}
