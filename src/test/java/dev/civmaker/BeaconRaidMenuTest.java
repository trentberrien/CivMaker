package dev.civmaker;

import dev.civmaker.BeaconEscrowLedger.Option;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BeaconRaidMenuTest {
    @Test void selectionShowsOccupiedSlotCountsWithoutRevealingContents() {
        var escrow = mock(BeaconEscrow.class);
        var village = new CivStore.Village("Enemy", UUID.randomUUID(), new CivStore.Position(UUID.randomUUID(), 0, 80, 0));
        UUID owner = UUID.randomUUID();
        var offline = new BeaconEscrowLedger.Offline(village.id, village.beacon, "HiddenPlayer", null);
        when(escrow.offlinePlayers(village)).thenReturn(Map.of(owner, offline));
        ItemStack[] contents = new ItemStack[27];
        contents[0] = mock(ItemStack.class);
        contents[5] = mock(ItemStack.class);
        contents[10] = mock(ItemStack.class);
        when(contents[10].isEmpty()).thenReturn(true);
        when(escrow.items(owner, village, Option.ENDER_CHEST)).thenReturn(contents);
        List<Component> lore = new ArrayList<>();
        try (var bukkit = mockStatic(Bukkit.class);
             var icons = mockConstruction(ItemStack.class, (item, context) -> {
                 var meta = mock(ItemMeta.class);
                 doAnswer(call -> {
                     call.<Consumer<ItemMeta>>getArgument(0).accept(meta);
                     return true;
                 }).when(item).editMeta(any());
                 doAnswer(call -> {
                     if (context.arguments().getFirst() == Material.PLAYER_HEAD)
                         lore.addAll(call.<List<Component>>getArgument(0));
                     return null;
                 }).when(meta).lore(anyList());
             })) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                    .thenReturn(mock(Inventory.class));
            var menu = new BeaconRaidMenu(null, village, new CivStore.Window(1, Long.MAX_VALUE),
                    BeaconRaidMenu.Page.PLAYERS, Option.ENDER_CHEST, null, 0, escrow);
            assertEquals(owner, menu.players.get(0));
            assertTrue(lore.contains(Component.text("Occupied slots: 2 / 27")));
            verify(escrow, never()).claimItems(any(), any(), any(), any(), any(), any());
        }
    }

    @Test void showsOnlyGlassAndAllowsExactlyFiveDifferentSlotWithdrawals() throws Exception {
        BeaconEscrow escrow = mock(BeaconEscrow.class);
        World world = mock(World.class);
        Location location = new Location(world, 0.5, 80.5, 0.5);
        Block beacon = mock(Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(beacon);
        var village = new CivStore.Village("Enemy", UUID.randomUUID(), new CivStore.Position(UUID.randomUUID(), 0, 80, 0));
        var window = new CivStore.Window(1, Long.MAX_VALUE);
        UUID owner = UUID.randomUUID();
        ItemStack[] items = new ItemStack[42];
        for (int slot = 0; slot < 7; slot++) items[slot] = mock(ItemStack.class);
        when(escrow.items(owner, village, Option.INVENTORY)).thenReturn(items);
        Player player = mock(Player.class);
        when(escrow.takeItem(eq(player), eq(owner), eq(village), eq(Option.INVENTORY), anyInt())).thenReturn(true);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(45);
        List<Material> displayed = new ArrayList<>();
        try (var bukkit = mockStatic(Bukkit.class);
             var icons = mockConstruction(ItemStack.class, (item, context) -> displayed.add((Material) context.arguments().getFirst()))) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(45), any(Component.class))).thenReturn(inventory);
            BeaconRaidMenu menu = new BeaconRaidMenu(location, village, window, BeaconRaidMenu.Page.LOOT,
                    Option.INVENTORY, owner, 0, escrow);
            assertEquals(7, displayed.size());
            assertTrue(displayed.stream().allMatch(material -> material == Material.GRAY_STAINED_GLASS_PANE));
            RaidTools tools = new RaidTools(mock(CivMaker.class), mock(CivStore.class), mock(RaidEquipment.class),
                    mock(Protection.class), mock(VaultDoors.class), mock(PillagerGuards.class),
                    mock(DefenderGolems.class), mock(SettlementVaults.class));
            tools.beaconEscrow(escrow);
            var click = RaidTools.class.getDeclaredMethod("clickBeaconMenu", Player.class, BeaconRaidMenu.class, int.class);
            click.setAccessible(true);
            click.invoke(tools, player, menu, 0);
            click.invoke(tools, player, menu, 0);
            for (int slot = 1; slot < 7; slot++) click.invoke(tools, player, menu, slot);
            verify(escrow, times(5)).takeItem(eq(player), eq(owner), eq(village), eq(Option.INVENTORY), anyInt());
            verify(escrow, never()).takeItem(player, owner, village, Option.INVENTORY, 5);
            assertEquals(0, menu.remaining);
        }
    }
}
