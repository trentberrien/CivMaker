package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** The actual egg remains a vanilla block; its non-occluding shape lets beacon beams pass through. */
final class DragonEggs implements Listener {
    private final CivMaker plugin;
    private final CivStore store;

    DragonEggs(CivMaker plugin, CivStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void refresh() {
        long now = System.currentTimeMillis();
        for (Village village : store.villages()) {
            Position beacon = village.beacon;
            var world = plugin.getServer().getWorld(beacon.world());
            if (world == null) {
                store.observeEgg(village, false, now);
            } else if (world.isChunkLoaded(beacon.x() >> 4, beacon.z() >> 4) || village.dragonEgg) {
                // A saved holder is checked even after restart. Unloaded empty capitals need no scan.
                store.observeEgg(village, world.getBlockAt(beacon.x(), beacon.y(), beacon.z()).getType() == Material.BEACON
                        && world.getBlockAt(beacon.x(), beacon.y() + 1, beacon.z()).getType() == Material.DRAGON_EGG, now);
            }
        }
        store.refreshEggRaids(now);
    }

    static boolean placingOnCapital(PlayerInteractEvent event, CivStore store) {
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.BEACON || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getBlockFace() != BlockFace.UP || event.getItem() == null
                || event.getItem().getType() != Material.DRAGON_EGG) return false;
        Village village = store.claimAt(block);
        return village != null && village.beacon.equals(Position.at(block))
                && store.registeredVillage(event.getPlayer().getUniqueId()) == village;
    }

    static boolean canBreak(CivStore store, Village village, Player player) {
        store.refreshEggRaids(System.currentTimeMillis());
        return !village.eggProtected && player.getGameMode() == GameMode.SURVIVAL
                && store.activeWindow(System.currentTimeMillis()) != null
                && store.raidCovers(village.beacon.plot()) && !store.raidRestricted(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void placed(BlockPlaceEvent event) {
        if (!event.canBuild()) return;
        Village village = store.eggAt(event.getBlockPlaced());
        if (village != null) {
            long now = System.currentTimeMillis();
            store.refreshEggRaids(now);
            store.observeEgg(village, true, now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void broken(BlockBreakEvent event) {
        Village village = store.eggAt(event.getBlock());
        if (village != null) {
            long now = System.currentTimeMillis();
            store.refreshEggRaids(now);
            store.observeEgg(village, false, now);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void interact(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        Village village = block == null ? null : store.eggAt(block);
        if (village == null) return;
        // Suppress both vanilla teleport paths. A left click instead requests a normal, cancellable break.
        boolean denied = event.useInteractedBlock() == Event.Result.DENY;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || denied) return;
        if (!canBreak(store, village, event.getPlayer())) {
            event.getPlayer().sendMessage(village.eggProtected ? "This dragon egg is permanently protected."
                    : "This dragon egg can only be taken during a raid open to this capital.");
            return;
        }
        BlockBreakEvent broken = new BlockBreakEvent(block, event.getPlayer());
        plugin.getServer().getPluginManager().callEvent(broken);
        if (!broken.isCancelled() && block.getType() == Material.DRAGON_EGG) {
            block.setType(Material.AIR, false);
            if (broken.isDropItems()) {
                block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(Material.DRAGON_EGG));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void death(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && killer.getGameMode() == GameMode.SURVIVAL && victim.getGameMode() == GameMode.SURVIVAL) {
            store.recordRaidKill(killer.getUniqueId(), victim.getUniqueId(), System.currentTimeMillis());
        }
    }
}
