package dev.civmaker;

import dev.civmaker.RaidEquipment.Tool;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.mockito.Mockito.*;

class RaidShopTest {
    private RaidShop shop;
    private Player player;
    private PlayerInventory inventory;
    private Inventory menu;
    private Wallets wallets;
    private RaidEquipment equipment;
    private ItemStack radar;
    private ItemStack scaffolding;
    private ItemStack[] contents;

    @BeforeEach void setup() {
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        equipment = mock(RaidEquipment.class);
        when(equipment.item(any(Tool.class), nullable(String.class))).thenReturn(mock(ItemStack.class));
        radar = mock(ItemStack.class);
        when(radar.getAmount()).thenReturn(1);
        when(radar.getMaxStackSize()).thenReturn(64);
        when(radar.asQuantity(1)).thenReturn(radar);
        when(equipment.item(Tool.RADAR, null)).thenReturn(radar);
        scaffolding = mock(ItemStack.class);
        when(scaffolding.getAmount()).thenReturn(64);
        when(scaffolding.getMaxStackSize()).thenReturn(64);
        when(scaffolding.asQuantity(64)).thenReturn(scaffolding);
        when(equipment.item(Tool.RAID_SCAFFOLDING, null)).thenReturn(scaffolding);
        wallets = mock(Wallets.class);
        player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        contents = new ItemStack[36];
        when(inventory.getStorageContents()).thenReturn(contents);
        when(inventory.getMaxStackSize()).thenReturn(64);
        shop = new RaidShop(plugin, mock(CivStore.class), wallets, equipment,
                new SettlementResources(plugin, equipment), mock(RaidShields.class), mock(FoundingBeacons.class));
        menu = mock(Inventory.class);
        when(menu.getSize()).thenReturn(45);
        try (var bukkit = mockStatic(Bukkit.class); var icons = mockConstruction(ItemStack.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(45), any(net.kyori.adventure.text.Component.class)))
                    .thenAnswer(call -> { when(menu.getHolder()).thenReturn(call.getArgument(0)); return menu; });
            shop.open(player);
        }
    }

    private ItemStack copper(int amount, boolean settlement) {
        return currency(amount, settlement, "COPPER");
    }

    private ItemStack currency(int amount, boolean settlement, String reward) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(settlement ? Material.STRUCTURE_VOID : Material.COPPER_INGOT);
        when(item.getAmount()).thenReturn(amount);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getPersistentDataContainer()).thenReturn(data);
        NamespacedKey key = new NamespacedKey("civmaker", "settlement_currency");
        when(data.has(key, PersistentDataType.STRING)).thenReturn(settlement);
        when(data.get(key, PersistentDataType.STRING)).thenReturn(reward);
        when(item.asQuantity(anyInt())).thenAnswer(call -> currency(call.getArgument(0), settlement, reward));
        return item;
    }

    private void buyRadar(ClickType click) {
        buy(click, 39);
    }

    private void buy(ClickType click, int slot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(menu);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClick()).thenReturn(click);
        when(event.getRawSlot()).thenReturn(slot);
        shop.click(event);
    }

    private ItemStack product(Tool tool, int quantity) {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(quantity);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.asQuantity(quantity)).thenReturn(item);
        when(equipment.item(tool, null)).thenReturn(item);
        return item;
    }

    @ParameterizedTest
    @EnumSource(value = Tool.class, names = {"C4", "GRENADE"})
    void explosivesChargeTwentyThousandAndRejectInsufficientCash(Tool tool) {
        product(tool, 1);
        int slot = tool == Tool.C4 ? 14 : 15;
        org.junit.jupiter.api.Assertions.assertEquals(20_000L, shop.price(tool));
        when(wallets.balance(player)).thenReturn(19_999L);
        buy(ClickType.LEFT, slot);
        verify(inventory, never()).setStorageContents(any());
        verify(wallets, never()).setBalance(any(), anyLong());
        when(wallets.balance(player)).thenReturn(25_000L);
        buy(ClickType.LEFT, slot);
        verify(wallets).setBalance(player, 5_000L);
        verify(player).sendMessage("Bought " + tool.label + " for $20,000.");
    }

    @ParameterizedTest
    @EnumSource(value = Tool.class, names = {"C4", "GRENADE"})
    void explosivesRequireTenMatchingCurrencyAndPreserveChange(Tool tool) {
        ItemStack item = product(tool, 1);
        int slot = tool == Tool.C4 ? 14 : 15;
        contents[0] = currency(5, true, "GUNPOWDER");
        buy(ClickType.RIGHT, slot);
        contents[0] = currency(9, true, "GUNPOWDER");
        buy(ClickType.RIGHT, slot);
        contents[0] = copper(10, true);
        buy(ClickType.RIGHT, slot);
        verify(inventory, never()).setStorageContents(any());
        contents[0] = currency(12, true, "GUNPOWDER");
        buy(ClickType.RIGHT, slot);
        var result = org.mockito.ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setStorageContents(result.capture());
        org.junit.jupiter.api.Assertions.assertEquals(2, result.getValue()[0].getAmount());
        org.junit.jupiter.api.Assertions.assertSame(item, result.getValue()[1]);
        verify(wallets, never()).setBalance(any(), anyLong());
        verify(player).sendMessage("Bought " + tool.label + " for 10 Settlement Gunpowder.");
    }

    @Test void scaffoldingCostsTenThousandForAStack() {
        when(wallets.balance(player)).thenReturn(10_000L);
        buy(ClickType.LEFT, 17);
        verify(wallets).setBalance(player, 0L);
        var result = org.mockito.ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setStorageContents(result.capture());
        org.junit.jupiter.api.Assertions.assertSame(scaffolding, result.getValue()[0]);
        verify(player).sendMessage("Bought 64 Raid Scaffolding for $10,000.");
    }

    @Test void scaffoldingCostsFiveSettlementCopperWithoutWalletMoney() {
        contents[0] = copper(5, true);
        buy(ClickType.RIGHT, 17);
        var result = org.mockito.ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setStorageContents(result.capture());
        org.junit.jupiter.api.Assertions.assertSame(scaffolding, result.getValue()[0]);
        verify(wallets, never()).setBalance(any(), anyLong());
        verify(player).sendMessage("Bought 64 Raid Scaffolding for 5 Settlement Copper.");
    }

    @Test void scaffoldingRejectsOrdinaryAndInsufficientCopper() {
        contents[0] = copper(64, false);
        buy(ClickType.RIGHT, 17);
        contents[0] = copper(4, true);
        buy(ClickType.RIGHT, 17);
        verify(inventory, never()).setStorageContents(any());
        verify(wallets, never()).setBalance(any(), anyLong());
        verify(player, times(2)).sendMessage("You need 5 Settlement Copper in your inventory.");
    }

    @Test void rightClickBuysRadarWithFiveSettlementCopperAndNoWalletMoney() {
        contents[0] = copper(5, true);
        buyRadar(ClickType.RIGHT);
        org.mockito.ArgumentCaptor<ItemStack[]> result = org.mockito.ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setStorageContents(result.capture());
        org.junit.jupiter.api.Assertions.assertSame(radar, result.getValue()[0]);
        verify(wallets, never()).setBalance(any(), anyLong());
        verify(player).sendMessage("Bought Radar for 5 Settlement Copper.");
    }

    @Test void ordinaryCopperAndInsufficientSettlementCopperCannotBuyRadar() {
        contents[0] = copper(64, false);
        buyRadar(ClickType.RIGHT);
        contents[0] = copper(4, true);
        buyRadar(ClickType.RIGHT);
        verify(inventory, never()).setStorageContents(any());
        verify(wallets, never()).setBalance(any(), anyLong());
        org.junit.jupiter.api.Assertions.assertEquals(4, contents[0].getAmount());
        verify(player, times(2)).sendMessage("You need 5 Settlement Copper in your inventory.");
    }

    @Test void leftClickStillBuysRadarWithWalletMoney() {
        when(wallets.balance(player)).thenReturn(10_000L);
        buyRadar(ClickType.LEFT);
        verify(wallets).setBalance(player, 0L);
        verify(inventory).setStorageContents(any());
        verify(player).sendMessage("Bought Radar for $10,000.");
    }

    @Test void traderPurchaseChargesTheDiscountedWalletPrice() {
        when(wallets.balance(player)).thenReturn(10_000L);
        org.junit.jupiter.api.Assertions.assertTrue(shop.purchase(player, Tool.RADAR, false, 5_700));
        verify(wallets).setBalance(player, 4_300L);
        verify(inventory).setStorageContents(any());
        verify(player).sendMessage("Bought Radar for $5,700.");
    }

    @Test void failedTraderPurchasesLeaveMoneyAndInventoryUntouched() {
        when(wallets.balance(player)).thenReturn(4_999L);
        org.junit.jupiter.api.Assertions.assertFalse(shop.purchase(player, Tool.RADAR, false, 5_000));
        when(wallets.balance(player)).thenReturn(10_000L);
        ItemStack full = mock(ItemStack.class);
        when(full.isEmpty()).thenReturn(false);
        java.util.Arrays.fill(contents, full);
        org.junit.jupiter.api.Assertions.assertFalse(shop.purchase(player, Tool.RADAR, false, 5_000));
        verify(wallets, never()).setBalance(any(), anyLong());
        verify(inventory, never()).setStorageContents(any());
    }
}
