package dev.civmaker.companion;

import dev.civmaker.map.protocol.MapSnapshot;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MapStateTest {
    private static final String DIM = "minecraft:overworld";
    private static MapSnapshot snapshot(List<MapSnapshot.Claim> claims) {
        return new MapSnapshot("00000000-0000-0000-0000-000000000001", DIM, "Inactive",
                List.of(new MapSnapshot.Village("a", "Village A", "owner", "Alice", 0x5599ff, 100),
                        new MapSnapshot.Village("b", "Village B", "other", "Bob", 0xff5555, 100)), claims, List.of());
    }
    private static MapSnapshot.Claim claim(int x, int z, String village) {
        return new MapSnapshot.Claim(x, z, village, "Capital territory");
    }

    @Test void bordersFollowOwnershipAndNegativeChunkCoordinates() {
        var state = new MapState();
        state.replace(snapshot(List.of(claim(-1, -1, "a"), claim(0, -1, "a"), claim(-1, 0, "b"))));
        int[] colors = state.colors(DIM, -1, -1, 50, 230);
        assertEquals(MapState.xaeroColor(0x5599ff, 50), colors[0]);
        assertEquals(colors[0], colors[2], "No interior east border within the same village");
        assertEquals(MapState.xaeroColor(0x5599ff, 230), colors[3], "Border against another village");
        assertTrue(state.region(DIM, -1, -1));
        assertNull(state.claim("minecraft:the_nether", -1, -1));
        assertEquals(0xff995532, MapState.xaeroColor(0x5599ff, 50));
    }

    @Test void updatesRemoveDisbandedClaimsAndInvalidateCachedRegions() {
        var state = new MapState();
        state.replace(snapshot(List.of(claim(3, 4, "a"))));
        int previous = state.view().revision();
        state.replace(snapshot(List.of()));
        assertNull(state.claim(DIM, 3, 4));
        assertFalse(state.region(DIM, 0, 0));
        assertNotEquals(previous, state.view().revision());
        state.replace(snapshot(List.of(claim(3, 4, "b"))));
        assertEquals("Bob", state.claim(DIM, 3, 4).village().ownerName());
        state.setEnabled(false);
        assertNull(state.colors(DIM, 3, 4, 50, 230));
        state.setEnabled(true);
        assertNotNull(state.claim(DIM, 3, 4));
        state.clear();
        assertNull(state.view().snapshot());
        assertNull(state.claim(DIM, 3, 4));
    }

    @Test void invalidSnapshotCannotReplaceTheLastGoodView() {
        var state = new MapState();
        state.replace(snapshot(List.of(claim(1, 1, "a"))));
        var previous = state.view();
        assertThrows(RuntimeException.class, () -> state.replace(snapshot(List.of(claim(1, 1, "unknown")))));
        assertSame(previous, state.view());
        assertThrows(RuntimeException.class, () -> state.replace(snapshot(List.of(claim(Integer.MIN_VALUE, 0, "a")))));
        assertSame(previous, state.view());
        assertThrows(RuntimeException.class, () -> state.replace(snapshot(List.of(claim(1, 1, "a"), claim(1, 1, "b")))));
    }

    private static MapSnapshot withFarms(List<MapSnapshot.Claim> claims, List<MapSnapshot.Farm> farms) {
        var base = snapshot(claims);
        return new MapSnapshot(base.world(), base.dimension(), base.raidStatus(), base.villages(), claims, List.of(), farms);
    }

    private static MapSnapshot.Farm farm(int x, int z) {
        return new MapSnapshot.Farm("Wheat Farm", "owner", "Alice", "Village A", 0x5599ff, x, 70, z);
    }

    @Test void farmsHighlightWildernessAndPreserveClaimFill() {
        var state = new MapState();
        state.replace(withFarms(List.of(claim(0, 0, "a")), List.of(farm(-1, -17), farm(8, 8))));
        assertTrue(state.region(DIM, -1, -1));
        assertTrue(state.highlighted(DIM, -1, -2));
        assertNull(state.claim(DIM, -1, -2));
        int[] wilderness = state.colors(DIM, -1, -2, 50, 230);
        assertEquals(MapState.xaeroColor(MapState.FARM_FILL_COLOR, 50), wilderness[0]);
        assertEquals(MapState.xaeroColor(MapState.FARM_BORDER_COLOR, 230), wilderness[1]);
        int[] territory = state.colors(DIM, 0, 0, 50, 230);
        assertEquals(MapState.xaeroColor(0x5599ff, 50), territory[0]);
        assertEquals(MapState.xaeroColor(MapState.FARM_BORDER_COLOR, 230), territory[2]);
        assertNull(state.farm("minecraft:the_nether", -1, -2));
        int before = state.view().revision();
        state.setFarmsEnabled(false);
        assertNotEquals(before, state.view().revision());
        assertFalse(state.highlighted(DIM, -1, -2));
        assertNull(state.colors(DIM, -1, -2, 50, 230));
        assertTrue(state.highlighted(DIM, 0, 0));
        assertEquals(MapState.xaeroColor(0x5599ff, 230), state.colors(DIM, 0, 0, 50, 230)[2]);
        state.setFarmsEnabled(true);
        assertNotNull(state.farm(DIM, -1, -2));
        state.replace(snapshot(List.of()));
        assertTrue(state.view().farms().isEmpty());
        assertFalse(state.region(DIM, -1, -1));
    }

    @Test void farmValidationIsAtomicAndOlderSnapshotsRemainCompatible() {
        var state = new MapState();
        state.replace(withFarms(List.of(), List.of(farm(1, 1))));
        var previous = state.view();
        assertThrows(RuntimeException.class, () -> state.replace(withFarms(List.of(), List.of(farm(1, 1), farm(2, 2)))));
        assertSame(previous, state.view());
        assertThrows(RuntimeException.class, () -> state.replace(withFarms(List.of(), List.of(farm(Integer.MIN_VALUE, 1)))));
        assertSame(previous, state.view());
        String legacy = new com.google.gson.Gson().toJson(snapshot(List.of()));
        legacy = legacy.replace(",\"farms\":[]", "");
        var decoded = new com.google.gson.Gson().fromJson(legacy, MapSnapshot.class);
        assertNotNull(decoded.farms());
        assertTrue(decoded.farms().isEmpty());
        state.replace(decoded);
        assertTrue(state.view().farms().isEmpty());
    }

    @Test void radarPolicyHidesEnemyPlayersAndClaimsButKeepsFarmsAndTeammates() {
        var state = new MapState();
        var friend = java.util.UUID.randomUUID();
        var enemy = java.util.UUID.randomUUID();
        var base = withFarms(List.of(claim(0, 0, "a")), List.of(farm(8, 8)));
        state.setRadarServer(true);
        assertTrue(state.hidePlayer(enemy), "Hide enemies while waiting for the server's first verdict");
        state.replace(new MapSnapshot(base.world(), base.dimension(), base.raidStatus(), base.villages(),
                base.claims(), base.markers(), base.farms(), new MapSnapshot.RadarAccess(false, List.of(friend.toString()))));
        assertFalse(state.claimsAvailable());
        assertNull(state.claim(DIM, 0, 0));
        assertEquals(MapState.xaeroColor(MapState.FARM_FILL_COLOR, 50), state.colors(DIM, 0, 0, 50, 230)[0]);
        assertNotNull(state.farm(DIM, 0, 0));
        assertTrue(state.hidePlayer(enemy));
        assertFalse(state.hidePlayer(friend));
        state.setEnabled(false);
        assertTrue(state.hidePlayer(enemy), "Display toggles must not bypass the inventory requirement");
        state.setEnabled(true);
        state.replace(new MapSnapshot(base.world(), base.dimension(), base.raidStatus(), base.villages(),
                base.claims(), base.markers(), base.farms(), new MapSnapshot.RadarAccess(true, List.of(friend.toString()))));
        assertTrue(state.claimsAvailable());
        assertNotNull(state.claim(DIM, 0, 0));
        assertFalse(state.hidePlayer(enemy));
        state.clear();
        state.setRadarServer(false);
        assertFalse(state.hidePlayer(enemy), "Other servers are unaffected");
    }
}
