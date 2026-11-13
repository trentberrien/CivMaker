package dev.civmaker;

import dev.civmaker.CivStore.ClaimPurchase;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class ClaimBuying implements Listener {
    static final String PROMOTION_MESSAGE = "You must be promoted to elder to buy plots for your civilization. Ask your owner to use /civ promote <player>.";
    private static final int BUY_SLOT = 11;
    private static final int CANCEL_SLOT = 15;

    private static final class UpgradeMenu implements InventoryHolder {
        final Village village;
        final Inventory inventory;

        UpgradeMenu(Village village) {
            this.village = village;
            inventory = Bukkit.createInventory(this, 27, Component.text("Upgrade Land Claims"));
            inventory.setItem(4, item(Material.BEACON, "Expand " + village.name,
                    "Increase the expansion limit from 100 to 200 chunks.",
                    "Unlock vanilla beacon effects and effect selection.",
                    "One upgrade for your entire civilization.",
                    "Current expansions: " + village.capitalExpansionCount() + "/" + village.capitalExpansionLimit()));
            inventory.setItem(BUY_SLOT, item(Material.BEACON, "Deposit 1 Beacon to upgrade",
                    "Consumes one ordinary beacon from your inventory.",
                    "Craft it normally using a Nether Star.",
                    "Founding Beacons cannot be deposited. No money cost.",
                    "Requires an elder or owner in Survival."));
            inventory.setItem(CANCEL_SLOT, item(Material.BARRIER, "Cancel"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private enum Status {
        OWNED(Color.BLUE), FOR_SALE(Color.LIME), OTHER_VILLAGE(Color.RED), NATURAL_VILLAGE(Color.RED),
        NOT_ADJACENT(Color.GRAY), AT_CAPACITY(Color.GRAY), TOO_EXPENSIVE(Color.GRAY);

        final Particle.DustOptions dust;

        Status(Color color) {
            dust = new Particle.DustOptions(color, 1.4f);
        }
    }

    private final CivMaker plugin;
    private final CivStore store;
    private final Wallets wallets;
    private final NaturalVillages natural;
    private final long price;
    private final Set<UUID> buying = new HashSet<>();

    ClaimBuying(CivMaker plugin, CivStore store, Wallets wallets, NaturalVillages natural) {
        this.plugin = plugin;
        this.store = store;
        this.wallets = wallets;
        this.natural = natural;
        price = Math.max(0, plugin.getConfig().getLong("village.chunk-price"));
    }

    void command(Player player, Village village, String[] args) {
        if (!canBuy(player, village)) {
            return;
        }
        if (args.length == 1) {
            buy(player, village);
        } else if (args.length == 2) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "mode" -> toggle(player, village);
                case "undo" -> undo(player, village);
                default -> player.sendMessage("Use /civ claim [mode|undo].");
            }
        } else {
            player.sendMessage("Use /civ claim [mode|undo].");
        }
    }

    private void toggle(Player player, Village village) {
        if (buying.remove(player.getUniqueId())) {
            player.sendMessage("Claim buying mode off.");
        } else {
            buying.add(player.getUniqueId());
            player.sendMessage("Claim buying mode on. Sneak and right-click a block to buy the chunk you're standing in.");
            showBorder(player, village);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void click(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Block block = event.getClickedBlock();
        Village capital = block == null || block.getType() != Material.BEACON ? null : store.claimAt(block);
        if (capital != null && capital.beacon.equals(Position.at(block))) {
            if (capital.expandedClaims) {
                return;
            }
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND && canUpgrade(player, capital)) {
                player.openInventory(new UpgradeMenu(capital).inventory);
            }
            return;
        }
        if (!player.isSneaking() || !buying.contains(player.getUniqueId()) || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            Village village = store.registeredVillage(player.getUniqueId());
            if (canBuy(player, village)) {
                buy(player, village);
            } else {
                stop(player.getUniqueId());
            }
        }
    }

    private boolean canBuy(Player player, Village village) {
        if (village == null || store.registeredVillage(player.getUniqueId()) != village) {
            player.sendMessage("You must belong to this civilization to buy plots.");
            return false;
        }
        if (!village.canManage(player.getUniqueId())) {
            player.sendMessage(PROMOTION_MESSAGE);
            return false;
        }
        return true;
    }

    private boolean canUpgrade(Player player, Village village) {
        if (!canBuy(player, village)) {
            return false;
        }
        if (player.getGameMode() != GameMode.SURVIVAL) {
            player.sendMessage("Switch to Survival to upgrade your land claims.");
            return false;
        }
        if (village.expandedClaims) {
            player.sendMessage("Your civilization already has the maximum of 200 expansion claims.");
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void menuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof UpgradeMenu menu)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (!(event.getWhoClicked() instanceof Player player) || !event.getClick().isLeftClick()
                || (slot != BUY_SLOT && slot != CANCEL_SLOT)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) {
                return;
            }
            player.closeInventory();
            if (slot == BUY_SLOT) {
                depositUpgrade(player, menu.village);
            }
        });
    }

    private void depositUpgrade(Player player, Village village) {
        if (!store.hasVillage(village.id) || !canUpgrade(player, village)) {
            return;
        }
        Position position = village.beacon;
        if (!player.getWorld().getUID().equals(position.world())
                || !player.getWorld().isChunkLoaded(position.x() >> 4, position.z() >> 4)
                || player.getWorld().getBlockAt(position.x(), position.y(), position.z()).getType() != Material.BEACON
                || player.getEyeLocation().distanceSquared(new Location(player.getWorld(),
                        position.x() + 0.5, position.y() + 0.5, position.z() + 0.5)) > 36) {
            player.sendMessage("Move closer to your capital beacon and open the upgrade menu again.");
            return;
        }
        if (!consumeUpgradeBeacon(player)) {
            player.sendMessage("You need an ordinary beacon crafted with a Nether Star in your inventory. Founding Beacons cannot be deposited.");
            return;
        }
        village.expandedClaims = true;
        store.dirty = true;
        player.sendMessage("Deposited 1 Beacon and upgraded " + village.name
                + " to 200 expansion claims. Your capital beacon can now grant normal effects when powered by a pyramid.");
    }

    private boolean consumeUpgradeBeacon(Player player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == Material.BEACON && item.getAmount() > 0
                    && !item.getPersistentDataContainer().has(FoundingBeacons.BEACON_KEY)) {
                inventory.setItem(slot, item.getAmount() == 1 ? null : item.asQuantity(item.getAmount() - 1));
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void menuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof UpgradeMenu) {
            event.setCancelled(true);
        }
    }

    private static ItemStack item(Material material, String name, String... lines) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(lines).stream().map(line ->
                    Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    @EventHandler
    void quit(PlayerQuitEvent event) {
        stop(event.getPlayer().getUniqueId());
    }

    void stop(UUID player) {
        buying.remove(player);
    }

    void closeAll() {
        buying.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof UpgradeMenu) {
                player.closeInventory();
            }
        }
    }

    private Status status(Player player, Village village, Plot plot, long balance) {
        Village claimed = store.claimAt(plot);
        if (claimed != null) {
            return claimed == village ? Status.OWNED : Status.OTHER_VILLAGE;
        }
        if (natural.reserved(player.getWorld(), plot)) {
            return Status.NATURAL_VILLAGE;
        }
        if (plot.neighbors().stream().noneMatch(neighbor -> store.isCapitalClaim(village, neighbor))) {
            return Status.NOT_ADJACENT;
        }
        if (village.capitalExpansionCount() >= village.capitalExpansionLimit()) {
            return Status.AT_CAPACITY;
        }
        return balance >= price ? Status.FOR_SALE : Status.TOO_EXPENSIVE;
    }

    private void buy(Player player, Village village) {
        Plot plot = Plot.at(player.getLocation());
        long balance = wallets.balance(player);
        String error = switch (status(player, village, plot, balance)) {
            case OWNED -> "Your village already owns this chunk.";
            case OTHER_VILLAGE -> "Another village already owns this chunk.";
            case NATURAL_VILLAGE -> "This chunk is reserved for a natural village. Talk to the villager by its bell.";
            case NOT_ADJACENT -> "Stand in an unclaimed chunk that shares an edge with your capital's territory.";
            case AT_CAPACITY -> "Your capital has reached its limit of " + village.capitalExpansionLimit() + " expansion chunks."
                    + (village.expandedClaims ? "" : " Right-click your capital beacon to deposit 1 ordinary beacon and upgrade to 200.");
            case TOO_EXPENSIVE -> "Your wallet needs $" + price + " for this claim.";
            case FOR_SALE -> null;
        };
        if (error != null) {
            player.sendMessage(error);
            return;
        }
        wallets.setBalance(player, balance - price);
        store.addPlot(village, plot);
        village.lastPurchase = new ClaimPurchase(plot, price);
        player.sendMessage("Claimed chunk " + plot.x() + ", " + plot.z() + " for $" + price + ".");
    }

    private void undo(Player player, Village village) {
        ClaimPurchase purchase = village.lastPurchase;
        if (purchase == null || !store.isCapitalClaim(village, purchase.plot()) || purchase.plot().equals(village.beacon.plot())) {
            player.sendMessage("There is no recent claim purchase to undo.");
            return;
        }
        Plot plot = purchase.plot();
        long balance = wallets.balance(player);
        if (balance > Long.MAX_VALUE - purchase.price()) {
            player.sendMessage("Your wallet cannot hold the refund.");
            return;
        }
        store.removePlot(village, plot);
        village.lastPurchase = null;
        wallets.setBalance(player, balance + purchase.price());
        player.sendMessage("Released chunk " + plot.x() + ", " + plot.z() + " and refunded $" + purchase.price() + ".");
    }

    void showBorders() {
        for (UUID id : buying) {
            Player player = plugin.getServer().getPlayer(id);
            Village village = store.registeredVillage(id);
            if (player != null && player.getGameMode() == GameMode.SURVIVAL && village != null && village.canManage(id)) {
                showBorder(player, village);
            }
        }
    }

    private void showBorder(Player player, Village village) {
        var location = player.getLocation();
        Plot plot = Plot.at(location);
        var dust = status(player, village, plot, wallets.balance(player)).dust;
        int x = plot.x() * 16;
        int z = plot.z() * 16;
        double y = location.getY() + 0.75;
        for (int offset = 0; offset < 16; offset++) {
            player.spawnParticle(Particle.DUST, x + offset, y, z, 1, 0, 0, 0, 0, dust, true);
            player.spawnParticle(Particle.DUST, x + 16, y, z + offset, 1, 0, 0, 0, 0, dust, true);
            player.spawnParticle(Particle.DUST, x + 16 - offset, y, z + 16, 1, 0, 0, 0, 0, dust, true);
            player.spawnParticle(Particle.DUST, x, y, z + 16 - offset, 1, 0, 0, 0, 0, dust, true);
        }
    }
}
