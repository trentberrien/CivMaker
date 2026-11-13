package dev.civmaker;

import dev.civmaker.CivStore.FarmStation;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Settlement;
import dev.civmaker.CivStore.Village;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FarmStationsTest {
    @TempDir Path directory;
    private final NamespacedKey positionKey = new NamespacedKey("civmaker", "farm_station_position");
    private final NamespacedKey ownerKey = new NamespacedKey("civmaker", "farm_station_owner");
    private CivStore store;
    private FarmStations stations;
    private FarmStationLoot loot;
    private Protection protection;
    private World world;
    private Player owner;
    private Player member;
    private Player outsider;
    private Village faction;
    private Site original;

    private record Site(Block block, Chunk chunk, Map<NamespacedKey, Object> data) {}

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        loot = spy(new FarmStationLoot(plugin));
        stations = new FarmStations(plugin, store, loot);
        protection = new Protection(store, mock(RaidEquipment.class), mock(VaultDoors.class),
                mock(NaturalVillages.class), mock(DefenderGolems.class), stations);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        owner = player();
        member = player();
        outsider = player();
        faction = new Village("Farmers", owner.getUniqueId(), new Position(world.getUID(), 0, 80, 0));
        store.addVillage(faction);
        store.addMember(faction, member.getUniqueId());
        original = site(2);
        register(original, owner, "Iron Farm");
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        return player;
    }

    private Site site(int x) {
        Block block = mock(Block.class);
        Chunk chunk = mock(Chunk.class);
        when(block.getType()).thenReturn(Material.LODESTONE);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(80);
        when(block.getZ()).thenReturn(2);
        when(block.getWorld()).thenReturn(world);
        when(block.getChunk()).thenReturn(chunk);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(x >> 4);
        when(chunk.getZ()).thenReturn(0);
        when(chunk.getBlock(x & 15, 80, 2)).thenReturn(block);
        Map<NamespacedKey, Object> values = new HashMap<>();
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(chunk.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> values.get(call.getArgument(0)));
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.INTEGER_ARRAY)))
                .thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(data).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(data).set(any(NamespacedKey.class), eq(PersistentDataType.INTEGER_ARRAY), any(int[].class));
        doAnswer(call -> values.remove(call.getArgument(0))).when(data).remove(any(NamespacedKey.class));
        return new Site(block, chunk, values);
    }

    private void register(Site site, Player owner, String name) {
        site.data().put(positionKey, new int[]{site.block().getX() & 15, 80, 2});
        site.data().put(ownerKey, owner.getUniqueId().toString());
        site.data().put(loot.nameKey, name);
        store.indexFarmStation(new FarmStation(Position.at(site.block()), owner.getUniqueId(), "Owner", name));
    }

    private BlockBreakEvent mine(Site site, Player miner) {
        BlockBreakEvent event = new BlockBreakEvent(site.block(), miner);
        // Exercise both listeners in their actual registration order.
        protection.breakBlock(event);
        if (!event.isCancelled()) {
            stations.protectStation(event);
        }
        return event;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownerAndFactionMembersCanRecoverStationsInPeaceAndDuringRaids(boolean raid) {
        store.setRaidMode(raid ? CivStore.RaidMode.ON : CivStore.RaidMode.OFF);
        assertFalse(mine(original, owner).isCancelled());
        assertFalse(mine(original, member).isCancelled());
    }

    @Test void outsidersAreBlockedInPeaceButCanStealStationsInsideEnemyClaimsDuringRaids() {
        assertTrue(mine(original, outsider).isCancelled());
        store.setRaidMode(CivStore.RaidMode.ON);
        assertFalse(mine(original, outsider).isCancelled());
        store.setRaidMode(CivStore.RaidMode.OFF);
        assertTrue(mine(original, outsider).isCancelled());
    }

    @Test void unaffiliatedPlayersDoNotCountAsOneFaction() {
        store.disband(faction);
        assertFalse(mine(original, owner).isCancelled());
        assertTrue(mine(original, member).isCancelled());
        assertTrue(mine(original, outsider).isCancelled());
        store.setRaidMode(CivStore.RaidMode.ON);
        assertFalse(mine(original, outsider).isCancelled());
    }

    @Test void stationOwnershipFollowsItsOwnersCurrentFaction() {
        store.removeMember(faction, member.getUniqueId());
        assertTrue(mine(original, member).isCancelled());
        store.addMember(faction, outsider.getUniqueId());
        assertFalse(mine(original, outsider).isCancelled());
    }

    @Test void stationsKeepTheirFactionAccessAfterTheSurroundingClaimChangesOwner() {
        // Territory ownership must not override the station owner's faction permission.
        store.disband(faction);
        Village newFaction = new Village("New Farmers", owner.getUniqueId(), new Position(world.getUID(), 64, 80, 0));
        store.addVillage(newFaction);
        store.addMember(newFaction, member.getUniqueId());
        Village landOwner = new Village("Landowner", outsider.getUniqueId(), new Position(world.getUID(), 0, 80, 0));
        store.addVillage(landOwner);
        assertFalse(mine(original, member).isCancelled());
        assertTrue(mine(original, outsider).isCancelled());
    }

    @Test void raidProtectionProtectsClaimedAndWildernessStations() {
        store.setRaidMode(CivStore.RaidMode.ON);
        faction.shieldWindow = store.activeWindow(System.currentTimeMillis()).start();
        assertTrue(mine(original, outsider).isCancelled());
        Site wilderness = site(66);
        register(wilderness, owner, "Wilderness Farm");
        assertTrue(mine(wilderness, outsider).isCancelled());
        assertFalse(mine(wilderness, member).isCancelled());
    }

    @Test void protectedRaidersCannotStealButMayStillRecoverTheirOwnStations() {
        Village raiders = new Village("Raiders", outsider.getUniqueId(), new Position(world.getUID(), 64, 80, 0));
        store.addVillage(raiders);
        store.setRaidMode(CivStore.RaidMode.ON);
        raiders.shieldWindow = store.activeWindow(System.currentTimeMillis()).start();
        assertTrue(mine(original, outsider).isCancelled());
        Site own = site(66);
        register(own, outsider, "Own Farm");
        assertFalse(mine(own, outsider).isCancelled());
    }

    @Test void settlementOnlyRaidsDoNotOpenCapitalStations() {
        store.setRaidMode(CivStore.RaidMode.SETTLEMENTS);
        assertTrue(mine(original, outsider).isCancelled());
        Site settlement = site(130);
        store.addSettlement(faction, new Settlement("minecraft:village_plains",
                new Position(world.getUID(), 128, 80, 0), Set.of(Plot.at(settlement.block()))));
        register(settlement, owner, "Settlement Farm");
        assertFalse(mine(settlement, outsider).isCancelled());
    }

    @Test void ordinaryLodestonesDoNotBypassClaimProtection() {
        store.setRaidMode(CivStore.RaidMode.ON);
        original.data().remove(positionKey);
        assertTrue(mine(original, outsider).isCancelled());
    }

    @Test void stolenStationKeepsItsTagAndNameAndBecomesThePlacersProperty() {
        store.setRaidMode(CivStore.RaidMode.ON);
        assertFalse(mine(original, outsider).isCancelled());
        ItemStack vanillaDrop = mock(ItemStack.class);
        when(vanillaDrop.getType()).thenReturn(Material.LODESTONE);
        ItemStack taggedDrop = mock(ItemStack.class);
        doReturn(taggedDrop).when(loot).createStation("Iron Farm");
        Item drop = mock(Item.class);
        when(drop.getItemStack()).thenReturn(vanillaDrop);
        BlockState old = mock(BlockState.class);
        when(old.getType()).thenReturn(Material.LODESTONE);
        BlockDropItemEvent recovery = mock(BlockDropItemEvent.class);
        when(recovery.getBlock()).thenReturn(original.block());
        when(recovery.getBlockState()).thenReturn(old);
        when(recovery.getItems()).thenReturn(List.of(drop));
        when(original.block().getType()).thenReturn(Material.AIR);
        stations.recoverStation(recovery);
        verify(drop).setItemStack(taggedDrop);
        assertFalse(stations.allowsAutomation(original.chunk()));
        assertFalse(original.data().containsKey(ownerKey));
        assertNull(store.farmStationAt(Plot.at(original.block())));

        store.setRaidMode(CivStore.RaidMode.OFF);
        Site destination = site(66);
        Village newFaction = new Village("New Owners", outsider.getUniqueId(), new Position(world.getUID(), 64, 80, 0));
        store.addVillage(newFaction);
        doReturn(true).when(loot).isStation(taggedDrop);
        PersistentDataContainer itemData = mock(PersistentDataContainer.class);
        when(taggedDrop.getPersistentDataContainer()).thenReturn(itemData);
        when(itemData.get(loot.nameKey, PersistentDataType.STRING)).thenReturn("Iron Farm");
        BlockPlaceEvent placement = mock(BlockPlaceEvent.class);
        when(placement.canBuild()).thenReturn(true);
        when(placement.getBlockPlaced()).thenReturn(destination.block());
        when(placement.getItemInHand()).thenReturn(taggedDrop);
        when(placement.getPlayer()).thenReturn(outsider);
        stations.validatePlacement(placement);
        verify(placement, never()).setCancelled(true);
        stations.recordPlacement(placement);
        FarmStation placed = store.farmStationAt(Plot.at(destination.block()));
        assertNotNull(placed);
        assertEquals(outsider.getUniqueId(), placed.owner());
        assertEquals("Iron Farm", placed.name());
        assertEquals(newFaction, store.registeredVillage(placed.owner()));
        assertTrue(stations.allowsAutomation(destination.chunk()));
        assertFalse(mine(destination, outsider).isCancelled());
        assertTrue(mine(destination, owner).isCancelled());
    }
}
