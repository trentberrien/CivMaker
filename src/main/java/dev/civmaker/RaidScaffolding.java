package dev.civmaker;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import dev.civmaker.CivStore.Position;
import dev.civmaker.RaidEquipment.Tool;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

final class RaidScaffolding implements Listener {
    static final long CLEAR_DELAY = 30 * 60 * 1000L;

    private final CivStore store;
    private final RaidEquipment equipment;

    RaidScaffolding(CivStore store, RaidEquipment equipment) {
        this.store = store;
        this.equipment = equipment;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void placed(BlockPlaceEvent event) {
        if (!event.canBuild()) {
            return;
        }
        Position position = Position.at(event.getBlockPlaced());
        store.forgetRaidScaffolding(position);
        if (equipment.identify(event.getItemInHand()) == Tool.RAID_SCAFFOLDING) {
            var window = store.activeWindow(System.currentTimeMillis());
            if (window != null) {
                store.recordRaidScaffolding(position, window);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void broken(BlockBreakEvent event) {
        forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void dropped(BlockDropItemEvent event) {
        forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void destroyed(BlockDestroyEvent event) {
        forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void burned(BlockBurnEvent event) {
        forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void exploded(EntityExplodeEvent event) {
        event.blockList().forEach(this::forget);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void exploded(BlockExplodeEvent event) {
        event.blockList().forEach(this::forget);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void falling(EntityChangeBlockEvent event) {
        // Keep temporary blocks at their saved coordinates until removed or cleaned up.
        if (store.isRaidScaffolding(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void extend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(store::isRaidScaffolding)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void retract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(store::isRaidScaffolding)) {
            event.setCancelled(true);
        }
    }

    private void forget(Block block) {
        store.forgetRaidScaffolding(Position.at(block));
    }

    void refresh() {
        refresh(System.currentTimeMillis());
    }

    void refresh(long now) {
        store.closeRaidScaffoldingWindows(now);
        for (var scaffold : store.raidScaffolding()) {
            if (scaffold.clearAt() > now) {
                continue;
            }
            Position position = scaffold.position();
            var world = Bukkit.getWorld(position.world());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(position.x(), position.y(), position.z());
            if (block.getType() == Material.SCAFFOLDING) {
                // No drops or physics: cleanup must not break neighboring permanent blocks.
                block.setType(Material.AIR, false);
            }
            store.forgetRaidScaffolding(position);
        }
    }
}
