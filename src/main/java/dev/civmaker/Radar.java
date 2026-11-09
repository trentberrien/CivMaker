package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import dev.civmaker.RaidEquipment.Tool;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class Radar {
    private static final int RADIUS = 5;
    // Minecraft yaw starts at south and increases toward west.
    private static final List<Heading> HEADINGS = List.of(
            new Heading("S", "v"), new Heading("SW", "↙"), new Heading("W", "<"), new Heading("NW", "↖"),
            new Heading("N", "^"), new Heading("NE", "↗"), new Heading("E", ">"), new Heading("SE", "↘"));
    private static final Component ROW_EDGE = Component.text("|", NamedTextColor.DARK_GRAY);

    private record Heading(String label, String symbol) {
        Component marker() {
            Component arrow = Component.text(symbol, NamedTextColor.AQUA);
            // In the uniform font, < and > are one pixel narrower. Bold zero-width space adds one invisible pixel.
            if (symbol.equals("<") || symbol.equals(">")) {
                arrow = arrow.append(Component.text("\u200c").decorate(TextDecoration.BOLD));
            }
            return arrow;
        }
    }

    private final CivMaker plugin;
    private final CivStore store;
    private final RaidEquipment equipment;
    private final PillagerGuards guards;

    Radar(CivMaker plugin, CivStore store, RaidEquipment equipment, PillagerGuards guards) {
        this.plugin = plugin;
        this.store = store;
        this.equipment = equipment;
        this.guards = guards;
    }

    void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            var inventory = player.getInventory();
            if (!player.isDead() && (equipment.identify(inventory.getItemInMainHand()) == Tool.RADAR
                    || equipment.identify(inventory.getItemInOffHand()) == Tool.RADAR)) {
                player.sendMessage(scan(player));
            }
        }
    }

    private Component scan(Player viewer) {
        World world = viewer.getWorld();
        var location = viewer.getLocation();
        Plot center = Plot.at(location);
        Village ownVillage = store.memberOf(viewer.getUniqueId());
        Set<Plot> enemies = new HashSet<>();
        for (Player target : world.getPlayers()) {
            if (target.equals(viewer) || target.isDead() || !viewer.canSee(target)
                    || (target.getGameMode() != GameMode.SURVIVAL && target.getGameMode() != GameMode.ADVENTURE)) {
                continue;
            }
            Plot plot = Plot.at(target.getLocation());
            if (!inRange(center, plot)) {
                continue;
            }
            Village targetVillage = store.memberOf(target.getUniqueId());
            if (targetVillage == null || (targetVillage != ownVillage && targetVillage != store.claimAt(plot))) {
                enemies.add(plot);
            }
        }
        Set<Plot> guardPosts = guards.enemyPosts(world, ownVillage).map(Plot::at)
                .filter(plot -> inRange(center, plot)).collect(Collectors.toSet());
        int heading = Math.floorMod(Math.round(location.getYaw() / 45), HEADINGS.size());
        return draw(center, HEADINGS.get(heading), enemies, guardPosts);
    }

    private static boolean inRange(Plot center, Plot plot) {
        return Math.abs(plot.x() - center.x()) <= RADIUS && Math.abs(plot.z() - center.z()) <= RADIUS;
    }

    private Component draw(Plot center, Heading heading, Set<Plot> enemies, Set<Plot> guardPosts) {
        var grid = Component.text().font(Key.key("minecraft", "uniform"));
        Component viewerMarker = heading.marker();
        // North is negative Z, regardless of where the viewer is facing.
        for (int z = center.z() - RADIUS; z <= center.z() + RADIUS; z++) {
            grid.appendNewline().append(ROW_EDGE);
            for (int x = center.x() - RADIUS; x <= center.x() + RADIUS; x++) {
                Plot plot = new Plot(center.world(), x, z);
                var settlement = store.settlementAt(plot);
                Position bell = settlement != null && settlement.bell().plot().equals(plot) ? settlement.bell() : null;
                grid.append(cell(plot, store.claimAt(plot), plot.equals(center) ? viewerMarker : null,
                        enemies.contains(plot), guardPosts.contains(plot), bell));
            }
            grid.append(ROW_EDGE);
        }
        return Component.text("N ^  Facing " + heading.label(), NamedTextColor.WHITE).append(grid.build());
    }

    private static Component cell(Plot plot, Village village, Component viewerMarker, boolean enemy, boolean guard,
                                  Position bell) {
        // Each marker and the padding space advance four pixels in the vanilla uniform font.
        Component tile;
        if (viewerMarker != null) {
            tile = viewerMarker;
        } else if (guard) {
            tile = Component.text("G", NamedTextColor.GOLD);
        } else if (village != null) {
            tile = Component.text("#", TextColor.color(village.color));
        } else {
            tile = Component.text("=", NamedTextColor.DARK_GRAY);
        }
        String land = village == null ? "Wilderness" : village.name;
        int x = plot.x() * 16;
        int z = plot.z() * 16;
        String details = land + "\nX " + x + " to " + (x + 15) + "   Z " + z + " to " + (z + 15);
        if (bell != null) {
            details += "\nSettlement bell at " + bell.x() + ", " + bell.y() + ", " + bell.z();
        }
        return Component.empty().append(tile)
                .append(Component.text(bell != null ? "B" : " ", NamedTextColor.YELLOW))
                .append(Component.text(enemy ? "X" : " ", NamedTextColor.RED))
                .append(Component.text(viewerMarker != null && guard ? "G" : " ", NamedTextColor.GOLD))
                .hoverEvent(Component.text(details));
    }
}
