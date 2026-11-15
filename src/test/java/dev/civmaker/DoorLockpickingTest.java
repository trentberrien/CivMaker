package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DoorLockpickingTest {
    private RaidTools tools;
    private CivStore store;
    private Village village;
    private Player player;
    private Block bottom;
    private Block top;
    private Door lower;
    private Door upper;
    private ItemStack lockpick;
    private Inventory puzzleInventory;
    private final AtomicBoolean lowerOpen = new AtomicBoolean();
    private final AtomicBoolean upperOpen = new AtomicBoolean();

    @BeforeEach void setup() {
        store = mock(CivStore.class);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        bottom = block(world, 80);
        top = block(world, 81);
        when(bottom.getRelative(BlockFace.UP)).thenReturn(top);
        when(top.getRelative(BlockFace.DOWN)).thenReturn(bottom);
        lower = data(Bisected.Half.BOTTOM, lowerOpen);
        upper = data(Bisected.Half.TOP, upperOpen);
        when(bottom.getBlockData()).thenReturn(lower);
        when(top.getBlockData()).thenReturn(upper);
        when(world.getBlockAt(any(Location.class))).thenAnswer(call ->
                call.<Location>getArgument(0).getBlockY() == 80 ? bottom : top);
        village = new Village("Defenders", UUID.randomUUID(), new Position(world.getUID(), 32, 80, 0));
        village.vaultDoors = new HashSet<>();
        village.vaultDoors.add(Position.at(bottom));
        when(store.protectedClaimAt(any(Block.class))).thenReturn(village);
        when(store.activeWindow(anyLong())).thenReturn(new Window(1, Long.MAX_VALUE));
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
        tools = new RaidTools(mock(CivMaker.class), store, equipment, mock(Protection.class),
                new VaultDoors(store, equipment), mock(PillagerGuards.class), mock(DefenderGolems.class), mock(SettlementVaults.class));
    }

    private Block block(World world, int y) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.IRON_DOOR);
        when(block.getY()).thenReturn(y);
        when(block.getLocation()).thenAnswer(call -> new Location(world, 0, y, 0));
        return block;
    }

    private Door data(Bisected.Half half, AtomicBoolean open) {
        Door door = mock(Door.class);
        when(door.getHalf()).thenReturn(half);
        when(door.isOpen()).thenAnswer(call -> open.get());
        doAnswer(call -> { open.set(call.getArgument(0)); return null; }).when(door).setOpen(anyBoolean());
        return door;
    }

    private Object start(Block target) throws Exception {
        try (var puzzles = mockConstruction(LockpickPuzzle.class, (puzzle, context) ->
                when(puzzle.getInventory()).thenReturn(puzzleInventory))) {
            tools.use(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, lockpick,
                    target, BlockFace.NORTH, EquipmentSlot.HAND));
        }
        var field = RaidTools.class.getDeclaredField("picking");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(tools)).get(player.getUniqueId());
    }

    private void finish(Object attempt, LockpickPuzzle.Result result) throws Exception {
        assertNotNull(attempt, "The door must accept a lockpick attempt in either state");
        var method = RaidTools.class.getDeclaredMethod("finishPicking", Player.class, attempt.getClass(), LockpickPuzzle.Result.class);
        method.setAccessible(true);
        method.invoke(tools, player, attempt, result);
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void solvedLockpickTogglesBothHalvesFromEitherDoorState(boolean open, boolean clickTop) throws Exception {
        lowerOpen.set(open);
        upperOpen.set(open);
        Object attempt = start(clickTop ? top : bottom);
        assertNotNull(attempt);
        assertEquals(0, lockpick.getAmount());
        finish(attempt, LockpickPuzzle.Result.SOLVED);
        assertEquals(!open, lowerOpen.get());
        assertEquals(!open, upperOpen.get());
        verify(bottom).setBlockData(lower, false);
        verify(top).setBlockData(upper, false);
        verify(player).sendMessage(open ? "Vault Door picked closed." : "Vault Door picked open.");
        finish(attempt, LockpickPuzzle.Result.SOLVED);
        assertEquals(!open, lowerOpen.get(), "A completed puzzle cannot toggle the door twice");
    }

    @Test void successTogglesTheDoorsCurrentStateIfItChangedDuringThePuzzle() throws Exception {
        Object attempt = start(bottom);
        lowerOpen.set(true);
        upperOpen.set(true);
        finish(attempt, LockpickPuzzle.Result.SOLVED);
        assertFalse(lowerOpen.get());
        assertFalse(upperOpen.get());
    }

    @ParameterizedTest @ValueSource(strings = {"broken", "raidEnded", "shielded", "restricted", "moved", "doorRemoved"})
    void failedOrInvalidAttemptsNeverChangeTheDoor(String reason) throws Exception {
        Object attempt = start(bottom);
        switch (reason) {
            case "raidEnded" -> when(store.activeWindow(anyLong())).thenReturn(null);
            case "shielded" -> when(store.raidCovers(any())).thenReturn(false);
            case "restricted" -> when(store.raidRestricted(player.getUniqueId())).thenReturn(true);
            case "moved" -> {
                Location farAway = new Location(player.getWorld(), 100, 80, 0);
                when(player.getLocation()).thenReturn(farAway);
            }
            case "doorRemoved" -> when(bottom.getType()).thenReturn(Material.AIR);
        }
        finish(attempt, reason.equals("broken") ? LockpickPuzzle.Result.BROKEN : LockpickPuzzle.Result.SOLVED);
        assertFalse(lowerOpen.get());
        assertFalse(upperOpen.get());
        verify(bottom, never()).setBlockData(any(), anyBoolean());
        verify(top, never()).setBlockData(any(), anyBoolean());
    }
}
