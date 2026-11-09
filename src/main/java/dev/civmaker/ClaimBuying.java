package dev.civmaker;

import dev.civmaker.CivStore.ClaimPurchase;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Village;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class ClaimBuying implements Listener {
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
        if ((event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR)
                || !buying.contains(player.getUniqueId()) || !player.isSneaking() || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        Village village = store.memberOf(player.getUniqueId());
        if (village == null || !village.owner.equals(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            buy(player, village);
        }
    }

    @EventHandler
    void quit(PlayerQuitEvent event) {
        stop(event.getPlayer().getUniqueId());
    }

    void stop(UUID player) {
        buying.remove(player);
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
        if (village.capitalExpansionCount() >= CivStore.CAPITAL_EXPANSION_LIMIT) {
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
            case AT_CAPACITY -> "Your capital has reached its limit of " + CivStore.CAPITAL_EXPANSION_LIMIT + " expansion chunks.";
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
            Village village = store.memberOf(id);
            if (player != null && player.getGameMode() == GameMode.SURVIVAL && village != null && village.owner.equals(id)) {
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
