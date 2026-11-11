package dev.civmaker.companion;

import dev.civmaker.map.protocol.MapSnapshot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable views can safely be read by Xaero's map worker threads. */
public final class MapState {
    public static final int FARM_BORDER_COLOR = 0xFFD166;
    public static final int FARM_FILL_COLOR = 0x66BB66;
    public static final MapState INSTANCE = new MapState();
    public record OwnedClaim(MapSnapshot.Claim claim, MapSnapshot.Village village) {}
    public record View(MapSnapshot snapshot, Map<Long, OwnedClaim> claims, Map<Long, MapSnapshot.Farm> farms,
                       Set<Long> regions, int revision) {}
    private volatile View view = new View(null, Map.of(), Map.of(), Set.of(), 0);
    private volatile boolean enabled = true;
    private volatile boolean farmsEnabled = true;
    private volatile boolean radarServer;

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public View view() { return view; }
    public boolean enabled() { return enabled; }
    public boolean farmsEnabled() { return farmsEnabled; }

    public void setRadarServer(boolean required) {
        if (radarServer != required) {
            radarServer = required;
            View old = view;
            view = new View(old.snapshot(), old.claims(), old.farms(), old.regions(), old.revision() + 1);
        }
    }

    public boolean claimsAvailable() { return claimsAvailable(view); }

    private boolean claimsAvailable(View current) {
        return current.snapshot() != null && (current.snapshot().radar() == null
                ? !radarServer : current.snapshot().radar().allowed());
    }

    /** This policy is independent of the optional overlay display toggle. */
    public boolean hidePlayer(UUID player) {
        View current = view;
        var access = current.snapshot() == null ? null : current.snapshot().radar();
        if (access == null) { return radarServer; }
        return !access.allowed() && !access.friendlyPlayers().contains(player.toString());
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        View old = view;
        view = new View(old.snapshot(), old.claims(), old.farms(), old.regions(), old.revision() + 1);
    }

    public void setFarmsEnabled(boolean enabled) {
        farmsEnabled = enabled;
        View old = view;
        view = new View(old.snapshot(), old.claims(), old.farms(), old.regions(), old.revision() + 1);
    }

    public void clear() {
        view = new View(null, Map.of(), Map.of(), Set.of(), view.revision() + 1);
    }

    public void replace(MapSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        UUID.fromString(snapshot.world());
        text(snapshot.dimension());
        text(snapshot.raidStatus());
        if (snapshot.radar() != null) {
            if (snapshot.radar().friendlyPlayers().size() > 10_000) {
                throw new IllegalArgumentException("Too many friendly players");
            }
            snapshot.radar().friendlyPlayers().forEach(UUID::fromString);
        }
        if (snapshot.villages().size() > 10_000 || snapshot.claims().size() > 100_000
                || snapshot.markers().size() > 10_000 || snapshot.farms().size() > 10_000) {
            throw new IllegalArgumentException("Too many map entries");
        }
        Map<String, MapSnapshot.Village> villages = new HashMap<>();
        for (var village : snapshot.villages()) {
            text(village.id()); text(village.name()); text(village.owner()); text(village.ownerName());
            if (villages.put(village.id(), village) != null) {
                throw new IllegalArgumentException("Duplicate village");
            }
        }
        Map<Long, OwnedClaim> claims = new HashMap<>();
        Set<Long> regions = new HashSet<>();
        for (var claim : snapshot.claims()) {
            if (Math.abs((long) claim.x()) > 1_875_000 || Math.abs((long) claim.z()) > 1_875_000) {
                throw new IllegalArgumentException("Invalid chunk coordinates");
            }
            text(claim.kind());
            var village = Objects.requireNonNull(villages.get(claim.village()), "Unknown village");
            if (claims.put(key(claim.x(), claim.z()), new OwnedClaim(claim, village)) != null) {
                throw new IllegalArgumentException("Duplicate plot");
            }
            regions.add(key(claim.x() >> 5, claim.z() >> 5));
        }
        for (var marker : snapshot.markers()) {
            text(marker.id()); text(marker.name()); text(marker.symbol());
            Objects.requireNonNull(villages.get(marker.village()), "Unknown marker village");
            if (Math.abs((long) marker.x()) > 30_000_000 || Math.abs((long) marker.z()) > 30_000_000
                    || Math.abs((long) marker.y()) > 4096) {
                throw new IllegalArgumentException("Invalid marker coordinates");
            }
        }
        Map<Long, MapSnapshot.Farm> farms = new HashMap<>();
        for (var farm : snapshot.farms()) {
            text(farm.name()); text(farm.owner()); text(farm.ownerName()); text(farm.faction());
            if (Math.abs((long) farm.x()) > 30_000_000 || Math.abs((long) farm.z()) > 30_000_000
                    || Math.abs((long) farm.y()) > 4096) {
                throw new IllegalArgumentException("Invalid farm coordinates");
            }
            int x = farm.x() >> 4;
            int z = farm.z() >> 4;
            if (farms.put(key(x, z), farm) != null) {
                throw new IllegalArgumentException("Duplicate Farm Station chunk");
            }
            regions.add(key(x >> 5, z >> 5));
        }
        if (!snapshot.equals(view.snapshot())) {
            view = new View(snapshot, Map.copyOf(claims), Map.copyOf(farms), Set.copyOf(regions), view.revision() + 1);
        }
    }

    private static void text(String value) {
        if (value == null || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid map label");
        }
    }

    public OwnedClaim claim(String dimension, int x, int z) {
        View current = view;
        return enabled && claimsAvailable(current) && current.snapshot().dimension().equals(dimension)
                ? current.claims().get(key(x, z)) : null;
    }

    public boolean region(String dimension, int x, int z) {
        View current = view;
        return enabled && current.snapshot() != null && current.snapshot().dimension().equals(dimension)
                && current.regions().contains(key(x, z));
    }

    public MapSnapshot.Farm farm(String dimension, int x, int z) {
        View current = view;
        return enabled && farmsEnabled && current.snapshot() != null && current.snapshot().dimension().equals(dimension)
                ? current.farms().get(key(x, z)) : null;
    }

    public boolean highlighted(String dimension, int x, int z) {
        return claim(dimension, x, z) != null || farm(dimension, x, z) != null;
    }

    /** Xaero's texture highlighters use BBGGRRAA, rather than ARGB. */
    public static int xaeroColor(int rgb, int alpha) {
        return (rgb & 255) << 24 | (rgb >> 8 & 255) << 16 | (rgb >> 16 & 255) << 8 | alpha;
    }

    public int[] colors(String dimension, int x, int z, int fillAlpha, int borderAlpha) {
        // Capture a single view for all neighbors, so a network update cannot split one tile.
        View current = view;
        if (!enabled || current.snapshot() == null || !current.snapshot().dimension().equals(dimension)) {
            return null;
        }
        OwnedClaim claim = claimsAvailable(current) ? current.claims().get(key(x, z)) : null;
        if (farmsEnabled && current.farms().containsKey(key(x, z))) {
            int fill = xaeroColor(claim == null ? FARM_FILL_COLOR : claim.village().color(), fillAlpha);
            int border = xaeroColor(FARM_BORDER_COLOR, borderAlpha);
            return new int[] {fill, border, border, border, border};
        }
        if (claim == null) { return null; }
        int fill = xaeroColor(claim.village().color(), fillAlpha);
        int border = xaeroColor(claim.village().color(), borderAlpha);
        return new int[] {fill,
                same(current, claim, x, z - 1) ? fill : border,
                same(current, claim, x + 1, z) ? fill : border,
                same(current, claim, x, z + 1) ? fill : border,
                same(current, claim, x - 1, z) ? fill : border};
    }

    private static boolean same(View view, OwnedClaim claim, int x, int z) {
        OwnedClaim neighbor = view.claims().get(key(x, z));
        return neighbor != null && neighbor.village().id().equals(claim.village().id())
                && neighbor.claim().kind().equals(claim.claim().kind());
    }
}
