package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.RaidMode;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import dev.civmaker.RaidEquipment.Tool;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaidScaffoldingTest {
    @TempDir Path directory;
    private CivStore store;
    private RaidEquipment equipment;
    private NaturalVillages natural;
    private Protection protection;
    private RaidScaffolding scaffolding;
    private World world;
    private Block block;
    private Player player;
    private ItemStack item;
    private Village village;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        store.setRaidMode(RaidMode.ON);
        equipment = mock(RaidEquipment.class);
        natural = mock(NaturalVillages.class);
        protection = new Protection(store, equipment, mock(VaultDoors.class), natural,
                mock(DefenderGolems.class), mock(FarmStations.class));
        scaffolding = new RaidScaffolding(store, equipment);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        village = new Village("Defenders", UUID.randomUUID(), new Position(world.getUID(), 0, 80, 0));
        store.addVillage(village);
        block = block(4, 80, 4);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        item = mock(ItemStack.class);
        when(equipment.identify(item)).thenReturn(Tool.RAID_SCAFFOLDING);
    }

    private Block block(int x, int y, int z) {
        Block result = mock(Block.class);
        when(result.getWorld()).thenReturn(world);
        when(result.getX()).thenReturn(x);
        when(result.getY()).thenReturn(y);
        when(result.getZ()).thenReturn(z);
        when(result.getType()).thenReturn(Material.SCAFFOLDING);
        when(world.getBlockAt(x, y, z)).thenReturn(result);
        return result;
    }

    private BlockPlaceEvent place(ItemStack held) {
        var event = new BlockPlaceEvent(block, mock(BlockState.class), block, held, player, true, EquipmentSlot.HAND);
        protection.place(event);
        if (!event.isCancelled()) {
            scaffolding.placed(event);
        }
        return event;
    }

    @Test void outsiderCanBuildTemporaryScaffoldingInsideAnEnemySettlement() {
        when(natural.inBellProtection(block)).thenReturn(true);
        assertFalse(place(item).isCancelled());
        assertTrue(store.isRaidScaffolding(block));
        var broken = new BlockBreakEvent(block, player);
        protection.breakBlock(broken);
        assertFalse(broken.isCancelled());
        scaffolding.broken(broken);
        assertFalse(store.isRaidScaffolding(block));
    }

    @Test void ordinaryScaffoldingStillCannotBePlacedOrBrokenByOutsiders() {
        assertTrue(place(mock(ItemStack.class)).isCancelled());
        assertTrue(store.raidScaffolding().isEmpty());
        var broken = new BlockBreakEvent(block, player);
        protection.breakBlock(broken);
        assertTrue(broken.isCancelled());
    }

    @Test void scaffoldingCannotBePlacedOutsideRaidHoursEvenByTheOwner() {
        store.setRaidMode(RaidMode.OFF);
        when(player.getUniqueId()).thenReturn(village.owner);
        assertTrue(place(item).isCancelled());
        assertTrue(store.raidScaffolding().isEmpty());
    }

    @Test void shieldedClaimsAndRestrictedRaidersCannotUseScaffolding() {
        village.shieldWindow = store.activeWindow(System.currentTimeMillis()).start();
        assertTrue(place(item).isCancelled());
        village.shieldWindow = null;
        var attacker = new Village("Attackers", player.getUniqueId(), new Position(world.getUID(), 32, 80, 0));
        store.addVillage(attacker);
        attacker.shieldWindow = store.activeWindow(System.currentTimeMillis()).start();
        assertTrue(place(item).isCancelled());
        assertTrue(store.raidScaffolding().isEmpty());
    }

    @Test void settlementOnlyRaidsDoNotAllowScaffoldingInCapitalClaims() {
        store.setRaidMode(RaidMode.SETTLEMENTS);
        assertTrue(place(item).isCancelled());
    }

    @Test void naturalVillagesAndTheBeaconSkyColumnStayProtected() {
        when(natural.reserved(world, Position.at(block).plot())).thenReturn(true);
        assertTrue(place(item).isCancelled());
        when(natural.reserved(world, Position.at(block).plot())).thenReturn(false);
        block = block(0, 81, 0);
        assertTrue(place(item).isCancelled());
    }

    @Test void itemInteractionCanReachVanillaPlacementWithoutOpeningProtectedBlocks() {
        var event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, item, block, BlockFace.UP, EquipmentSlot.HAND);
        protection.interact(event);
        assertEquals(Result.DENY, event.useInteractedBlock());
        assertEquals(Result.ALLOW, event.useItemInHand());
        new RaidTools(mock(CivMaker.class), store, equipment, protection, mock(VaultDoors.class),
                mock(PillagerGuards.class), mock(DefenderGolems.class), mock(SettlementVaults.class)).use(event);
        assertEquals(Result.ALLOW, event.useItemInHand());
    }

    @Test void scheduledCleanupSurvivesRestartAndRunsThirtyMinutesAfterWindowEnd() throws Exception {
        store.setRaidMode(RaidMode.AUTO);
        Window raid = new Window(1_000, 10_000);
        store.windows().add(raid);
        store.recordRaidScaffolding(Position.at(block), raid);
        store.save();
        CivStore restored = new CivStore(directory);
        RaidScaffolding resumed = new RaidScaffolding(restored, equipment);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(world.getUID())).thenReturn(world);
            resumed.refresh(raid.end() + RaidScaffolding.CLEAR_DELAY - 1);
            verify(block, never()).setType(any(), anyBoolean());
            resumed.refresh(raid.end() + RaidScaffolding.CLEAR_DELAY);
            verify(block).setType(Material.AIR, false);
            assertTrue(restored.raidScaffolding().isEmpty());
            restored.save();
            assertTrue(new CivStore(directory).raidScaffolding().isEmpty());
        }
    }

    @Test void scaffoldingDoesNotOverrideAnExistingItemInteractionDenial() {
        var event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, item, block, BlockFace.UP, EquipmentSlot.HAND);
        event.setUseItemInHand(Result.DENY);
        protection.interact(event);
        assertEquals(Result.DENY, event.useItemInHand());
    }

    @Test void manualCloseStartsTheTimerAndAnotherRaidDoesNotExtendIt() throws Exception {
        assertFalse(place(item).isCancelled());
        long before = System.currentTimeMillis();
        store.setRaidMode(RaidMode.OFF);
        long clearAt = store.raidScaffolding().iterator().next().clearAt();
        assertTrue(clearAt >= before + RaidScaffolding.CLEAR_DELAY);
        assertTrue(clearAt <= System.currentTimeMillis() + RaidScaffolding.CLEAR_DELAY);
        store.setRaidMode(RaidMode.ON);
        store.save();
        var restored = new CivStore(directory);
        assertEquals(clearAt, restored.raidScaffolding().iterator().next().clearAt());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(world.getUID())).thenReturn(world);
            var resumed = new RaidScaffolding(restored, equipment);
            resumed.refresh(clearAt - 1);
            verify(block, never()).setType(any(), anyBoolean());
            resumed.refresh(clearAt);
            verify(block).setType(Material.AIR, false);
        }
    }

    @Test void eachRaidHasItsOwnCleanupEvenWhileANewRaidIsActive() {
        store.setRaidMode(RaidMode.AUTO);
        Window first = new Window(1_000, 10_000);
        Window second = new Window(10_001, 5_000_000);
        store.windows().add(first);
        store.windows().add(second);
        Block later = block(5, 80, 4);
        store.recordRaidScaffolding(Position.at(block), first);
        store.recordRaidScaffolding(Position.at(later), second);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(world.getUID())).thenReturn(world);
            scaffolding.refresh(first.end() + RaidScaffolding.CLEAR_DELAY);
            verify(block).setType(Material.AIR, false);
            verify(later, never()).setType(any(), anyBoolean());
            assertEquals(1, store.raidScaffolding().size());
        }
    }

    @Test void unavailableWorldIsRetriedAndReplacementBlocksArePreserved() {
        Window raid = new Window(1_000, 10_000);
        store.recordRaidScaffolding(Position.at(block), raid);
        long clearAt = raid.end() + RaidScaffolding.CLEAR_DELAY;
        try (var bukkit = mockStatic(Bukkit.class)) {
            scaffolding.refresh(clearAt);
            assertEquals(1, store.raidScaffolding().size());
            bukkit.when(() -> Bukkit.getWorld(world.getUID())).thenReturn(world);
            when(block.getType()).thenReturn(Material.STONE);
            scaffolding.refresh(clearAt);
            verify(block, never()).setType(any(), anyBoolean());
            assertTrue(store.raidScaffolding().isEmpty());
        }
    }

    @Test void replacingABrokenScaffoldWithAnOrdinaryScaffoldCancelsItsCleanup() {
        store.recordRaidScaffolding(Position.at(block), new Window(1_000, 10_000));
        when(player.getUniqueId()).thenReturn(village.owner);
        assertFalse(place(mock(ItemStack.class)).isCancelled());
        assertFalse(store.isRaidScaffolding(block));
    }
}
