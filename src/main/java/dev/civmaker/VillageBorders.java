package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

final class VillageBorders {
    private static final int CHUNK_SIZE = 16;
    private static final int RANGE = 2 * CHUNK_SIZE;
    private static final Particle.DustOptions BLUE = new Particle.DustOptions(Color.BLUE, 1.4f);

    private final CivMaker plugin;
    private final CivStore store;
    private final NamespacedKey preference;

    VillageBorders(CivMaker plugin, CivStore store) {
        this.plugin = plugin;
        this.store = store;
        preference = new NamespacedKey(plugin, "show_village_borders");
    }

    void toggle(Player player) {
        var data = player.getPersistentDataContainer();
        if (data.has(preference, PersistentDataType.BYTE)) {
            data.remove(preference);
            player.sendMessage("Village borders off.");
        } else {
            data.set(preference, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage("Village borders on. Nearby edges show in blue while you're inside a village.");
            show(player);
        }
    }

    void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getPersistentDataContainer().has(preference, PersistentDataType.BYTE)) {
                show(player);
            }
        }
    }

    private void show(Player player) {
        Location location = player.getLocation();
        Plot current = Plot.at(location);
        var village = store.claimAt(current);
        if (village == null) {
            return;
        }

        // Include a chunk whose far edge sits exactly at the lower limit.
        int minX = (location.getBlockX() - RANGE - 1) >> 4;
        int maxX = (location.getBlockX() + RANGE) >> 4;
        int minZ = (location.getBlockZ() - RANGE - 1) >> 4;
        int maxZ = (location.getBlockZ() + RANGE) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Plot plot = new Plot(current.world(), x, z);
                if (!village.plots.contains(plot)) {
                    continue;
                }
                for (Plot neighbor : plot.neighbors()) {
                    if (!village.plots.contains(neighbor)) {
                        drawEdge(player, location, plot, neighbor);
                    }
                }
            }
        }
    }

    private static void drawEdge(Player player, Location location, Plot plot, Plot neighbor) {
        int stepX = Math.abs(neighbor.z() - plot.z());
        int stepZ = Math.abs(neighbor.x() - plot.x());
        int startX = Math.max(plot.x(), neighbor.x()) * CHUNK_SIZE;
        int startZ = Math.max(plot.z(), neighbor.z()) * CHUNK_SIZE;
        double y = location.getY() + 0.75;
        for (int offset = 0; offset <= CHUNK_SIZE; offset += 2) {
            int x = startX + offset * stepX;
            int z = startZ + offset * stepZ;
            double dx = x - location.getX();
            double dz = z - location.getZ();
            if (dx * dx + dz * dz <= RANGE * RANGE) {
                player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, BLUE, true);
            }
        }
    }
}
