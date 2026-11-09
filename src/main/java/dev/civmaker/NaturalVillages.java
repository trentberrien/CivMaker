package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Settlement;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class NaturalVillages implements Listener {
    private static final int BELL_RADIUS = 16;
    private static final int BELL_DEPTH = 3;

    private record Site(UUID world, String structure, List<BoundingBox> pieces) {
        boolean covers(Plot plot) {
            return pieces.stream().anyMatch(piece -> plot.x() >= chunk(piece.getMinX())
                    && plot.x() <= chunk(piece.getMaxX()) && plot.z() >= chunk(piece.getMinZ())
                    && plot.z() <= chunk(piece.getMaxZ()));
        }

        Settlement settlement(Block bell) {
            Set<Plot> footprint = new LinkedHashSet<>();
            for (BoundingBox piece : pieces) {
                for (int x = chunk(piece.getMinX()); x <= chunk(piece.getMaxX()); x++) {
                    for (int z = chunk(piece.getMinZ()); z <= chunk(piece.getMaxZ()); z++) {
                        footprint.add(new Plot(world, x, z));
                    }
                }
            }
            return new Settlement(structure, Position.at(bell), footprint);
        }
    }

    private final CivStore store;
    private final NamespacedKey placedBells;
    private final Map<Plot, List<Site>> sites = new HashMap<>();
    private final Map<Plot, Map<Site, Settlement>> centers = new HashMap<>();
    private final Map<Plot, World> pending = new LinkedHashMap<>();

    NaturalVillages(CivMaker plugin, CivStore store) {
        this.store = store;
        placedBells = new NamespacedKey(plugin, "placed_bells_v1");
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                queue(chunk);
            }
        }
    }

    boolean reserved(World world, Plot plot) {
        return store.settlementAt(plot) == null && !sitesAt(world, plot).isEmpty();
    }

    Settlement findSettlement(Block bell) {
        if (bell.getType() != Material.BELL || placedBells(bell).get(bellIndex(bell))) {
            return null;
        }
        for (Site site : sitesAt(bell.getWorld(), Plot.at(bell))) {
            if (site.pieces().stream().anyMatch(piece -> containsBell(piece, bell))) {
                return site.settlement(bell);
            }
        }
        return null;
    }

    Settlement settlementAt(World world, Plot plot) {
        for (Site site : sitesAt(world, plot)) {
            BoundingBox square = site.pieces().getFirst();
            for (int x = chunk(square.getMinX()); x <= chunk(square.getMaxX()); x++) {
                for (int z = chunk(square.getMinZ()); z <= chunk(square.getMaxZ()); z++) {
                    Plot squarePlot = new Plot(plot.world(), x, z);
                    Settlement known = centers.getOrDefault(squarePlot, Map.of()).get(site);
                    if (known != null) {
                        return known;
                    }
                    Block bell = findBell(world, squarePlot, square);
                    if (bell != null) {
                        return site.settlement(bell);
                    }
                }
            }
        }
        return null;
    }

    Block meadowBellAt(Chunk start) {
        World world = start.getWorld();
        Plot plot = new Plot(world.getUID(), start.getX(), start.getZ());
        for (Site site : sitesAt(world, plot)) {
            if (!site.structure().equals("minecraft:village_plains")) {
                continue;
            }
            BoundingBox square = site.pieces().getFirst();
            for (int x = chunk(square.getMinX()); x <= chunk(square.getMaxX()); x++) {
                for (int z = chunk(square.getMinZ()); z <= chunk(square.getMaxZ()); z++) {
                    Block bell = findBell(world, new Plot(plot.world(), x, z), square);
                    if (bell != null && bell.getBiome() == Biome.MEADOW
                            && world.getWorldBorder().isInside(bell.getLocation().add(0.5, 0, 0.5))) {
                        return bell;
                    }
                }
            }
        }
        return null;
    }

    List<Settlement> centers() {
        Map<Site, Settlement> unique = new HashMap<>();
        for (var chunk : centers.values()) {
            chunk.forEach((site, settlement) -> unique.merge(site, settlement,
                    (first, second) -> precedes(first.bell(), second.bell()) ? first : second));
        }
        return List.copyOf(unique.values());
    }

    boolean inBellProtection(Block block) {
        Plot current = Plot.at(block);
        World world = block.getWorld();
        for (int x = current.x() - 1; x <= current.x() + 1; x++) {
            for (int z = current.z() - 1; z <= current.z() + 1; z++) {
                Plot plot = new Plot(current.world(), x, z);
                Settlement owned = store.settlementAt(plot);
                if (owned != null && owned.bell().plot().equals(plot) && inBellColumn(block, owned.bell())) {
                    return true;
                }
                if (pending.remove(plot) != null) {
                    discoverCenters(world, plot);
                }
                if (store.naturalBellsAt(plot).stream().anyMatch(bell -> inBellColumn(block, bell))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inBellColumn(Block block, Position bell) {
        int dx = block.getX() - bell.x();
        int dz = block.getZ() - bell.z();
        return block.getY() >= bell.y() - BELL_DEPTH && dx * dx + dz * dz <= BELL_RADIUS * BELL_RADIUS;
    }

    void discoverCenters() {
        var iterator = pending.entrySet().iterator();
        for (int remaining = 64; remaining > 0 && iterator.hasNext(); remaining--) {
            var entry = iterator.next();
            Plot plot = entry.getKey();
            World world = entry.getValue();
            iterator.remove();
            discoverCenters(world, plot);
        }
    }

    private void discoverCenters(World world, Plot plot) {
        if (!world.isChunkLoaded(plot.x(), plot.z())) {
            return;
        }
        Map<Site, Settlement> found = new HashMap<>();
        Set<Position> bells = new LinkedHashSet<>();
        for (Site site : sitesAt(world, plot)) {
            // The first piece is the generated meeting point (the well or village square).
            Block bell = findBell(world, plot, site.pieces().getFirst());
            if (bell != null) {
                Settlement settlement = site.settlement(bell);
                found.put(site, settlement);
                bells.add(settlement.bell());
            }
        }
        if (found.isEmpty()) {
            centers.remove(plot);
        } else {
            centers.put(plot, found);
        }
        store.updateNaturalBells(plot, bells);
    }

    private List<Site> sitesAt(World world, Plot plot) {
        if (!world.isChunkLoaded(plot.x(), plot.z())) {
            return List.of();
        }
        return sites.computeIfAbsent(plot, ignored -> {
            List<Site> result = new ArrayList<>();
            var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
            for (var structure : world.getStructures(plot.x(), plot.z())) {
                NamespacedKey key = registry.getKey(structure.getStructure());
                if (key == null || !key.getNamespace().equals("minecraft") || !key.getKey().startsWith("village_")) {
                    continue;
                }
                Site site = new Site(world.getUID(), key.toString(),
                        structure.getPieces().stream().map(StructurePiece::getBoundingBox).toList());
                if (site.covers(plot)) {
                    result.add(site);
                }
            }
            return List.copyOf(result);
        });
    }

    private Block findBell(World world, Plot plot, BoundingBox piece) {
        int minX = Math.max(plot.x() << 4, (int) piece.getMinX());
        int maxX = Math.min((plot.x() << 4) + 15, (int) piece.getMaxX());
        int minZ = Math.max(plot.z() << 4, (int) piece.getMinZ());
        int maxZ = Math.min((plot.z() << 4) + 15, (int) piece.getMaxZ());
        int minY = Math.max(world.getMinHeight(), (int) piece.getMinY());
        int maxY = Math.min(world.getMaxHeight() - 1, (int) piece.getMaxY());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.BELL && !placedBells(block).get(bellIndex(block))) {
                        return block;
                    }
                }
            }
        }
        return null;
    }

    private static boolean precedes(Position first, Position second) {
        if (first.x() != second.x()) {
            return first.x() < second.x();
        }
        return first.z() != second.z() ? first.z() < second.z() : first.y() < second.y();
    }

    private static int chunk(double coordinate) {
        return (int) Math.floor(coordinate) >> 4;
    }

    private static boolean containsBell(BoundingBox piece, Block bell) {
        // Paper exposes inclusive block coordinates for structure pieces.
        return bell.getX() >= piece.getMinX() && bell.getX() <= piece.getMaxX()
                && bell.getY() >= piece.getMinY() && bell.getY() <= piece.getMaxY()
                && bell.getZ() >= piece.getMinZ() && bell.getZ() <= piece.getMaxZ();
    }

    @EventHandler
    void load(ChunkLoadEvent event) {
        queue(event.getChunk());
    }

    private void queue(Chunk chunk) {
        pending.put(new Plot(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()), chunk.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void unload(ChunkUnloadEvent event) {
        Plot plot = new Plot(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
        pending.remove(plot);
        sites.remove(plot);
        centers.remove(plot);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void unloadWorld(WorldUnloadEvent event) {
        var world = event.getWorld().getUID();
        pending.keySet().removeIf(plot -> plot.world().equals(world));
        sites.keySet().removeIf(plot -> plot.world().equals(world));
        centers.keySet().removeIf(plot -> plot.world().equals(world));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void recordPlacedBell(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (event.canBuild() && block.getType() == Material.BELL) {
            BitSet bells = placedBells(block);
            bells.set(bellIndex(block));
            block.getChunk().getPersistentDataContainer().set(placedBells, PersistentDataType.BYTE_ARRAY, bells.toByteArray());
        }
    }

    private BitSet placedBells(Block block) {
        byte[] saved = block.getChunk().getPersistentDataContainer().get(placedBells, PersistentDataType.BYTE_ARRAY);
        return saved == null ? new BitSet() : BitSet.valueOf(saved);
    }

    private static int bellIndex(Block block) {
        return ((block.getY() - block.getWorld().getMinHeight()) << 8) | ((block.getZ() & 15) << 4) | (block.getX() & 15);
    }
}
