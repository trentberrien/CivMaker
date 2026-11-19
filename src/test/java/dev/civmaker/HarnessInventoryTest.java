package dev.civmaker;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HarnessInventoryTest {
    private HarnessInventory menus;
    private HarnessInventory.Menu menu;
    private Player player;
    private Inventory inventory;
    private EntityEquipment gear;
    private PlayerInventory storage;
    private ItemStack current;
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> icons;

    @BeforeEach void setup() {
        icons = mockConstruction(ItemStack.class);
        bukkit = mockStatic(Bukkit.class);
        inventory = mock(Inventory.class);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(9), any(net.kyori.adventure.text.Component.class)))
                .thenReturn(inventory);
        menus = new HarnessInventory(mock(RaidEquipment.class));
        HappyGhast ghast = mock(HappyGhast.class);
        when(ghast.isValid()).thenReturn(true);
        gear = mock(EntityEquipment.class);
        when(ghast.getEquipment()).thenReturn(gear);
        menu = new HarnessInventory.Menu(ghast);
        when(inventory.getHolder()).thenReturn(menu);
        player = mock(Player.class);
        when(player.getVehicle()).thenReturn(ghast);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        storage = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(storage);
        current = item(Material.YELLOW_HARNESS);
        when(gear.getItem(EquipmentSlot.BODY)).thenReturn(current);
    }

    @AfterEach void cleanup() { bukkit.close(); icons.close(); }

    private ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(1);
        when(item.getType()).thenReturn(material);
        when(item.clone()).thenReturn(item);
        return item;
    }

    private InventoryClickEvent click(int slot, ClickType type) {
        var event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(slot);
        when(event.getClick()).thenReturn(type);
        return event;
    }

    @Test void mouseSwapUsesActualEquipmentRatherThanTheMenuCopy() {
        var replacement = item(Material.BLUE_HARNESS);
        when(player.getItemOnCursor()).thenReturn(replacement);
        var stale = item(Material.RED_HARNESS);
        when(inventory.getItem(4)).thenReturn(stale);
        var event = click(4, ClickType.LEFT);
        menus.click(event);
        verify(event).setCancelled(true);
        verify(gear).setItem(EquipmentSlot.BODY, replacement);
        verify(player).setItemOnCursor(current);
        verify(player, never()).setItemOnCursor(stale);
    }

    @Test void rejectsNonHarnessAndStackedItems() {
        var incoming = item(Material.DIAMOND);
        when(player.getItemOnCursor()).thenReturn(incoming);
        menus.click(click(4, ClickType.LEFT));
        verify(gear, never()).setItem(any(), any());
        var harness = item(Material.YELLOW_HARNESS);
        when(harness.getAmount()).thenReturn(2);
        assertFalse(HarnessInventory.accepts(harness));
    }

    @Test void numberKeysSwapHarnesses() {
        var incoming = item(Material.BLUE_HARNESS);
        when(storage.getItem(2)).thenReturn(incoming);
        var event = click(4, ClickType.NUMBER_KEY);
        when(event.getHotbarButton()).thenReturn(2);
        menus.click(event);
        verify(gear).setItem(EquipmentSlot.BODY, incoming);
        verify(storage).setItem(2, current);
    }

    @Test void inventoryRearrangementStaysVanillaButDoubleCollectCannotCopyEquipment() {
        var event = click(14, ClickType.LEFT);
        menus.click(event);
        verify(event, never()).setCancelled(true);
        var collect = click(14, ClickType.DOUBLE_CLICK);
        when(collect.getAction()).thenReturn(InventoryAction.COLLECT_TO_CURSOR);
        menus.click(collect);
        verify(collect).setCancelled(true);
    }

    @Test void shiftClickEquipsFromInventoryOnlyIntoEmptyHarnessSlot() {
        var incoming = item(Material.BLUE_HARNESS);
        var event = click(14, ClickType.SHIFT_LEFT);
        when(event.isShiftClick()).thenReturn(true);
        when(event.getCurrentItem()).thenReturn(incoming);
        menus.click(event);
        verify(event).setCancelled(true);
        verify(gear, never()).setItem(any(), any());
        when(gear.getItem(EquipmentSlot.BODY)).thenReturn(null);
        menus.click(event);
        verify(gear).setItem(EquipmentSlot.BODY, incoming);
        verify(event).setCurrentItem(null);
    }

    @Test void dismountPreventsFurtherEquipmentChanges() {
        when(player.getVehicle()).thenReturn(null);
        var event = click(4, ClickType.LEFT);
        menus.click(event);
        verify(event).setCancelled(true);
        verify(gear, never()).setItem(any(), any());
    }

    @Test void draggingWithinInventoryWorksButCannotPlaceIntoMirroredEquipment() {
        var drag = mock(InventoryDragEvent.class);
        when(drag.getInventory()).thenReturn(inventory);
        when(drag.getRawSlots()).thenReturn(Set.of(12, 13));
        menus.drag(drag);
        verify(drag, never()).setCancelled(true);
        when(drag.getRawSlots()).thenReturn(Set.of(4, 12));
        menus.drag(drag);
        verify(drag).setCancelled(true);
    }
}
