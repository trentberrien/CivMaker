package dev.civmaker;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import dev.civmaker.CivStore.Position;
import dev.civmaker.FarmProduce.Crop;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FarmHarvests implements Listener {
    private record HandHarvest(Crop crop, int tick) {}

    private final CivMaker plugin;
    private final FarmStations stations;
    private final Map<Position, HandHarvest> handHarvests = new HashMap<>();

    FarmHarvests(CivMaker plugin, FarmStations stations) {
        this.plugin = plugin;
        this.stations = stations;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void recordHandHarvest(BlockBreakEvent event) {
        if (!event.isDropItems() || event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Block block = event.getBlock();
        Crop crop = FarmProduce.crop(block.getType());
        if (crop != null) {
            creditNextSegment(block, crop);
            return;
        }

        // A player can harvest a stalk by breaking its support as well as the stalk itself.
        for (BlockFace direction : List.of(BlockFace.UP, BlockFace.DOWN)) {
            Block next = block.getRelative(direction);
            Crop supported = FarmProduce.crop(next.getType());
            if (supported != null && (supported.growthDirection == direction
                    || (direction == BlockFace.UP && supported.growthDirection == null && supported != Crop.COCOA))) {
                credit(next, supported);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void filterPhysicsDrops(BlockDestroyEvent event) {
        Block block = event.getBlock();
        Crop crop = FarmProduce.crop(block.getType());
        if (crop != null && !hasHandCredit(block, crop) && !stations.allowsAutomation(block.getChunk())) {
            event.setWillDrop(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void carryHandHarvest(BlockDestroyEvent event) {
        Block block = event.getBlock();
        Crop crop = FarmProduce.crop(block.getType());
        if (crop != null && hasHandCredit(block, crop)) {
            handHarvests.remove(Position.at(block));
            if (event.willDrop()) {
                creditNextSegment(block, crop);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void filterMachineDrops(BlockBreakBlockEvent event) {
        Block block = event.getBlock();
        if (FarmProduce.crop(block.getType()) != null && !stations.allowsAutomation(block.getChunk())) {
            handHarvests.remove(Position.at(block));
            event.getDrops().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void preventEntityPicking(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        Crop crop = FarmProduce.crop(block.getType());
        if (!(event.getEntity() instanceof Player) && crop != null && FarmProduce.crop(event.getTo()) == crop
                && !stations.allowsAutomation(block.getChunk())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void explode(BlockExplodeEvent event) {
        if (event.getYield() > 0) {
            destroyWithoutDrops(event, event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void explode(EntityExplodeEvent event) {
        var source = event.getEntity();
        if (event.getYield() > 0 && !(source instanceof Player)
                && !(source instanceof TNTPrimed tnt && tnt.getSource() instanceof Player)
                && !(source instanceof Projectile projectile && projectile.getShooter() instanceof Player)) {
            destroyWithoutDrops(event, event.blockList());
        }
    }

    private void destroyWithoutDrops(Cancellable event, List<Block> blocks) {
        List<BlockState> plants = new ArrayList<>();
        blocks.removeIf(block -> {
            if (FarmProduce.crop(block.getType()) == null || stations.allowsAutomation(block.getChunk())) {
                return false;
            }
            plants.add(block.getState());
            return true;
        });
        if (plants.isEmpty()) {
            return;
        }
        // Let protection listeners finish before removing plants excluded from vanilla explosion loot.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!event.isCancelled()) {
                for (BlockState plant : plants) {
                    Block block = plant.getBlock();
                    if (block.getBlockData().equals(plant.getBlockData())) {
                        handHarvests.remove(Position.at(block));
                        block.setType(Material.AIR);
                    }
                }
            }
        });
    }

    private boolean hasHandCredit(Block block, Crop crop) {
        HandHarvest credit = handHarvests.get(Position.at(block));
        return credit != null && credit.crop() == crop;
    }

    private void creditNextSegment(Block block, Crop crop) {
        if (crop.growthDirection != null) {
            Block next = block.getRelative(crop.growthDirection);
            if (FarmProduce.crop(next.getType()) == crop) {
                credit(next, crop);
            }
        }
    }

    private void credit(Block block, Crop crop) {
        Position position = Position.at(block);
        HandHarvest harvest = new HandHarvest(crop, Bukkit.getCurrentTick());
        handHarvests.put(position, harvest);
        // Some stalks schedule each unsupported segment for the next tick.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> handHarvests.remove(position, harvest), 3);
    }
}
