package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class VaultTracking implements Listener {
    private final CivMaker plugin;
    private final CivStore store;
    private final RaidEquipment equipment;
    private final Notes notes;
    private final Map<Position, TextDisplay> labels = new HashMap<>();

    VaultTracking(CivMaker plugin, CivStore store, RaidEquipment equipment, Notes notes) {
        this.plugin = plugin;
        this.store = store;
        this.equipment = equipment;
        this.notes = notes;
    }

    void refresh() {
        Set<Position> visible = new HashSet<>();
        scanLoaded(visible);
        labels.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
    }

    void refreshBalances() {
        scanLoaded(null);
    }

    private void scanLoaded(Set<Position> visible) {
        for (var world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scan(chunk, visible);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void unload(ChunkUnloadEvent event) {
        scan(event.getChunk(), null);
    }

    private void scan(Chunk chunk, Set<Position> visible) {
        var world = chunk.getWorld();
        Plot plot = new Plot(world.getUID(), chunk.getX(), chunk.getZ());
        Village village = store.claimAt(plot);
        if (village == null) {
            return;
        }

        if (village.treasuries.removeIf(position -> position.plot().equals(plot)
                && migrateTreasury(world.getBlockAt(position.x(), position.y(), position.z())))) {
            store.dirty = true;
        }

        long balance = 0;
        for (var state : chunk.getTileEntities(false)) {
            if (!(state instanceof Container container)) {
                continue;
            }
            Block block = container.getBlock();
            Chest chest = container instanceof Chest chestState ? chestState : null;
            List<Block> parts = chest == null ? List.of(block) : Protection.inventoryBlocks(chest.getInventory());
            Block marker = parts.stream()
                    .filter(part -> store.claimAt(part) == village && equipment.isVault(part))
                    .min(Comparator.comparingInt(Block::getX).thenComparingInt(Block::getZ))
                    .orElse(null);
            if (marker == null) {
                continue;
            }

            // A double chest appears as two tile entities. Count each half's slots once.
            long contents = notes.value(chest == null ? container.getInventory() : chest.getBlockInventory(),
                    Integer.MAX_VALUE);
            balance += Math.min(contents, Long.MAX_VALUE - balance);
            if (visible != null && visible.add(Position.at(marker))) {
                showLabel(marker);
            }
        }
        store.updateVaultBalance(plot, balance);
    }

    private boolean migrateTreasury(Block block) {
        if (block.getType() != Material.CHEST) {
            return true;
        }
        var storage = equipment.storageAt(block);
        return storage != null && (equipment.isVault(block) || equipment.reinforce(storage));
    }

    private void showLabel(Block block) {
        Position position = Position.at(block);
        TextDisplay current = labels.get(position);
        if (current == null || !current.isValid()) {
            var world = block.getWorld();
            Location above = new Location(world, position.x() + 0.5, position.y() + 1.05, position.z() + 0.5);
            labels.put(position, world.spawn(above, TextDisplay.class, display -> {
                display.setPersistent(false);
                display.setBillboard(Display.Billboard.CENTER);
                display.setShadowed(true);
                display.text(Component.text("Secure Vault", NamedTextColor.GREEN));
            }));
        }
    }

    void clear() {
        labels.values().forEach(TextDisplay::remove);
        labels.clear();
    }
}
