package dev.civmaker;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FactionRenamingTest {
    @TempDir Path directory;
    private CivStore store;
    private FactionRenaming renaming;
    private CivStore.Village village;
    private Player owner;
    private World world;
    private Block beacon;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        var config = new YamlConfiguration();
        config.set("name-filter.additional-blocked-terms", List.of("forbidden"));
        when(plugin.getConfig()).thenReturn(config);
        renaming = new FactionRenaming(plugin, store);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        beacon = mock(Block.class);
        when(beacon.getType()).thenReturn(Material.BEACON);
        when(world.getBlockAt(0, 80, 0)).thenReturn(beacon);
        owner = mock(Player.class);
        when(owner.getUniqueId()).thenReturn(UUID.randomUUID());
        when(owner.isOnline()).thenReturn(true);
        when(owner.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(owner.getWorld()).thenReturn(world);
        when(owner.getEyeLocation()).thenReturn(new Location(world, 0.5, 81.6, 1.5));
        village = new CivStore.Village("Original", owner.getUniqueId(),
                new CivStore.Position(world.getUID(), 0, 80, 0));
        store.addVillage(village);
        store.dirty = false;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownerCanRenameBeforeOrAfterUpgradeAndTheSameFactionSurvivesReload(boolean upgraded) throws Exception {
        village.expandedClaims = upgraded;
        UUID member = UUID.randomUUID();
        store.addMember(village, member);
        assertNull(renaming.rename(owner, village, "  New Dawn  "));
        assertEquals("New Dawn", village.name);
        assertSame(village, store.registeredVillage(member));
        assertSame(village, store.claimAt(village.beacon.plot()));
        assertTrue(store.dirty);
        store.save();
        CivStore restored = new CivStore(directory);
        assertEquals("New Dawn", restored.village(village.id).name);
        assertEquals(village.id, restored.registeredVillage(member).id);
        assertEquals(upgraded, restored.village(village.id).expandedClaims);
    }

    @ParameterizedTest @ValueSource(strings = {"!", "A", "A name longer than twenty four characters", "forbidden", "fuck"})
    void invalidOrFilteredNamesAreRejected(String name) {
        assertNotNull(renaming.rename(owner, village, name));
        assertEquals("Original", village.name);
        assertFalse(store.dirty);
    }

    @Test void duplicatesAreCaseInsensitiveAndTheCurrentNameCanChangeCase() {
        store.addVillage(new CivStore.Village("Oak", UUID.randomUUID(),
                new CivStore.Position(world.getUID(), 32, 80, 0)));
        assertNotNull(renaming.rename(owner, village, "oAK"));
        assertEquals("Original", village.name);
        assertNull(renaming.rename(owner, village, "ORIGINAL"));
        assertEquals("ORIGINAL", village.name);
    }

    @ParameterizedTest @ValueSource(strings = {"elder", "member", "outsider", "leave", "disband", "move", "creative", "offline", "missingBeacon", "unloaded", "world"})
    void confirmationRechecksOwnershipMembershipAndTheCapital(String change) {
        switch (change) {
            case "elder", "member", "outsider" -> {
                UUID other = UUID.randomUUID();
                if (!change.equals("outsider")) {
                    store.addMember(village, other);
                    store.setElder(village, other, change.equals("elder"));
                }
                when(owner.getUniqueId()).thenReturn(other);
            }
            case "leave" -> store.removeMember(village, owner.getUniqueId());
            case "disband" -> store.disband(village);
            case "move" -> when(owner.getEyeLocation()).thenReturn(new Location(world, 100, 80, 0));
            case "creative" -> when(owner.getGameMode()).thenReturn(GameMode.CREATIVE);
            case "offline" -> when(owner.isOnline()).thenReturn(false);
            case "missingBeacon" -> when(beacon.getType()).thenReturn(Material.AIR);
            case "unloaded" -> when(world.isChunkLoaded(0, 0)).thenReturn(false);
            case "world" -> {
                World other = mock(World.class);
                when(other.getUID()).thenReturn(UUID.randomUUID());
                when(owner.getWorld()).thenReturn(other);
            }
        }
        store.dirty = false;
        assertNotNull(renaming.rename(owner, village, "New Dawn"));
        assertEquals("Original", village.name);
        assertFalse(store.dirty);
    }
}
