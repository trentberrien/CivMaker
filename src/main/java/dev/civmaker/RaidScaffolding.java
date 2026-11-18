package dev.civmaker;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import dev.civmaker.CivStore.Position;
import dev.civmaker.RaidEquipment.Tool;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.ExplosionResult;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Scaffolding;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class RaidScaffolding implements Listener {
    static final long CLEAR_DELAY = 30 * 60 * 1000L;

    private final CivStore store;
    private final RaidEquipment equipment;
    private final CivMaker plugin;
    private final Set<Position> brokenDrops = new HashSet<>();

    RaidScaffolding(CivMaker plugin, CivStore store, RaidEquipment equipment) {
        this.plugin = plugin;
        this.store = store;
        this.equipment = equipment;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void placed(BlockPlaceEvent event) {
        if (!event.canBuild()) {
            return;
        }
        boolean raidItem = equipment.identify(event.getItemInHand()) == Tool.RAID_SCAFFOLDING;
        var window = store.activeWindow(System.currentTimeMillis());
        for (BlockState state : placementStates(event)) {
            // The same placement can update neighboring scaffolds' distance/bottom data.
            // Those blocks keep their original identity and cleanup deadline.
            if (connectionUpdate(state)) continue;
            Position position = Position.at(state.getBlock());
            brokenDrops.remove(position);
            store.forgetRaidScaffolding(position);
            if (raidItem && window != null && state.getBlock().getType() == Material.SCAFFOLDING) {
                store.recordRaidScaffolding(position, window);
            }
        }
    }

    static List<BlockState> placementStates(BlockPlaceEvent event) {
        return event instanceof BlockMultiPlaceEvent multi ? multi.getReplacedBlockStates()
                : List.of(event.getBlockReplacedState());
    }

    static boolean connectionUpdate(BlockState previous) {
        return previous.getType() == Material.SCAFFOLDING
                && previous.getBlockData() instanceof Scaffolding before
                && previous.getBlock().getBlockData() instanceof Scaffolding after
                && before.isWaterlogged() == after.isWaterlogged();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void broken(BlockBreakEvent event) {
        if (store.isRaidScaffolding(event.getBlock()) && event.isDropItems()) {
            Position position = Position.at(event.getBlock());
            brokenDrops.add(position);
            afterEvent(() -> brokenDrops.remove(position));
        }
        forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void dropped(BlockDropItemEvent event) {
        if (brokenDrops.remove(Position.at(event.getBlock()))) {
            event.getItems().forEach(this::tagDrop);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void collapseDrop(BlockDestroyEvent event) {
        if (!store.isRaidScaffolding(event.getBlock()) || !event.willDrop()) return;
        // Natural support loss has no BlockDropItemEvent. Replace its one vanilla drop only
        // after the destruction event has finished, so a later cancellation creates no item.
        event.setWillDrop(false);
        var location = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        afterEvent(() -> {
            if (!event.isCancelled() && blockDropsEnabled(location.getWorld())) {
                location.getWorld().dropItemNaturally(location, equipment.item(Tool.RAID_SCAFFOLDING, null).asQuantity(1));
            }
        });
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
    void explosionDrops(EntityExplodeEvent event) {
        explosionDrops(event, event.blockList(), event.getYield(), event.getExplosionResult());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void explosionDrops(BlockExplodeEvent event) {
        explosionDrops(event, event.blockList(), event.getYield(), event.getExplosionResult());
    }

    private void explosionDrops(Cancellable event, List<Block> blocks, float yield, ExplosionResult result) {
        if (result != ExplosionResult.DESTROY && result != ExplosionResult.DESTROY_WITH_DECAY) return;
        var temporary = blocks.stream().filter(store::isRaidScaffolding).toList();
        if (temporary.isEmpty()) return;
        // Explosion loot merges ordinary and custom scaffolding before spawning it. Handle
        // these blocks separately so an ordinary stack cannot acquire the raid item's tag.
        var original = temporary.stream().map(block -> store.raidScaffoldingAt(Position.at(block))).toList();
        blocks.removeAll(temporary);
        afterEvent(() -> {
            if (event.isCancelled()) return;
            for (int index = 0; index < temporary.size(); index++) {
                Block block = temporary.get(index);
                Position position = Position.at(block);
                if (!store.isRaidScaffolding(block) || store.raidScaffoldingAt(position) != original.get(index)) continue;
                boolean waterlogged = block.getBlockData() instanceof Scaffolding data && data.isWaterlogged();
                store.forgetRaidScaffolding(position);
                block.setType(waterlogged ? Material.WATER : Material.AIR, true);
                if (ThreadLocalRandom.current().nextFloat() < yield
                        && blockDropsEnabled(block.getWorld())) {
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                            equipment.item(Tool.RAID_SCAFFOLDING, null).asQuantity(1));
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void falling(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)
                || falling.getBlockData().getMaterial() != Material.SCAFFOLDING) return;
        if (store.isRaidScaffolding(event.getBlock())
                && (event.getTo() == Material.AIR || event.getTo() == Material.WATER)) {
            store.startRaidScaffoldingFall(Position.at(event.getBlock()), falling.getUniqueId());
        } else if (event.getTo() == Material.SCAFFOLDING) {
            store.landRaidScaffolding(falling.getUniqueId(), Position.at(event.getBlock()));
        }
    }

    @EventHandler
    void loaded(EntityAddToWorldEvent event) {
        var scaffold = store.fallingRaidScaffolding(event.getEntity().getUniqueId());
        if (scaffold != null) {
            store.closeRaidScaffoldingWindows(System.currentTimeMillis());
            scaffold = store.fallingRaidScaffolding(event.getEntity().getUniqueId());
            if (scaffold.clearAt() <= System.currentTimeMillis()) {
                event.getEntity().remove();
                store.forgetFallingRaidScaffolding(scaffold.fallingEntity());
            }
        }
    }

    @EventHandler
    void removed(EntityRemoveEvent event) {
        if (event.getCause() == EntityRemoveEvent.Cause.DROP) {
            // Paper can remove a falling block before firing its EntityDropItemEvent.
            afterEvent(() -> store.forgetFallingRaidScaffolding(event.getEntity().getUniqueId()));
        } else if (event.getCause() != EntityRemoveEvent.Cause.UNLOAD) {
            store.forgetFallingRaidScaffolding(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void fallingDrop(EntityDropItemEvent event) {
        if (store.fallingRaidScaffolding(event.getEntity().getUniqueId()) != null) {
            tagDrop(event.getItemDrop());
        }
    }

    private void tagDrop(org.bukkit.entity.Item dropped) {
        var stack = dropped.getItemStack();
        if (stack.getType() == Material.SCAFFOLDING) {
            dropped.setItemStack(equipment.item(Tool.RAID_SCAFFOLDING, null).asQuantity(stack.getAmount()));
        }
    }

    private void afterEvent(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private static boolean blockDropsEnabled(org.bukkit.World world) {
        return !"false".equals(world.getGameRuleValue("minecraft:block_drops"));
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
            if (scaffold.fallingEntity() != null) {
                var falling = Bukkit.getEntity(scaffold.fallingEntity());
                if (falling != null) {
                    falling.remove();
                    store.forgetFallingRaidScaffolding(scaffold.fallingEntity());
                }
                // Keep unloaded entities registered so loaded() can remove them on return.
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
                boolean waterlogged = block.getBlockData() instanceof Scaffolding data && data.isWaterlogged();
                block.setType(waterlogged ? Material.WATER : Material.AIR, false);
            }
            store.forgetRaidScaffolding(position);
        }
    }
}
