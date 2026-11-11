package dev.civmaker;

import dev.civmaker.RaidEquipment.Tool;
import dev.civmaker.SettlementResources.Reward;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class RaidShop implements Listener {
    private static final long PRICE = 10000;
    private static final long BANNER_PRICE = 100;
    private static final int RESOURCE_PRICE = Math.toIntExact(PRICE / SettlementResources.ITEM_VALUE);
    private static final int ROW_WIDTH = 9;
    private static final int HEADER_COLUMN = 1;
    private static final int FIRST_PRODUCT_COLUMN = 3;
    private static final int FACTION_SLOT = ROW_WIDTH / 2;

    private record Section(String label, Material icon, NamedTextColor color, List<Tool> tools) {}

    private static final List<Section> SECTIONS = List.of(
            new Section("Raid Gear", Material.IRON_SWORD, NamedTextColor.RED,
                    List.of(Tool.LOCKPICK, Tool.RAM, Tool.C4, Tool.GRENADE, Tool.BLAST_ROCKET)),
            new Section("Village Defense", Material.SHIELD, NamedTextColor.GREEN,
                    List.of(Tool.PILLAGER_GUARD, Tool.VAULT, Tool.VAULT_DOOR, Tool.RAID_SHIELD, Tool.DEFENDER_GOLEM)),
            new Section("Mount Upgrades", Material.LEAD, NamedTextColor.GOLD,
                    List.of(Tool.HAPPY_HARNESS, Tool.SUPER_SADDLE)),
            new Section("Utility", Material.BOOK, NamedTextColor.AQUA,
                    List.of(Tool.RADAR, Tool.SUPER_TOTEM, Tool.WARP_PORTAL)));

    private static final class Menu implements InventoryHolder {
        private final Inventory inventory = Bukkit.createInventory(this, (SECTIONS.size() + 1) * ROW_WIDTH,
                Component.text("CivMaker Shop"));
        private final Tool[] products = new Tool[inventory.getSize()];
        private final boolean factionBanner;

        Menu(RaidEquipment equipment, RaidShields shields, FoundingBeacons foundingBeacons, CivStore store, Player player) {
            factionBanner = store.registeredVillage(player.getUniqueId()) != null;
            for (int row = 0; row < SECTIONS.size(); row++) {
                Section section = SECTIONS.get(row);
                int rowStart = (row + 1) * ROW_WIDTH;
                var header = new ItemStack(section.icon());
                header.editMeta(meta -> meta.displayName(Component.text(section.label(), section.color())
                        .decoration(TextDecoration.ITALIC, false)));
                inventory.setItem(rowStart + HEADER_COLUMN, header);

                int slot = rowStart + FIRST_PRODUCT_COLUMN;
                for (Tool tool : section.tools()) {
                    products[slot] = tool;
                    inventory.setItem(slot, product(equipment, shields, tool));
                    slot++;
                }
            }
            if (factionBanner) {
                inventory.setItem(FACTION_SLOT, button(new ItemStack(Material.WHITE_BANNER), "Faction Banner",
                        "Apply your faction design to a banner or shield for " + cashLabel(BANNER_PRICE) + "."));
            } else if (foundingBeacons.canBuy(player)) {
                inventory.setItem(FACTION_SLOT, button(new ItemStack(Material.BEACON), "Founding Beacon",
                        "Left-click to buy for " + FoundingBeacons.PRICE_LABEL + ".",
                        foundingBeacons.placementHint(), "Give it clear sky above and follow the setup menu."));
            }
        }

        private static ItemStack product(RaidEquipment equipment, RaidShields shields, Tool tool) {
            long price = tool == Tool.RAID_SHIELD ? shields.price() : PRICE;
            var item = equipment.item(tool, "Left-click to pay " + cashLabel(price));
            Reward payment = Reward.paymentFor(tool);
            if (payment != null) {
                item.editMeta(meta -> {
                    var lore = meta.lore();
                    lore.add(Component.text("Right-click to pay " + RESOURCE_PRICE + " " + payment.label,
                            NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                    meta.lore(lore);
                });
            }
            return item;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class BannerMenu implements InventoryHolder {
        private static final int PREVIEW = 10;
        private static final int INPUT = 13;
        private static final int APPLY = 16;
        private final Inventory inventory = Bukkit.createInventory(this, 27, Component.text("Faction Banner"));
        private final CivStore.Village village;

        BannerMenu(CivStore.Village village) {
            this.village = village;
            refreshPreview();
            inventory.setItem(APPLY, button(new ItemStack(Material.LIME_DYE), "Apply Faction Design for " + cashLabel(BANNER_PRICE),
                    "Click a banner or shield in your inventory to insert it.",
                    "Click the item in the middle to take it back without applying."));
        }

        void refreshPreview() {
            var preview = FactionBanner.of(village).applyTo(new ItemStack(Material.WHITE_BANNER));
            inventory.setItem(PREVIEW, button(preview, village.name + " Banner",
                    "Faction owners can use /civ banner set while holding a banner."));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final CivMaker plugin;
    private final CivStore store;
    private final RaidShields shields;
    private final Wallets wallets;
    private final RaidEquipment equipment;
    private final SettlementResources resources;
    private final FoundingBeacons foundingBeacons;

    RaidShop(CivMaker plugin, CivStore store, Wallets wallets, RaidEquipment equipment, SettlementResources resources,
             RaidShields shields, FoundingBeacons foundingBeacons) {
        this.plugin = plugin;
        this.store = store;
        this.shields = shields;
        this.wallets = wallets;
        this.equipment = equipment;
        this.resources = resources;
        this.foundingBeacons = foundingBeacons;
    }

    void open(Player player) {
        player.openInventory(new Menu(equipment, shields, foundingBeacons, store, player).getInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void click(InventoryClickEvent event) {
        var holder = event.getInventory().getHolder();
        if (holder instanceof BannerMenu bannerMenu) {
            clickBannerMenu(event, bannerMenu);
            return;
        }
        if (!(holder instanceof Menu menu)) {
            return;
        }
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        var cursor = event.getCursor();
        boolean alternate = event.getClick() == ClickType.RIGHT;
        if (alreadyCancelled || (!alternate && event.getClick() != ClickType.LEFT) || (cursor != null && !cursor.isEmpty())
                || event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()
                || !(event.getWhoClicked() instanceof Player player) || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        if (event.getRawSlot() == FACTION_SLOT) {
            if (alternate || event.getCurrentItem() == null) {
                return;
            }
            if (!menu.factionBanner) {
                foundingBeacons.buy(player);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                var top = player.getOpenInventory().getTopInventory();
                if (top == null || top.getHolder() != menu) {
                    return;
                }
                var village = store.registeredVillage(player.getUniqueId());
                if (village == null) {
                    player.sendMessage("Join a faction to use the faction banner station.");
                    return;
                }
                try {
                    player.openInventory(new BannerMenu(village).getInventory());
                } catch (IllegalArgumentException error) {
                    player.sendMessage(error.getMessage());
                }
            });
            return;
        }
        Tool tool = menu.products[event.getRawSlot()];
        if (tool == null) {
            return;
        }

        var village = store.registeredVillage(player.getUniqueId());
        long now = System.currentTimeMillis();
        boolean shield = tool == Tool.RAID_SHIELD;
        if (shield) {
            String error = shields.purchaseError(village, now);
            if (error != null) {
                player.sendMessage(error);
                return;
            }
        }
        long price = shield ? shields.price() : PRICE;
        String cash = cashLabel(price);
        long balance = wallets.balance(player);
        if (!alternate && balance < price) {
            player.sendMessage("You need " + cash + " in your wallet.");
            return;
        }
        var purchase = equipment.item(tool, null);
        if (shield) {
            shields.bind(purchase, village);
        }
        var inventory = player.getInventory();
        var contents = inventory.getStorageContents().clone();
        String priceLabel = cash;
        if (alternate) {
            Reward payment = Reward.paymentFor(tool);
            if (payment == null) {
                player.sendMessage("Use left-click to buy this item for " + cash + ".");
                return;
            }
            priceLabel = RESOURCE_PRICE + " " + payment.label;
            if (!resources.debit(contents, payment, RESOURCE_PRICE)) {
                player.sendMessage("You need " + priceLabel + " in your inventory.");
                return;
            }
        }
        if (ItemTransfers.insert(contents, purchase, inventory.getMaxStackSize()) > 0) {
            player.sendMessage("Free up inventory space before buying an item.");
            return;
        }
        inventory.setStorageContents(contents);
        if (!alternate) {
            wallets.setBalance(player, balance - price);
            if (shield) {
                village.shieldBoughtAt = now;
                store.dirty = true;
            }
        }
        String quantity = purchase.getAmount() > 1 ? purchase.getAmount() + " " : "";
        player.sendMessage("Bought " + quantity + tool.label + " for " + priceLabel + ".");
    }

    private void clickBannerMenu(InventoryClickEvent event, BannerMenu menu) {
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        var cursor = event.getCursor();
        if (alreadyCancelled || event.getClick() != ClickType.LEFT || (cursor != null && !cursor.isEmpty())
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack input = menu.inventory.getItem(BannerMenu.INPUT);
        if (event.getRawSlot() == BannerMenu.INPUT && input != null) {
            returnBannerInput(player, menu, false);
            return;
        }
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        if (store.registeredVillage(player.getUniqueId()) != menu.village) {
            player.sendMessage("Reopen the shop to use your current faction's banner.");
            return;
        }
        if (event.getClickedInventory() == event.getView().getBottomInventory()) {
            var inventory = player.getInventory();
            int slot = event.getSlot();
            ItemStack item = inventory.getItem(slot);
            if (item != null && (item.getType() == Material.SHIELD || FactionBanner.isBanner(item))) {
                if (input != null) {
                    player.sendMessage("Take the item in the middle before inserting another.");
                    return;
                }
                menu.inventory.setItem(BannerMenu.INPUT, item.asOne());
                inventory.setItem(slot, item.getAmount() == 1 ? null : item.asQuantity(item.getAmount() - 1));
            }
        } else if (event.getRawSlot() == BannerMenu.APPLY) {
            if (input == null) {
                player.sendMessage("Click a banner or shield in your inventory to insert it first.");
                return;
            }
            long balance = wallets.balance(player);
            if (balance < BANNER_PRICE) {
                player.sendMessage("You need " + cashLabel(BANNER_PRICE) + " in your wallet to apply the faction design.");
                return;
            }
            try {
                var design = FactionBanner.of(menu.village);
                var decorated = design.applyTo(input);
                var inventory = player.getInventory();
                var contents = inventory.getStorageContents().clone();
                if (ItemTransfers.insert(contents, decorated, inventory.getMaxStackSize()) > 0) {
                    player.sendMessage("Make room for the decorated item in your inventory before applying the design.");
                    return;
                }
                menu.refreshPreview();
                inventory.setStorageContents(contents);
                menu.inventory.clear(BannerMenu.INPUT);
                wallets.setBalance(player, balance - BANNER_PRICE);
                player.sendMessage("Applied your faction banner design for " + cashLabel(BANNER_PRICE) + ".");
            } catch (IllegalArgumentException error) {
                player.sendMessage(error.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void drag(InventoryDragEvent event) {
        var holder = event.getInventory().getHolder();
        if (holder instanceof Menu || holder instanceof BannerMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof BannerMenu menu && event.getPlayer() instanceof Player player) {
            returnBannerInput(player, menu, true);
        }
    }

    private static void returnBannerInput(Player player, BannerMenu menu, boolean closing) {
        ItemStack input = menu.inventory.getItem(BannerMenu.INPUT);
        if (input == null) {
            return;
        }
        var inventory = player.getInventory();
        var contents = inventory.getStorageContents().clone();
        int remainder = ItemTransfers.insert(contents, input, inventory.getMaxStackSize());
        if (remainder > 0 && !closing) {
            player.sendMessage("Free up inventory space to take the item back.");
            return;
        }
        menu.inventory.clear(BannerMenu.INPUT);
        inventory.setStorageContents(contents);
        if (remainder > 0) {
            player.getWorld().dropItemNaturally(player.getLocation(), input.asQuantity(remainder),
                    dropped -> dropped.setOwner(player.getUniqueId()));
        }
    }

    private static ItemStack button(ItemStack item, String label, String... lines) {
        item.editMeta(meta -> {
            meta.displayName(Component.text(label, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(Arrays.stream(lines).<Component>map(line -> Component.text(line, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    private static String cashLabel(long price) {
        return String.format(Locale.US, "$%,d", price);
    }

    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            var holder = top == null ? null : top.getHolder();
            if (holder instanceof Menu || holder instanceof BannerMenu) {
                if (holder instanceof BannerMenu bannerMenu) {
                    returnBannerInput(player, bannerMenu, true);
                }
                player.closeInventory();
            }
        }
    }
}
