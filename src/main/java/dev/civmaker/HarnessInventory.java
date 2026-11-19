package dev.civmaker;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The menu mirrors equipment; only the ghast owns the actual harness. */
final class HarnessInventory implements Listener, CommandExecutor {
    static final int HARNESS_SLOT = 4;
    private final RaidEquipment equipment;
    private final Map<UUID, Menu> menus = new HashMap<>();

    static final class Menu implements InventoryHolder {
        final HappyGhast ghast;
        private final Inventory inventory;

        Menu(HappyGhast ghast) {
            this.ghast = ghast;
            inventory = Bukkit.createInventory(this, 9, Component.text("Happy Ghast Harness"));
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            filler.editMeta(meta -> meta.displayName(Component.empty()));
            for (int slot = 0; slot < 9; slot++) {
                if (slot != HARNESS_SLOT) inventory.setItem(slot, filler);
            }
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    HarnessInventory(RaidEquipment equipment) {
        this.equipment = equipment;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) open(player);
        else sender.sendMessage("Ride a Happy Ghast and use /harness.");
        return true;
    }

    void open(Player player) {
        if (!(player.getVehicle() instanceof HappyGhast ghast) || player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage("Ride a Happy Ghast to open its harness inventory.");
            return;
        }
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu current
                && current.ghast.equals(ghast)) return;
        equipment.syncHarness(ghast);
        Menu menu = new Menu(ghast);
        refresh(menu);
        if (player.openInventory(menu.inventory) != null) menus.put(player.getUniqueId(), menu);
    }

    private boolean usable(Player player, Menu menu) {
        return menu.ghast.isValid() && !player.isDead() && player.getVehicle() == menu.ghast
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    static boolean accepts(ItemStack item) {
        return empty(item) || item.getAmount() == 1 && item.getType().name().endsWith("_HARNESS");
    }

    private static boolean empty(ItemStack item) { return item == null || item.isEmpty(); }

    private ItemStack equipped(Menu menu) { return menu.ghast.getEquipment().getItem(EquipmentSlot.BODY); }

    private void equip(Menu menu, ItemStack item) {
        menu.ghast.getEquipment().setItem(EquipmentSlot.BODY, empty(item) ? null : item.clone());
        equipment.syncHarness(menu.ghast);
        refresh(menu);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || !(event.getWhoClicked() instanceof Player player)) return;
        if (!usable(player, menu)) { event.setCancelled(true); return; }
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true); // Never collect the mirrored equipment into the cursor.
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0) return;
        if (raw >= 9) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                ItemStack incoming = event.getCurrentItem();
                if (!empty(incoming) && accepts(incoming) && empty(equipped(menu))) {
                    equip(menu, incoming);
                    event.setCurrentItem(null);
                }
            }
            return; // Ordinary rearrangement in the player's inventory remains vanilla.
        }
        event.setCancelled(true);
        if (raw != HARNESS_SLOT) return;
        ItemStack current = equipped(menu);
        if (event.isShiftClick()) {
            if (!empty(current) && player.getInventory().addItem(current.clone()).isEmpty()) equip(menu, null);
        } else if (event.getClick() == ClickType.NUMBER_KEY || event.getClick() == ClickType.SWAP_OFFHAND) {
            int slot = event.getClick() == ClickType.SWAP_OFFHAND ? 40 : event.getHotbarButton();
            if (slot < 0) return;
            ItemStack incoming = player.getInventory().getItem(slot);
            if (accepts(incoming)) {
                equip(menu, incoming);
                player.getInventory().setItem(slot, current);
            }
        } else if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT) {
            ItemStack incoming = player.getItemOnCursor();
            if (accepts(incoming)) {
                equip(menu, incoming);
                player.setItemOnCursor(current);
            }
        }
        refresh(menu);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void drag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu)
                || event.getRawSlots().stream().noneMatch(slot -> slot < 9)) return;
        // Use click/shift-click/number keys for equipment; dragging in the player inventory is unaffected.
        event.setCancelled(true);
    }

    @EventHandler
    void closed(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menus.remove(event.getPlayer().getUniqueId(), menu);
            menu.inventory.clear(); // Discard the display copy, never return it as another item.
        }
    }

    void refresh() {
        for (var entry : List.copyOf(menus.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || player.getOpenInventory().getTopInventory().getHolder() != entry.getValue()) {
                menus.remove(entry.getKey());
            } else if (!usable(player, entry.getValue())) {
                player.closeInventory();
            } else refresh(entry.getValue());
        }
    }

    private void refresh(Menu menu) {
        ItemStack item = equipped(menu);
        if (!java.util.Objects.equals(item, menu.inventory.getItem(HARNESS_SLOT))) {
            menu.inventory.setItem(HARNESS_SLOT, empty(item) ? null : item.clone());
        }
        String status = "No harness equipped";
        if (equipment.identify(item) == RaidEquipment.Tool.HAPPY_HARNESS) {
            int maximum = RaidEquipment.HARNESS_DURABILITY - 1;
            int remaining = Math.max(0, maximum - item.getDataOrDefault(DataComponentTypes.DAMAGE, 0));
            status = "Durability: " + remaining + " / " + maximum + (remaining == 0 ? " (worn out)" : "");
        } else if (!empty(item)) status = "Ordinary harness (no durability)";
        ItemStack info = new ItemStack(Material.GHAST_TEAR);
        String name = status;
        info.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.GOLD));
            meta.lore(List.of(Component.text("Swap harnesses in the middle slot.", NamedTextColor.GRAY),
                    Component.text("Rearrange your inventory below.", NamedTextColor.GRAY),
                    Component.text("One ghast tear fully repairs it at an anvil.", NamedTextColor.GRAY)));
        });
        if (!info.equals(menu.inventory.getItem(0))) menu.inventory.setItem(0, info);
    }

    void closeAll() {
        for (UUID id : List.copyOf(menus.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.closeInventory();
        }
        menus.clear();
    }
}
