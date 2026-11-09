package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Settlement;
import dev.civmaker.CivStore.Village;
import dev.civmaker.SettlementResources.Reward;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SettlementBuying implements Listener {
    private static final int BUY_SLOT = 11;
    private static final int CANCEL_SLOT = 15;

    private record Claimant(Settlement settlement, Villager villager) {}

    private static final class Menu implements InventoryHolder {
        final Position bell;
        final Villager claimant;
        final Inventory inventory;

        Menu(Settlement settlement, Block bell, Villager claimant, String cost, long balance) {
            this.bell = settlement.bell();
            this.claimant = claimant;
            inventory = Bukkit.createInventory(this, 27, Component.text("Claim the Village"));
            inventory.setItem(4, item(Material.BELL, settlement.displayName(bell),
                    settlement.plots().size() + " protected chunks",
                    "+" + CivStore.SETTLEMENT_POINTS + " village points",
                    "The vault replaces this villager.",
                    "Daily vault reward: " + Reward.at(settlement.structure(), bell).label));
            inventory.setItem(BUY_SLOT, item(Material.EMERALD, "Buy village for " + cost,
                    String.format(Locale.US, "Wallet balance: $%,d", balance),
                    "Purchase requires a village owner in Survival."));
            inventory.setItem(CANCEL_SLOT, item(Material.BARRIER, "Cancel"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final CivMaker plugin;
    private final CivStore store;
    private final Wallets wallets;
    private final SettlementVaults vaults;
    private final NaturalVillages natural;
    private final String cost;
    private final long price;
    private final Map<Position, Claimant> claimants = new HashMap<>();
    private final Map<UUID, Claimant> byEntity = new HashMap<>();

    SettlementBuying(CivMaker plugin, CivStore store, Wallets wallets, SettlementVaults vaults, NaturalVillages natural) {
        this.plugin = plugin;
        this.store = store;
        this.wallets = wallets;
        this.vaults = vaults;
        this.natural = natural;
        price = Math.max(0, plugin.getConfig().getLong("village.settlement-price", 50000));
        cost = String.format(Locale.US, "$%,d", price);
    }

    void command(Player player, Village village, String[] args) {
        boolean buy = args.length == 2 && args[1].equalsIgnoreCase("buy");
        boolean vault = args.length == 2 && args[1].equalsIgnoreCase("vault");
        if (args.length > 1 && !buy && !vault) {
            player.sendMessage("Use /civ settlement [buy|vault] while looking at a village bell.");
            return;
        }

        Block bell = player.getTargetBlockExact(5);
        if (bell == null || bell.getType() != Material.BELL) {
            player.sendMessage("Look at a natural village bell within five blocks.");
            return;
        }

        Settlement existing = store.settlementAt(Plot.at(bell));
        if (vault) {
            if (existing != null && existing.bell().equals(Position.at(bell)) && store.claimAt(bell) == village) {
                if (vaults.hasPlacedVault(existing)) {
                    player.sendMessage("This settlement already has a placed vault.");
                } else if (placeVault(player, bell, existing, standingSpot(bell, existing))) {
                    player.sendMessage("Placed your Settlement Vault beside the bell.");
                }
            } else {
                player.sendMessage("Look at a settlement bell owned by your village.");
            }
            return;
        }

        Settlement settlement = available(player, bell);
        if (settlement == null) {
            return;
        }
        if (buy) {
            Claimant claimant = claimants.get(settlement.bell());
            Location spot = claimant != null && claimant.villager().isValid()
                    ? claimant.villager().getLocation() : standingSpot(bell, settlement);
            buy(player, village, bell, settlement, spot);
        } else {
            player.sendMessage("Settlement purchase: " + cost + " for " + settlement.plots().size()
                    + " protected chunks and " + CivStore.SETTLEMENT_POINTS + " points.");
            player.sendMessage("Its Settlement Vault produces " + Reward.at(settlement.structure(), bell).label + " daily.");
            player.sendMessage("Talk to the villager by the bell or run /civ settlement buy to confirm.");
        }
    }

    private Settlement available(Player player, Block bell) {
        Settlement existing = store.settlementAt(Plot.at(bell));
        if (existing != null && existing.bell().equals(Position.at(bell))) {
            player.sendMessage("This settlement already belongs to " + store.claimAt(bell).name + ".");
            return null;
        }
        Settlement settlement = natural.findSettlement(bell);
        if (settlement == null) {
            player.sendMessage("This bell is not eligible. Use a bell in a naturally generated village.");
            return null;
        }
        for (Plot plot : settlement.plots()) {
            Village owner = store.claimAt(plot);
            if (owner != null) {
                player.sendMessage("This settlement overlaps a chunk already claimed by " + owner.name + ".");
                return null;
            }
        }
        return settlement;
    }

    private void buy(Player player, Village village, Block bell, Settlement settlement, Location spot) {
        long balance = wallets.balance(player);
        if (balance < price) {
            player.sendMessage("Your wallet needs " + cost + " to buy this settlement.");
            return;
        }
        if (!placeVault(player, bell, settlement, spot)) {
            return;
        }
        wallets.setBalance(player, balance - price);
        store.addSettlement(village, settlement);
        player.sendMessage("Bought a settlement for " + cost + ". The Settlement Vault is beside the bell. "
                + village.name + " now has " + village.points() + " points.");
        refreshClaimants();
    }

    private boolean placeVault(Player player, Block bell, Settlement settlement, Location spot) {
        if (spot != null && vaults.placeAt(spot.getBlock(), bell, settlement)) {
            return true;
        }
        player.sendMessage("There is no clear spot for the Settlement Vault. Try again when the area by the bell is clear.");
        return false;
    }

    void refreshClaimants() {
        Set<Position> available = new HashSet<>();
        for (Settlement settlement : natural.centers()) {
            if (settlement.plots().stream().anyMatch(plot -> store.settlementAt(plot) != null)) {
                continue;
            }
            Position position = settlement.bell();
            Block bell = loadedBell(position);
            if (bell == null) {
                continue;
            }
            available.add(position);
            Claimant current = claimants.get(position);
            if (current != null && current.villager().isValid()) {
                continue;
            }
            removeClaimant(claimants.remove(position));
            Location spot = standingSpot(bell, settlement);
            if (spot != null) {
                Villager villager = bell.getWorld().spawn(spot, Villager.class, npc -> {
                    npc.customName(Component.text("talk to me to claim the village", NamedTextColor.GOLD));
                    npc.setCustomNameVisible(true);
                    npc.setAI(false);
                    npc.setInvulnerable(true);
                    npc.setCollidable(false);
                    npc.setPersistent(false);
                    npc.setCanPickupItems(false);
                    npc.setRemoveWhenFarAway(false);
                });
                Claimant claimant = new Claimant(settlement, villager);
                claimants.put(position, claimant);
                byEntity.put(villager.getUniqueId(), claimant);
            }
        }
        claimants.entrySet().removeIf(entry -> {
            if (available.contains(entry.getKey())) {
                return false;
            }
            removeClaimant(entry.getValue());
            return true;
        });
    }

    private Block loadedBell(Position position) {
        World world = plugin.getServer().getWorld(position.world());
        Plot plot = position.plot();
        if (world == null || !world.isChunkLoaded(plot.x(), plot.z())) {
            return null;
        }
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        return block.getType() == Material.BELL ? block : null;
    }

    private static Location standingSpot(Block bell, Settlement settlement) {
        World world = bell.getWorld();
        for (int radius = 1; radius <= 6; radius++) {
            for (int dy : new int[]{0, -1, 1, -2, 2}) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) != radius) {
                            continue;
                        }
                        int x = bell.getX() + dx;
                        int y = bell.getY() + dy;
                        int z = bell.getZ() + dz;
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                            continue;
                        }
                        Block feet = world.getBlockAt(x, y, z);
                        Block ground = feet.getRelative(BlockFace.DOWN);
                        if (SettlementVaults.canPlaceAt(feet, settlement)
                                && ground.getType().isSolid() && ground.getBoundingBox().getMaxY() == y) {
                            Location spot = new Location(world, x + 0.5, y, z + 0.5);
                            spot.setDirection(bell.getLocation().add(0.5, 0, 0.5).subtract(spot).toVector());
                            return spot;
                        }
                    }
                }
            }
        }
        return null;
    }

    private void removeClaimant(Claimant claimant) {
        if (claimant != null) {
            byEntity.remove(claimant.villager().getUniqueId());
            claimant.villager().remove();
        }
    }

    void closeAll() {
        claimants.values().forEach(this::removeClaimant);
        claimants.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                player.closeInventory();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void interact(PlayerInteractEntityEvent event) {
        Claimant claimant = byEntity.get(event.getRightClicked().getUniqueId());
        if (claimant == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            Player player = event.getPlayer();
            Block bell = loadedBell(claimant.settlement().bell());
            Settlement settlement = bell == null ? null : available(player, bell);
            if (settlement != null) {
                player.openInventory(new Menu(settlement, bell, claimant.villager(), cost, wallets.balance(player)).inventory);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void damage(EntityDamageEvent event) {
        if (byEntity.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
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
                confirm(player, menu);
            }
        });
    }

    private void confirm(Player player, Menu menu) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            player.sendMessage("Switch to Survival to buy a settlement.");
            return;
        }
        Village village = store.memberOf(player.getUniqueId());
        if (village == null || !village.owner.equals(player.getUniqueId())) {
            player.sendMessage("Only a village owner can buy a settlement.");
            return;
        }
        Block bell = loadedBell(menu.bell);
        Location spot = menu.claimant.getLocation();
        if (bell == null || !menu.claimant.isValid() || !player.getWorld().equals(spot.getWorld())
                || player.getLocation().distanceSquared(spot) > 64) {
            player.sendMessage("Talk to the villager again to buy this settlement.");
            return;
        }
        Settlement settlement = available(player, bell);
        if (settlement != null) {
            buy(player, village, bell, settlement, spot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
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
}
