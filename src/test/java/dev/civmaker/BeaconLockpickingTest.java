package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import dev.civmaker.BeaconEscrowLedger.Option;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BeaconLockpickingTest {
    private RaidTools tools;
    private CivStore store;
    private BeaconEscrow escrow;
    private Village village;
    private Window window;
    private Player player;
    private Block beacon;
    private ItemStack lockpick;
    private Inventory puzzleInventory;

    @BeforeEach void setup() {
        store = mock(CivStore.class);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        beacon = mock(Block.class);
        when(beacon.getWorld()).thenReturn(world);
        when(beacon.getType()).thenReturn(Material.BEACON);
        when(beacon.getY()).thenReturn(80);
        when(beacon.getLocation()).thenReturn(new Location(world, 0, 80, 0));
        when(world.getBlockAt(any(Location.class))).thenReturn(beacon);
        village = new Village("Defenders", UUID.randomUUID(), Position.at(beacon));
        window = new Window(1, Long.MAX_VALUE);
        when(store.protectedClaimAt(beacon)).thenReturn(village);
        when(store.activeWindow(anyLong())).thenReturn(window);
        when(store.raidCovers(any())).thenReturn(true);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 80.5, 0.5));
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        lockpick = mock(ItemStack.class);
        AtomicInteger amount = new AtomicInteger(1);
        when(lockpick.getAmount()).thenAnswer(call -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(lockpick).setAmount(anyInt());
        when(inventory.getItemInMainHand()).thenReturn(lockpick);
        RaidEquipment equipment = mock(RaidEquipment.class);
        when(equipment.identify(lockpick)).thenReturn(RaidEquipment.Tool.LOCKPICK);
        puzzleInventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(player.openInventory(puzzleInventory)).thenReturn(view);
        when(player.getOpenInventory()).thenReturn(view);
        escrow = mock(BeaconEscrow.class);
        when(escrow.canPick(player, beacon, village, window)).thenReturn(true);
        tools = new RaidTools(mock(CivMaker.class), store, equipment, mock(Protection.class),
                mock(VaultDoors.class), mock(PillagerGuards.class), mock(DefenderGolems.class), mock(SettlementVaults.class));
        tools.beaconEscrow(escrow);
    }

    private Object start() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var icons = mockConstruction(ItemStack.class);
             var puzzles = mockConstruction(LockpickPuzzle.class, (puzzle, context) ->
                when(puzzle.getInventory()).thenReturn(puzzleInventory))) {
            Inventory menuInventory = mock(Inventory.class);
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), any(net.kyori.adventure.text.Component.class)))
                    .thenReturn(menuInventory);
            when(player.openInventory(menuInventory)).thenReturn(mock(InventoryView.class));
            PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, lockpick,
                    beacon, BlockFace.NORTH, EquipmentSlot.HAND);
            // Unupgraded capital/menu protection cancels vanilla beacon interaction first.
            event.setCancelled(true);
            tools.use(event);
            assertEquals(1, lockpick.getAmount(), "Opening the option menu must not consume a pick");
            var menusField = RaidTools.class.getDeclaredField("beaconMenus");
            menusField.setAccessible(true);
            Object access = ((Map<?, ?>) menusField.get(tools)).get(player.getUniqueId());
            assertNotNull(access);
            var menuMethod = access.getClass().getDeclaredMethod("menu");
            menuMethod.setAccessible(true);
            var startMethod = RaidTools.class.getDeclaredMethod("startBeaconPicking", Player.class,
                    BeaconRaidMenu.class, Option.class, UUID.class);
            startMethod.setAccessible(true);
            startMethod.invoke(tools, player, menuMethod.invoke(access), Option.MONEY, null);
        }
        var field = RaidTools.class.getDeclaredField("picking");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(tools)).get(player.getUniqueId());
    }

    private void finish(Object attempt, LockpickPuzzle.Result result) throws Exception {
        assertNotNull(attempt);
        var method = RaidTools.class.getDeclaredMethod("finishPicking", Player.class, attempt.getClass(), LockpickPuzzle.Result.class);
        method.setAccessible(true);
        method.invoke(tools, player, attempt, result);
    }

    @Test void capitalUsesExistingPuzzleConsumesOnePickAndPaysOnlyOnce() throws Exception {
        Object attempt = start();
        assertNotNull(attempt);
        assertEquals(0, lockpick.getAmount());
        verify(escrow).canPick(player, beacon, village, window);
        finish(attempt, LockpickPuzzle.Result.SOLVED);
        finish(attempt, LockpickPuzzle.Result.SOLVED);
        verify(escrow, times(1)).loot(player, beacon, village, window);
    }

    @Test void alreadyLootedOrEmptyCapitalDoesNotConsumePick() throws Exception {
        when(escrow.canPick(player, beacon, village, window)).thenReturn(false);
        assertNull(start());
        assertEquals(1, lockpick.getAmount());
        verify(player, never()).openInventory(puzzleInventory);
    }

    @ParameterizedTest @ValueSource(strings = {"broken", "ended", "shield", "restricted", "moved", "removed"})
    void invalidCompletionsDoNotReachPayout(String reason) throws Exception {
        Object attempt = start();
        switch (reason) {
            case "ended" -> when(store.activeWindow(anyLong())).thenReturn(null);
            case "shield" -> when(store.raidCovers(any())).thenReturn(false);
            case "restricted" -> when(store.raidRestricted(player.getUniqueId())).thenReturn(true);
            case "moved" -> doReturn(new Location(player.getWorld(), 100, 80, 0)).when(player).getLocation();
            case "removed" -> when(beacon.getType()).thenReturn(Material.AIR);
        }
        finish(attempt, reason.equals("broken") ? LockpickPuzzle.Result.BROKEN : LockpickPuzzle.Result.SOLVED);
        verify(escrow, never()).loot(any(), any(), any(), any());
    }
}
