package dev.civmaker;

import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnderChestStorageTest {
    private final Notes notes = mock(Notes.class);
    private final RaidEquipment equipment = mock(RaidEquipment.class);
    private final SettlementResources resources = mock(SettlementResources.class);
    private final EnderChestStorage rules = new EnderChestStorage(notes, equipment, resources);
    private final Inventory top = mock(Inventory.class);
    private final PlayerInventory bottom = mock(PlayerInventory.class);
    private final Player player = mock(Player.class);
    private final InventoryView view = mock(InventoryView.class);

    @BeforeEach void setup() {
        when(player.getEnderChest()).thenReturn(top);
        when(top.getSize()).thenReturn(27);
        when(view.getTopInventory()).thenReturn(top);
        when(player.getInventory()).thenReturn(bottom);
    }

    private ItemStack ordinary() { return mock(ItemStack.class, RETURNS_DEEP_STUBS); }
    private ItemStack note() {
        ItemStack item = ordinary();
        when(notes.isNote(item)).thenReturn(true);
        return item;
    }
    private InventoryClickEvent click(InventoryAction action, Inventory clicked) {
        var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(clicked);
        when(event.getAction()).thenReturn(action);
        return event;
    }

    @Test void recognizesAllSpecialItemSourcesAndAllowsOrdinaryItems() {
        ItemStack currency = ordinary(), tool = ordinary(), beacon = ordinary(), purchase = ordinary();
        when(resources.isCurrency(currency)).thenReturn(true);
        when(equipment.identify(tool)).thenReturn(RaidEquipment.Tool.GRENADE);
        when(beacon.getPersistentDataContainer().has(FoundingBeacons.BEACON_KEY, PersistentDataType.BYTE)).thenReturn(true);
        when(purchase.getPersistentDataContainer().has(EnderChestStorage.PURCHASED, PersistentDataType.BYTE)).thenReturn(true);
        for (ItemStack item : List.of(note(), currency, tool, beacon, purchase)) assertTrue(rules.restricted(item));
        assertFalse(rules.restricted(ordinary()));
        assertFalse(rules.restricted(null));
    }

    @Test void detectsSpecialItemsInsideNestedShulkerAndBundle() {
        ItemStack bundle = ordinary(), box = ordinary();
        BundleMeta bundleMeta = mock(BundleMeta.class);
        when(bundle.getItemMeta()).thenReturn(bundleMeta);
        doReturn(List.of(note())).when(bundleMeta).getItems();
        BlockStateMeta boxMeta = mock(BlockStateMeta.class);
        ShulkerBox shulker = mock(ShulkerBox.class);
        Inventory contents = mock(Inventory.class);
        when(box.getItemMeta()).thenReturn(boxMeta);
        when(boxMeta.getBlockState()).thenReturn(shulker);
        when(shulker.getInventory()).thenReturn(contents);
        when(contents.getContents()).thenReturn(new ItemStack[]{bundle});
        assertTrue(rules.restricted(box));
        when(bundleMeta.getItems()).thenReturn(List.of(ordinary()));
        assertFalse(rules.restricted(box));
    }

    @ParameterizedTest @EnumSource(value = InventoryAction.class, names = {"PLACE_ALL", "PLACE_ONE", "PLACE_SOME",
            "SWAP_WITH_CURSOR", "PLACE_FROM_BUNDLE", "PLACE_ALL_INTO_BUNDLE", "PLACE_SOME_INTO_BUNDLE"})
    void blocksCursorDeposits(InventoryAction action) {
        var event = click(action, top);
        doReturn(note()).when(event).getCursor();
        rules.click(event);
        verify(event).setCancelled(true);
    }

    @Test void blocksShiftNumberKeyAndOffhandDeposits() {
        ItemStack item = note();
        var shift = click(InventoryAction.MOVE_TO_OTHER_INVENTORY, bottom);
        when(shift.getCurrentItem()).thenReturn(item);
        rules.click(shift);
        verify(shift).setCancelled(true);
        var number = click(InventoryAction.HOTBAR_SWAP, top);
        when(number.getHotbarButton()).thenReturn(3);
        when(bottom.getItem(3)).thenReturn(item);
        rules.click(number);
        verify(number).setCancelled(true);
        var offhand = click(InventoryAction.HOTBAR_MOVE_AND_READD, top);
        when(offhand.getClick()).thenReturn(ClickType.SWAP_OFFHAND);
        when(bottom.getItemInOffHand()).thenReturn(item);
        rules.click(offhand);
        verify(offhand).setCancelled(true);
    }

    @Test void allowsWithdrawalsAndOrdinaryDepositsAndOtherContainers() {
        var withdraw = click(InventoryAction.MOVE_TO_OTHER_INVENTORY, top);
        doReturn(note()).when(withdraw).getCurrentItem();
        rules.click(withdraw);
        verify(withdraw, never()).setCancelled(true);
        var deposit = click(InventoryAction.PLACE_ALL, top);
        when(deposit.getCursor()).thenReturn(ordinary());
        rules.click(deposit);
        verify(deposit, never()).setCancelled(true);
        when(player.getEnderChest()).thenReturn(mock(Inventory.class));
        doReturn(note()).when(deposit).getCursor();
        rules.click(deposit);
        verify(deposit, never()).setCancelled(true);
    }

    @Test void blocksDraggingIntoEnderChestButAllowsRearrangingOwnInventory() {
        var event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        doReturn(note()).when(event).getOldCursor();
        when(event.getRawSlots()).thenReturn(Set.of(27, 28));
        rules.drag(event);
        verify(event, never()).setCancelled(true);
        when(event.getRawSlots()).thenReturn(Set.of(26, 27));
        rules.drag(event);
        verify(event).setCancelled(true);
    }
}
