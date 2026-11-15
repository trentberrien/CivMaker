package dev.civmaker;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import dev.civmaker.RaidEquipment.Tool;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WanderingTraderShopTest {
    private WanderingTraderShop traders;
    private RaidShop shop;
    private RaidEquipment equipment;
    private WarpPortals warps;
    private WanderingTrader trader;
    private Player player;
    private World world;
    private Map<NamespacedKey, String> saved;
    private List<Player> viewers;
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> icons;

    @BeforeEach void setup() {
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        shop = mock(RaidShop.class);
        when(shop.price(any())).thenReturn(10_000L);
        when(shop.purchase(any(), any(), eq(false), anyLong())).thenReturn(true);
        equipment = mock(RaidEquipment.class);
        when(equipment.item(any(), nullable(String.class))).thenReturn(mock(ItemStack.class));
        warps = mock(WarpPortals.class);
        traders = new WanderingTraderShop(plugin, shop, equipment, warps);
        world = mock(World.class);
        trader = mock(WanderingTrader.class);
        when(trader.getUniqueId()).thenReturn(UUID.randomUUID());
        when(trader.isValid()).thenReturn(true);
        when(trader.getWorld()).thenReturn(world);
        when(trader.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(trader.getPersistentDataContainer()).thenReturn(data);
        saved = new HashMap<>();
        when(data.get(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> saved.get(call.getArgument(0)));
        doAnswer(call -> { saved.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(data).set(any(), eq(PersistentDataType.STRING), anyString());
        viewers = new ArrayList<>();
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(viewers);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        });
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27),
                any(net.kyori.adventure.text.Component.class))).thenAnswer(call -> {
            Inventory inventory = mock(Inventory.class);
            when(inventory.getHolder()).thenReturn(call.getArgument(0));
            return inventory;
        });
        icons = mockConstruction(ItemStack.class);
        player = player();
    }

    @AfterEach void teardown() {
        icons.close();
        bukkit.close();
    }

    private Player player() {
        Player result = mock(Player.class);
        when(result.isOnline()).thenReturn(true);
        when(result.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(result.getWorld()).thenReturn(world);
        when(result.getLocation()).thenReturn(new Location(world, 1, 64, 0));
        InventoryView view = mock(InventoryView.class);
        AtomicReference<Inventory> top = new AtomicReference<>(mock(Inventory.class));
        when(result.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenAnswer(call -> top.get());
        when(result.openInventory(any(Inventory.class))).thenAnswer(call -> {
            top.set(call.getArgument(0));
            return view;
        });
        viewers.add(result);
        return result;
    }

    private PlayerInteractEntityEvent open(Player buyer) {
        PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
        when(event.getRightClicked()).thenReturn(trader);
        when(event.getPlayer()).thenReturn(buyer);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        traders.interact(event);
        return event;
    }

    private InventoryClickEvent click(Player buyer, ClickType type) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        Inventory menu = buyer.getOpenInventory().getTopInventory();
        when(event.getInventory()).thenReturn(menu);
        when(event.getWhoClicked()).thenReturn(buyer);
        when(event.getRawSlot()).thenReturn(10);
        when(event.getClick()).thenReturn(type);
        traders.click(event);
        return event;
    }

    private List<WanderingTraderShop.Offer> offers() {
        return WanderingTraderShop.decode(saved.values().iterator().next());
    }

    @Test void generatesSixDistinctItemsWithIndependentDiscountsIncludingBothEndpoints() {
        when(shop.price(Tool.RAID_SHIELD)).thenReturn(24_001L);
        var discounts = new java.util.HashSet<Integer>();
        for (int seed = 0; seed < 200; seed++) {
            var offers = WanderingTraderShop.generate(shop, new Random(seed));
            assertEquals(6, offers.size());
            assertEquals(6L, offers.stream().map(WanderingTraderShop.Offer::tool).distinct().count());
            for (var offer : offers) {
                assertTrue(offer.discount() >= 25 && offer.discount() <= 50);
                assertFalse(offer.sold());
                assertEquals((shop.price(offer.tool()) * (100 - offer.discount()) + 99) / 100, offer.price());
                discounts.add(offer.discount());
            }
        }
        assertTrue(discounts.containsAll(List.of(25, 50)));
        assertEquals(5_000, WanderingTraderShop.discountedPrice(10_000, 50));
        assertEquals(7_500, WanderingTraderShop.discountedPrice(10_000, 25));
        assertTrue(WanderingTraderShop.discountedPrice(Long.MAX_VALUE, 25) > 0);
    }

    @Test void purchaseIsSharedAcrossPlayersAndSavedAcrossReopeningAndListenerRecreation() {
        verify(open(player)).setCancelled(true);
        var original = offers();
        Player second = player();
        open(second);
        assertEquals(original, offers());
        var first = original.getFirst();
        click(player, ClickType.LEFT);
        click(second, ClickType.LEFT);
        verify(shop).purchase(player, first.tool(), false, first.price());
        verify(shop, never()).purchase(eq(second), any(), anyBoolean(), anyLong());
        assertTrue(offers().getFirst().sold());

        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        traders = new WanderingTraderShop(plugin, shop, equipment, warps);
        var loaded = mock(EntityAddToWorldEvent.class);
        when(loaded.getEntity()).thenReturn(trader);
        traders.loaded(loaded);
        click(player, ClickType.LEFT);
        verify(shop, times(1)).purchase(any(), any(), anyBoolean(), anyLong());
        assertTrue(offers().getFirst().sold());
    }

    @Test void failedPurchasesDoNotConsumeStockAndLaterRetryCanSucceed() {
        open(player);
        when(shop.purchase(any(), any(), anyBoolean(), anyLong())).thenReturn(false, true);
        click(player, ClickType.LEFT);
        assertFalse(offers().getFirst().sold());
        click(player, ClickType.LEFT);
        assertTrue(offers().getFirst().sold());
    }

    @Test void blocksShiftClicksHotbarSwapsDragsAndCancelledOrDistantPurchases() {
        open(player);
        verify(click(player, ClickType.SHIFT_LEFT)).setCancelled(true);
        verify(click(player, ClickType.NUMBER_KEY)).setCancelled(true);
        verify(click(player, ClickType.RIGHT)).setCancelled(true);
        var drag = mock(InventoryDragEvent.class);
        Inventory menu = player.getOpenInventory().getTopInventory();
        when(drag.getInventory()).thenReturn(menu);
        traders.drag(drag);
        verify(drag).setCancelled(true);
        when(player.getLocation()).thenReturn(new Location(world, 20, 64, 0));
        click(player, ClickType.LEFT);
        verify(player).closeInventory();
        verify(shop, never()).purchase(any(), any(), anyBoolean(), anyLong());
        assertFalse(offers().getFirst().sold());
    }

    @Test void cancelledPurchaseAndDeadOrRemovedTraderCannotConsumeStock() {
        open(player);
        var cancelled = mock(InventoryClickEvent.class);
        Inventory menu = player.getOpenInventory().getTopInventory();
        when(cancelled.getInventory()).thenReturn(menu);
        when(cancelled.isCancelled()).thenReturn(true);
        traders.click(cancelled);
        when(trader.isValid()).thenReturn(false);
        click(player, ClickType.LEFT);
        verify(shop, never()).purchase(any(), any(), anyBoolean(), anyLong());
        assertFalse(offers().getFirst().sold());
    }

    @Test void warpTraderAndCancelledInteractionKeepTheirExistingBehavior() {
        when(warps.isWarpTrader(trader)).thenReturn(true);
        var warpClick = open(player);
        verify(warpClick, never()).setCancelled(anyBoolean());
        when(warps.isWarpTrader(trader)).thenReturn(false);
        var cancelled = mock(PlayerInteractEntityEvent.class);
        when(cancelled.getRightClicked()).thenReturn(trader);
        when(cancelled.getPlayer()).thenReturn(player);
        when(cancelled.isCancelled()).thenReturn(true);
        traders.interact(cancelled);
        verify(player, never()).openInventory(any(Inventory.class));
        assertTrue(saved.isEmpty());
    }

    @Test void removesVanillaOffersAndPreventsNewVanillaTrades() {
        var loaded = mock(EntityAddToWorldEvent.class);
        when(loaded.getEntity()).thenReturn(trader);
        traders.loaded(loaded);
        verify(trader).setRecipes(List.of());
        var acquired = mock(VillagerAcquireTradeEvent.class);
        when(acquired.getEntity()).thenReturn(trader);
        traders.acquireTrade(acquired);
        verify(acquired).setCancelled(true);
    }
}
