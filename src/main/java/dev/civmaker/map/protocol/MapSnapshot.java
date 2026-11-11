package dev.civmaker.map.protocol;

import java.util.List;

/** Public map information only. Never include vaults, balances, or undiscovered settlements. */
public record MapSnapshot(String world, String dimension, String raidStatus,
                          List<Village> villages, List<Claim> claims, List<Marker> markers, List<Farm> farms) {
    // Older servers omit the additive farms field; keep their snapshots readable.
    public MapSnapshot {
        farms = farms == null ? List.of() : List.copyOf(farms);
    }
    public MapSnapshot(String world, String dimension, String raidStatus,
                       List<Village> villages, List<Claim> claims, List<Marker> markers) {
        this(world, dimension, raidStatus, villages, claims, markers, List.of());
    }
    public record Village(String id, String name, String owner, String ownerName, int color, long points) {}
    public record Claim(int x, int z, String village, String kind) {}
    public record Marker(String id, String village, String name, String symbol, int x, int y, int z) {}
    public record Farm(String name, String owner, String ownerName, String faction, int color, int x, int y, int z) {}
}
