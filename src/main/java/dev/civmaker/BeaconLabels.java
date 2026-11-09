package dev.civmaker;

import dev.civmaker.CivStore.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BeaconLabels {
    private static final List<BlockFace> SIDES = List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
    private final CivMaker plugin;
    private final CivStore store;
    private final Map<Position, TextDisplay> labels = new HashMap<>();

    BeaconLabels(CivMaker plugin, CivStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void refresh() {
        Set<Position> visible = new HashSet<>();
        for (var village : store.villages()) {
            TextColor color = TextColor.color(village.color);
            refreshLabel(visible, village.beacon, Material.BEACON, Component.text(village.name + "'s beacon", color));
            for (var settlement : village.settlements) {
                Position bell = settlement.bell();
                World world = plugin.getServer().getWorld(bell.world());
                var plot = bell.plot();
                if (world != null && world.isChunkLoaded(plot.x(), plot.z())) {
                    var bellBlock = world.getBlockAt(bell.x(), bell.y(), bell.z());
                    refreshLabel(visible, bell, Material.BELL,
                            Component.text(settlement.displayName(bellBlock), color)
                                    .append(Component.newline())
                                    .append(Component.text(village.name, color)));
                }
                var vault = settlement.vault();
                if (vault.position != null && vault.reward != null) {
                    refreshLabel(visible, vault.position, Material.CHEST,
                            Component.text("Settlement Vault • " + vault.reward.label, color));
                }
            }
        }
        labels.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
    }

    private void refreshLabel(Set<Position> visible, Position position, Material type, Component text) {
        World world = plugin.getServer().getWorld(position.world());
        var plot = position.plot();
        if (world == null || !world.isChunkLoaded(plot.x(), plot.z())
                || world.getBlockAt(position.x(), position.y(), position.z()).getType() != type) {
            return;
        }
        Location location = type == Material.BELL ? belowBell(world, position)
                : new Location(world, position.x() + 0.5, position.y() + 1.25, position.z() + 0.5);
        if (location == null) {
            return;
        }
        visible.add(position);
        TextDisplay label = labels.get(position);
        if (label == null || !label.isValid()) {
            label = world.spawn(location, TextDisplay.class, display -> {
                display.setPersistent(false);
                display.setBillboard(Display.Billboard.CENTER);
                display.setShadowed(true);
                display.text(text);
            });
            labels.put(position, label);
        } else {
            if (!location.equals(label.getLocation())) {
                label.teleport(location);
            }
            if (!text.equals(label.text())) {
                label.text(text);
            }
        }
    }

    private static Location belowBell(World world, Position position) {
        var below = world.getBlockAt(position.x(), position.y() - 1, position.z());
        if (below.getType().isAir()) {
            return new Location(world, position.x() + 0.5, position.y() - 0.6, position.z() + 0.5);
        }
        // A floor-mounted bell sits directly above a solid block. Put the text beside
        // the foot of its stand, below the bell itself, so the floor cannot hide it.
        for (BlockFace side : SIDES) {
            int x = position.x() + side.getModX();
            int z = position.z() + side.getModZ();
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            var block = world.getBlockAt(x, position.y(), z);
            if (block.getType().isAir()) {
                return new Location(world, x + 0.5, position.y() + 0.05, z + 0.5);
            }
        }
        return null;
    }

    void clear() {
        labels.values().forEach(TextDisplay::remove);
        labels.clear();
    }
}
