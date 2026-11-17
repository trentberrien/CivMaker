package dev.civmaker;

import dev.civmaker.BeaconEscrowLedger.Option;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** All displayed items are buttons or masks; real items move only through the escrow ledger. */
final class BeaconRaidMenu implements InventoryHolder {
    enum Page { OPTIONS, PLAYERS, LOOT }
    final Location location;
    final Village village;
    final Window window;
    final Page page;
    final Option option;
    final UUID owner;
    final int offset;
    final Map<Integer, UUID> players = new HashMap<>();
    final boolean[] taken = new boolean[54];
    int remaining = 5;
    private final Inventory inventory;

    BeaconRaidMenu(Location location, Village village, Window window, Page page, Option option, UUID owner,
                   int offset, BeaconEscrow escrow) {
        this.location = location;
        this.village = village;
        this.window = window;
        this.page = page;
        this.option = option;
        this.owner = owner;
        this.offset = offset;
        int size = page == Page.OPTIONS || (page == Page.LOOT && option == Option.ENDER_CHEST) ? 27
                : page == Page.LOOT ? 45 : 54;
        inventory = Bukkit.createInventory(this, size, Component.text(switch (page) {
            case OPTIONS -> "Capital Beacon • Raid Options";
            case PLAYERS -> "Choose an Offline Member";
            case LOOT -> "Offline " + (option == Option.ENDER_CHEST ? "Ender Chest" : "Inventory") + " • Hidden Loot";
        }));
        if (page == Page.OPTIONS) {
            button(11, Material.GOLD_INGOT, "Offline Player Money", status(escrow, Option.MONEY),
                    "Take 25% of the faction's offline funds.");
            button(13, Material.ENDER_CHEST, "Offline Player Ender Chest", status(escrow, Option.ENDER_CHEST),
                    "Choose one offline member; take up to five stacks.");
            button(15, Material.CHEST, "Offline Player Inventory", status(escrow, Option.INVENTORY),
                    "Choose one offline member; take up to five stacks.");
        } else if (page == Page.PLAYERS) {
            var members = escrow.offlinePlayers(village).entrySet().stream().toList();
            for (int index = offset; index < Math.min(offset + 45, members.size()); index++) {
                var entry = members.get(index);
                int slot = index - offset;
                players.put(slot, entry.getKey());
                ItemStack[] contents = escrow.items(entry.getKey(), village, option);
                long occupied = contents == null ? 0 : java.util.Arrays.stream(contents)
                        .filter(item -> item != null && !item.isEmpty()).count();
                button(slot, Material.PLAYER_HEAD, entry.getValue().name() == null ? entry.getKey().toString() : entry.getValue().name(),
                        "Occupied slots: " + occupied + " / " + (contents == null ? 0 : contents.length),
                        "Click to start lockpicking.", "One successful pick for this option per faction per raid.");
            }
            if (offset > 0) button(45, Material.ARROW, "Previous Page");
            button(49, Material.BARRIER, "Back to Raid Options");
            if (offset + 45 < members.size()) button(53, Material.ARROW, "Next Page");
        } else refresh(escrow);
    }

    private String status(BeaconEscrow escrow, Option option) {
        return escrow.looted(village, window, option) ? "Already picked this raid."
                : "Available • one successful pick per raid.";
    }

    void refresh(BeaconEscrow escrow) {
        ItemStack[] contents = escrow.items(owner, village, option);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (contents != null && slot < contents.length && !taken[slot] && contents[slot] != null && !contents[slot].isEmpty())
                button(slot, Material.GRAY_STAINED_GLASS_PANE, "Hidden Loot", "Click to take this stack.", remaining + " stack(s) remaining.");
            else inventory.setItem(slot, null);
        }
    }

    private void button(int slot, Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name));
            meta.lore(java.util.Arrays.stream(lore).map(Component::text).toList());
        });
        inventory.setItem(slot, item);
    }

    @Override public Inventory getInventory() { return inventory; }
}
