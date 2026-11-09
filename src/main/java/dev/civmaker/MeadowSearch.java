package dev.civmaker;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.Structure;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.logging.Level;

final class MeadowSearch {
    // Vanilla's village structure set uses 34-chunk placement cells.
    private static final int CELL_WIDTH = 34 * 16;
    private static final int SEARCH_RANGE = 12_700;
    private static final int BELL_MARGIN = 64;

    private record Cell(int x, int z) {
        double distanceSquared(Location origin) {
            double dx = x + CELL_WIDTH / 2.0 - origin.getX();
            double dz = z + CELL_WIDTH / 2.0 - origin.getZ();
            return dx * dx + dz * dz;
        }
    }

    private final CivMaker plugin;
    private final NaturalVillages natural;
    private boolean running;

    MeadowSearch(CivMaker plugin, NaturalVillages natural) {
        this.plugin = plugin;
        this.natural = natural;
    }

    void find(Player player) {
        Location origin = player.getLocation();
        World world = origin.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) {
            player.sendMessage("Run /civ locatemeadow in the Overworld.");
            return;
        }
        if (running) {
            player.sendMessage("A Meadow village search is already running.");
            return;
        }

        var border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() / 2;
        var cells = new ArrayList<Cell>();
        int minX = firstCell(Math.max(origin.getX() - SEARCH_RANGE, center.getX() - half - BELL_MARGIN));
        int maxX = firstCell(Math.min(origin.getX() + SEARCH_RANGE, center.getX() + half + BELL_MARGIN));
        int minZ = firstCell(Math.max(origin.getZ() - SEARCH_RANGE, center.getZ() - half - BELL_MARGIN));
        int maxZ = firstCell(Math.min(origin.getZ() + SEARCH_RANGE, center.getZ() + half + BELL_MARGIN));
        for (int x = minX; x <= maxX; x += CELL_WIDTH) {
            for (int z = minZ; z <= maxZ; z += CELL_WIDTH) {
                cells.add(new Cell(x, z));
            }
        }
        cells.sort(Comparator.comparingDouble(cell -> cell.distanceSquared(origin)));
        var remaining = cells.iterator();
        running = true;
        player.sendMessage("Searching for a village with a Meadow bell inside the world border...");
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || plugin.getServer().getWorld(world.getUID()) == null) {
                    finish();
                    return;
                }
                if (!remaining.hasNext()) {
                    player.sendMessage("No Meadow village found in the searched area (up to 12,700 blocks away).");
                    finish();
                    return;
                }
                try {
                    Cell cell = remaining.next();
                    // Radius zero checks just the placement cell containing this point.
                    var found = world.locateNearestStructure(new Location(world, cell.x(), origin.getY(), cell.z()),
                            Structure.VILLAGE_PLAINS, 0, false);
                    if (found == null) {
                        return;
                    }
                    var bell = natural.meadowBellAt(found.getLocation().getChunk());
                    if (bell != null) {
                        player.sendMessage("Meadow village bell: " + bell.getX() + " " + bell.getY() + " " + bell.getZ());
                        player.sendMessage("Teleport with /tp @s " + (bell.getX() + 0.5) + " " + (bell.getY() + 2)
                                + " " + (bell.getZ() + 0.5));
                        finish();
                    }
                } catch (RuntimeException error) {
                    plugin.getLogger().log(Level.WARNING, "Meadow village search failed", error);
                    player.sendMessage("The Meadow village search failed. See the server log.");
                    finish();
                }
            }

            private void finish() {
                cancel();
                running = false;
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    private static int firstCell(double block) {
        return (int) Math.floor(block / CELL_WIDTH) * CELL_WIDTH;
    }
}
