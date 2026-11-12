package dev.civmaker.map.protocol;

import java.util.List;

/** Public map information only. Never include vaults, balances, or undiscovered settlements. */
public record MapSnapshot(String world, String dimension, String raidStatus,
                          List<Village> villages, List<Claim> claims, List<Marker> markers, List<Farm> farms,
                          RadarAccess radar) {
    // Older servers omit the additive farms field; keep their snapshots readable.
    public MapSnapshot {
        farms = farms == null ? List.of() : List.copyOf(farms);
    }
    public MapSnapshot(String world, String dimension, String raidStatus,
                       List<Village> villages, List<Claim> claims, List<Marker> markers) {
        this(world, dimension, raidStatus, villages, claims, markers, List.of(), null);
    }
    public MapSnapshot(String world, String dimension, String raidStatus,
                       List<Village> villages, List<Claim> claims, List<Marker> markers, List<Farm> farms) {
        this(world, dimension, raidStatus, villages, claims, markers, farms, null);
    }
    public record Village(String id, String name, String owner, String ownerName, int color, long points) {}
    public record Claim(int x, int z, String village, String kind) {}
    public record Marker(String id, String village, String name, String symbol, int x, int y, int z) {}
    public record Farm(String name, String owner, String ownerName, String faction, int color, int x, int y, int z,
                       String factionId) {
        public Farm(String name, String owner, String ownerName, String faction, int color, int x, int y, int z) {
            this(name, owner, ownerName, faction, color, x, y, z, null);
        }
    }
    public record RadarAccess(boolean allowed, List<String> friendlyPlayers, String factionId, String viewerId) {
        public RadarAccess(boolean allowed, List<String> friendlyPlayers) {
            this(allowed, friendlyPlayers, null, null);
        }
        public RadarAccess {
            friendlyPlayers = friendlyPlayers == null ? List.of() : List.copyOf(friendlyPlayers);
        }
    }
}
