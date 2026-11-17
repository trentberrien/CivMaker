package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Settlement;
import dev.civmaker.CivStore.Village;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapitalDefenderGolemsTest {
    @TempDir Path directory;
    private CivStore store;
    private DefenderGolems defenders;
    private World world;
    private Village village;
    private Player player;
    private Block beacon;
    private IronGolem golem;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        defenders = spy(new DefenderGolems(plugin, store, mock(PillagerGuards.class)));
        doNothing().when(defenders).track(any());
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Chunk chunk = mock(Chunk.class);
        when(chunk.isEntitiesLoaded()).thenReturn(true);
        when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
        Block air = mock(Block.class);
        when(air.isEmpty()).thenReturn(true);
        BlockData ground = mock(BlockData.class);
        when(ground.isFaceSturdy(any(), any())).thenReturn(true);
        when(air.getBlockData()).thenReturn(ground);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        when(world.getNearbyEntities(any(BoundingBox.class), any())).thenReturn(List.of());
        beacon = block(Material.BEACON, 0);
        village = new Village("Test", UUID.randomUUID(), Position.at(beacon));
        store.addVillage(village);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(village.owner);
        golem = mock(IronGolem.class);
        when(golem.getUniqueId()).thenReturn(UUID.randomUUID());
        when(golem.getWorld()).thenReturn(world);
        when(golem.isValid()).thenReturn(true);
        when(golem.getLocation()).thenReturn(new Location(world, 2, 80, 0));
        when(golem.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(world.spawn(any(Location.class), eq(IronGolem.class), eq(false), any())).thenReturn(golem);
    }

    private Block block(Material material, int x) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(80);
        return block;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void capitalDeploymentWorksAtBothBeaconTiersAndSurvivesSaving(boolean upgraded) throws Exception {
        village.expandedClaims = upgraded;
        assertTrue(defenders.deploy(player, beacon));
        assertEquals(golem.getUniqueId(), village.capitalGolem);
        store.save();
        assertEquals(golem.getUniqueId(), new CivStore(directory).registeredVillage(village.owner).capitalGolem);
    }

    @Test void ordinaryBeaconsAndForeignCapitalsCannotDeploy() {
        assertFalse(defenders.deploy(player, block(Material.BEACON, 1)));
        Player outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        assertFalse(defenders.deploy(outsider, beacon));
        verify(world, never()).spawn(any(Location.class), eq(IronGolem.class), anyBoolean(), any());
    }

    @Test void existingCapitalDefendersCannotBeDuplicatedEvenWhileUnloaded() {
        village.capitalGolem = golem.getUniqueId();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(golem.getUniqueId())).thenReturn(golem);
            assertFalse(defenders.deploy(player, beacon));
            bukkit.when(() -> Bukkit.getEntity(golem.getUniqueId())).thenReturn(null);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            assertFalse(defenders.deploy(player, beacon));
        }
    }

    @Test void missingCapitalDefenderCanBeReplacedOnceItsWholeAreaIsLoaded() {
        village.capitalGolem = UUID.randomUUID();
        try (var bukkit = mockStatic(Bukkit.class)) {
            assertTrue(defenders.deploy(player, beacon));
        }
        assertEquals(golem.getUniqueId(), village.capitalGolem);
    }

    @Test void settlementAndCapitalEachKeepTheirOwnDefender() {
        Block bell = block(Material.BELL, 64);
        Settlement settlement = new Settlement("minecraft:village_plains", Position.at(bell), Set.of(Position.at(bell).plot()));
        store.addSettlement(village, settlement);
        settlement.defense().golem = UUID.randomUUID();
        UUID settlementGolem = settlement.defense().golem;
        assertTrue(defenders.deploy(player, beacon));
        assertEquals(settlementGolem, settlement.defense().golem);
    }

    private Object trackCapital() throws Exception {
        Class<?> type = Class.forName("dev.civmaker.DefenderGolems$Defender");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        BossBar bar = mock(BossBar.class);
        when(bar.getPlayers()).thenReturn(List.of());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class))).thenReturn(bar);
            Object defender = constructor.newInstance(golem, new int[]{0, 80, 0, 2, 80, 0}, village.id.toString(),
                    mock(DefenseStance.RaidAggro.class));
            var loaded = DefenderGolems.class.getDeclaredField("loaded");
            loaded.setAccessible(true);
            @SuppressWarnings("unchecked") var map = (Map<IronGolem, Object>) loaded.get(defenders);
            map.put(golem, defender);
            return defender;
        }
    }

    @Test void loadedCapitalDefenderRetainsItsOwner() throws Exception {
        Object defender = trackCapital();
        var owner = DefenderGolems.class.getDeclaredMethod("owner", defender.getClass());
        owner.setAccessible(true);
        assertSame(village, owner.invoke(defenders, defender));
    }

    @Test void unloadingPreservesAssignmentButDeathReleasesIt() throws Exception {
        village.capitalGolem = golem.getUniqueId();
        trackCapital();
        defenders.removed(new EntityRemoveEvent(golem, EntityRemoveEvent.Cause.UNLOAD));
        assertEquals(golem.getUniqueId(), village.capitalGolem);
        trackCapital();
        defenders.removed(new EntityRemoveEvent(golem, EntityRemoveEvent.Cause.DEATH));
        assertNull(village.capitalGolem);
    }

    @Test void disbandedCapitalLosesItsDefender() throws Exception {
        trackCapital();
        store.disband(village);
        defenders.refresh();
        verify(golem).remove();
    }
}
