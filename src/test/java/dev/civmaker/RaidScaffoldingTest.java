package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.RaidMode;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import dev.civmaker.RaidEquipment.Tool;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Scaffolding;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.List;

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
    private CivMaker plugin;
    private final List<Runnable> afterEvents = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        store.setRaidMode(RaidMode.ON);
        equipment = mock(RaidEquipment.class);
        natural = mock(NaturalVillages.class);
        protection = new Protection(store, equipment, mock(VaultDoors.class), natural,
                mock(DefenderGolems.class), mock(FarmStations.class));
        plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            afterEvents.add(call.getArgument(1));
            return null;
        });
        scaffolding = new RaidScaffolding(plugin, store, equipment);
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
        var event = new BlockPlaceEvent(block, state(block, Material.AIR), block, held, player, true, EquipmentSlot.HAND);
        return place(event);
    }

    private BlockState state(Block target, Material previous) {
        BlockState state = mock(BlockState.class);
        when(state.getBlock()).thenReturn(target);
        when(state.getType()).thenReturn(previous);
        return state;
    }

    private BlockPlaceEvent place(BlockPlaceEvent event) {
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

    @Test void scaffoldInteractionLeavesTheNativePlacementPathOpen() {
        var event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, item, block, BlockFace.UP, EquipmentSlot.HAND);
        protection.interact(event);
        assertNotEquals(Result.DENY, event.useInteractedBlock());
        assertEquals(Result.ALLOW, event.useItemInHand());
        new RaidTools(mock(CivMaker.class), store, equipment, protection, mock(VaultDoors.class),
                mock(PillagerGuards.class), mock(DefenderGolems.class), mock(SettlementVaults.class)).use(event);
        assertEquals(Result.ALLOW, event.useItemInHand());
    }

    @Test void solidGroundAllowsPlacementAndProtectedContainersRequireSneaking() {
        Material ground = mock(Material.class);
        when(ground.isInteractable()).thenReturn(false);
        when(block.getType()).thenReturn(ground);
        var event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, item, block, BlockFace.UP, EquipmentSlot.HAND);
        protection.interact(event);
        assertNotEquals(Result.DENY, event.useInteractedBlock());
        assertEquals(Result.ALLOW, event.useItemInHand());

        Material container = mock(Material.class);
        when(container.isInteractable()).thenReturn(true);
        when(block.getType()).thenReturn(container);
        event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, item, block, BlockFace.UP, EquipmentSlot.HAND);
        protection.interact(event);
        assertEquals(Result.DENY, event.useInteractedBlock());
        when(player.isSneaking()).thenReturn(true);
        event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, item, block, BlockFace.UP, EquipmentSlot.HAND);
        protection.interact(event);
        assertNotEquals(Result.DENY, event.useInteractedBlock());
        assertEquals(Result.ALLOW, event.useItemInHand());
    }

    private Scaffolding scaffoldData(int distance, boolean bottom, boolean waterlogged) {
        Scaffolding data = mock(Scaffolding.class);
        when(data.getMaterial()).thenReturn(Material.SCAFFOLDING);
        when(data.getDistance()).thenReturn(distance);
        when(data.isBottom()).thenReturn(bottom);
        when(data.isWaterlogged()).thenReturn(waterlogged);
        return data;
    }

    private BlockState connectionState(Block target, int beforeDistance, boolean beforeBottom,
                                       int afterDistance, boolean afterBottom) {
        BlockState previous = state(target, Material.SCAFFOLDING);
        Scaffolding before = scaffoldData(beforeDistance, beforeBottom, false);
        Scaffolding after = scaffoldData(afterDistance, afterBottom, false);
        when(previous.getBlockData()).thenReturn(before);
        when(target.getBlockData()).thenReturn(after);
        return previous;
    }

    @Test void connectedPlacementAllowsDistanceAndBottomChangesWithoutResettingExistingCleanup() {
        Block connected = block(5, 80, 4);
        Window earlier = new Window(1_000, 10_000);
        store.recordRaidScaffolding(Position.at(connected), earlier);
        var previousRecord = store.raidScaffolding().iterator().next();
        var event = new BlockMultiPlaceEvent(List.of(state(block, Material.AIR),
                connectionState(connected, 1, true, 0, false)), block, item, player, true, EquipmentSlot.HAND);
        assertFalse(place(event).isCancelled());
        assertTrue(store.isRaidScaffolding(block));
        assertTrue(store.raidScaffolding().contains(previousRecord));
        assertEquals(2, store.raidScaffolding().size());
    }

    @Test void existingOrdinaryScaffoldingDoesNotBecomeTemporaryAfterConnecting() {
        Block ordinary = block(5, 80, 4);
        var event = new BlockMultiPlaceEvent(List.of(state(block, Material.AIR),
                connectionState(ordinary, 3, false, 1, true)), block, item, player, true, EquipmentSlot.HAND);
        assertFalse(place(event).isCancelled());
        assertFalse(store.isRaidScaffolding(ordinary));
        assertEquals(1, store.raidScaffolding().size());
    }

    @Test void everyNewMultiPlacementTargetMustBeOpenToRaids() {
        var shielded = new Village("Shielded", UUID.randomUUID(), new Position(world.getUID(), 16, 80, 0));
        store.addVillage(shielded);
        shielded.shieldWindow = store.activeWindow(System.currentTimeMillis()).start();
        Block forbidden = block(17, 80, 4);
        var event = new BlockMultiPlaceEvent(List.of(state(block, Material.AIR), state(forbidden, Material.AIR)),
                block, item, player, true, EquipmentSlot.HAND);
        assertTrue(place(event).isCancelled());
        assertTrue(store.raidScaffolding().isEmpty());
    }

    @Test void multiPlacementCannotSmuggleOtherBlockTypesIntoClaims() {
        Block stone = block(5, 80, 4);
        when(stone.getType()).thenReturn(Material.STONE);
        var event = new BlockMultiPlaceEvent(List.of(state(block, Material.AIR), state(stone, Material.AIR)),
                block, item, player, true, EquipmentSlot.HAND);
        assertTrue(place(event).isCancelled());
        assertTrue(store.raidScaffolding().isEmpty());
    }

    private FallingBlock fallingBlock() {
        FallingBlock falling = mock(FallingBlock.class);
        when(falling.getUniqueId()).thenReturn(UUID.randomUUID());
        Scaffolding data = scaffoldData(7, true, false);
        when(falling.getBlockData()).thenReturn(data);
        return falling;
    }

    private EntityChangeBlockEvent fall(FallingBlock falling, Block target, Material material) {
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        var event = new EntityChangeBlockEvent(falling, target, data);
        protection.entityChange(event);
        if (!event.isCancelled()) scaffolding.falling(event);
        return event;
    }

    @Test void unsupportedScaffoldingCanCollapseInsideSettlementProtection() {
        when(natural.inBellProtection(block)).thenReturn(true);
        assertFalse(place(item).isCancelled());
        var fade = new BlockFadeEvent(block, state(block, Material.AIR));
        protection.fade(fade);
        assertFalse(fade.isCancelled());
        var falling = fallingBlock();
        assertFalse(fall(falling, block, Material.AIR).isCancelled());
        assertFalse(store.isRaidScaffolding(block));
        assertNotNull(store.fallingRaidScaffolding(falling.getUniqueId()));
    }

    @Test void fallingScaffoldingKeepsItsWindowAndCleanupDeadlineAcrossRestartAndLanding() throws Exception {
        assertFalse(place(item).isCancelled());
        var original = store.raidScaffolding().iterator().next();
        var falling = fallingBlock();
        assertFalse(fall(falling, block, Material.AIR).isCancelled());
        store.save();
        var restored = new CivStore(directory);
        assertEquals(original.window(), restored.fallingRaidScaffolding(falling.getUniqueId()).window());
        assertEquals(original.clearAt(), restored.fallingRaidScaffolding(falling.getUniqueId()).clearAt());
        var restoredProtection = new Protection(restored, equipment, mock(VaultDoors.class), natural,
                mock(DefenderGolems.class), mock(FarmStations.class));
        var resumed = new RaidScaffolding(plugin, restored, equipment);
        Block landed = block(4, 79, 4);
        when(landed.getType()).thenReturn(Material.AIR);
        var landing = mock(EntityChangeBlockEvent.class);
        when(landing.getEntity()).thenReturn(falling);
        when(landing.getBlock()).thenReturn(landed);
        when(landing.getTo()).thenReturn(Material.SCAFFOLDING);
        restoredProtection.entityChange(landing);
        verify(landing, never()).setCancelled(true);
        resumed.falling(landing);
        when(landed.getType()).thenReturn(Material.SCAFFOLDING);
        assertTrue(restored.isRaidScaffolding(landed));
        assertNull(restored.fallingRaidScaffolding(falling.getUniqueId()));
        assertEquals(original.window(), restored.raidScaffolding().iterator().next().window());
        restored.setRaidMode(RaidMode.OFF);
        long clearAt = restored.raidScaffolding().iterator().next().clearAt();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(world.getUID())).thenReturn(world);
            resumed.refresh(clearAt);
            verify(landed).setType(Material.AIR, false);
        }
    }

    @Test void ordinaryFallingScaffoldingCannotLandInEnemyClaims() {
        assertTrue(fall(fallingBlock(), block, Material.SCAFFOLDING).isCancelled());
    }

    @Test void raidScaffoldingCannotFallIntoShieldedClaimsOrLandAfterTheRaid() {
        assertFalse(place(item).isCancelled());
        var falling = fallingBlock();
        assertFalse(fall(falling, block, Material.AIR).isCancelled());
        Block destination = block(5, 79, 4);
        when(destination.getType()).thenReturn(Material.AIR);
        village.shieldWindow = store.activeWindow(System.currentTimeMillis()).start();
        assertTrue(fall(falling, destination, Material.SCAFFOLDING).isCancelled());
        village.shieldWindow = null;
        store.setRaidMode(RaidMode.OFF);
        assertTrue(fall(falling, destination, Material.SCAFFOLDING).isCancelled());
    }

    @Test void scheduledCleanupSurvivesRestartAndRunsThirtyMinutesAfterWindowEnd() throws Exception {
        store.setRaidMode(RaidMode.AUTO);
        Window raid = new Window(1_000, 10_000);
        store.windows().add(raid);
        store.recordRaidScaffolding(Position.at(block), raid);
        store.save();
        CivStore restored = new CivStore(directory);
        RaidScaffolding resumed = new RaidScaffolding(plugin, restored, equipment);
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
            var resumed = new RaidScaffolding(plugin, restored, equipment);
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

    @Test void cleanupPreservesWaterInsideWaterloggedScaffolding() {
        Window raid = new Window(1_000, 10_000);
        store.recordRaidScaffolding(Position.at(block), raid);
        Scaffolding data = scaffoldData(0, false, true);
        when(block.getBlockData()).thenReturn(data);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(world.getUID())).thenReturn(world);
            scaffolding.refresh(raid.end() + RaidScaffolding.CLEAR_DELAY);
            verify(block).setType(Material.WATER, false);
        }
    }

    @Test void cleanupAlsoRemovesScaffoldingStillInFlight() {
        Window raid = new Window(1_000, 10_000);
        store.recordRaidScaffolding(Position.at(block), raid);
        FallingBlock falling = fallingBlock();
        assertFalse(fall(falling, block, Material.AIR).isCancelled());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(falling.getUniqueId())).thenReturn(falling);
            scaffolding.refresh(raid.end() + RaidScaffolding.CLEAR_DELAY);
            verify(falling).remove();
            assertTrue(store.raidScaffolding().isEmpty());
        }
    }

    @Test void unloadedFallingScaffoldsKeepTheirDeadlineAndExpireOnReload() throws Exception {
        Window raid = new Window(1_000, 10_000);
        store.recordRaidScaffolding(Position.at(block), raid);
        FallingBlock falling = fallingBlock();
        assertFalse(fall(falling, block, Material.AIR).isCancelled());
        EntityRemoveEvent unloaded = mock(EntityRemoveEvent.class);
        when(unloaded.getEntity()).thenReturn(falling);
        when(unloaded.getCause()).thenReturn(EntityRemoveEvent.Cause.UNLOAD);
        scaffolding.removed(unloaded);
        store.save();
        var restored = new CivStore(directory);
        assertNotNull(restored.fallingRaidScaffolding(falling.getUniqueId()));
        EntityAddToWorldEvent loaded = mock(EntityAddToWorldEvent.class);
        when(loaded.getEntity()).thenReturn(falling);
        new RaidScaffolding(plugin, restored, equipment).loaded(loaded);
        verify(falling).remove();
        assertTrue(restored.raidScaffolding().isEmpty());
    }
}
