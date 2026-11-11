package dev.civmaker;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaimBuyingTest {
    @TempDir Path directory;
    private CivStore store;
    private ClaimBuying claims;
    private Wallets wallets;
    private World world;
    private Block beacon;
    private CivStore.Village village;
    private Player owner;
    private Player elder;
    private Player member;
    private final List<Runnable> tasks = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        var config = new YamlConfiguration();
        config.set("village.chunk-price", 1_000);
        when(plugin.getConfig()).thenReturn(config);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(call -> { tasks.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        wallets = mock(Wallets.class);
        claims = new ClaimBuying(plugin, store, wallets, mock(NaturalVillages.class));
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        beacon = mock(Block.class);
        when(beacon.getWorld()).thenReturn(world);
        when(beacon.getY()).thenReturn(80);
        when(beacon.getType()).thenReturn(Material.BEACON);
        when(world.getBlockAt(0, 80, 0)).thenReturn(beacon);
        owner = player();
        elder = player();
        member = player();
        village = new CivStore.Village("Test", owner.getUniqueId(), CivStore.Position.at(beacon));
        store.addVillage(village);
        store.addMember(village, elder.getUniqueId());
        store.addMember(village, member.getUniqueId());
        store.setElder(village, elder.getUniqueId(), true);
        store.dirty = false;
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isSneaking()).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 80, 1.5));
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.5, 81.6, 1.5));
        when(wallets.balance(player)).thenReturn(150_000L);
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        doAnswer(call -> {
            when(view.getTopInventory()).thenReturn(mock(Inventory.class));
            return null;
        }).when(player).closeInventory();
        return player;
    }

    private PlayerInteractEvent interact(Player player, Block block) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getClickedBlock()).thenReturn(block);
        claims.click(event);
        return event;
    }

    private Inventory openUpgrade(Player player) {
        Inventory inventory = mock(Inventory.class);
        try (var bukkit = mockStatic(Bukkit.class); var icons = mockConstruction(ItemStack.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(Component.class)))
                    .thenAnswer(call -> {
                        when(inventory.getHolder()).thenReturn(call.getArgument(0));
                        return inventory;
                    });
            interact(player, beacon);
            verify(player).openInventory(inventory);
            verify(inventory).setItem(eq(4), any(ItemStack.class));
            verify(inventory).setItem(eq(11), any(ItemStack.class));
            verify(inventory).setItem(eq(15), any(ItemStack.class));
        }
        when(player.getOpenInventory().getTopInventory()).thenReturn(inventory);
        return inventory;
    }

    private void choose(Player player, int slot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = player.getOpenInventory();
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getRawSlot()).thenReturn(slot);
        claims.menuClick(event);
        verify(event).setCancelled(true);
        tasks.removeFirst().run();
    }

    @Test void openingAndCancellingNeverChargeOrUpgrade() {
        openUpgrade(elder);
        assertFalse(village.expandedClaims);
        verify(wallets, never()).setBalance(any(), anyLong());
        choose(elder, 15);
        assertFalse(village.expandedClaims);
        assertFalse(store.dirty);
        verify(wallets, never()).setBalance(any(), anyLong());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ownerOrElderCanConfirmOneSavedUpgrade(boolean asOwner) throws Exception {
        Player buyer = asOwner ? owner : elder;
        openUpgrade(buyer);
        choose(buyer, 11);
        assertEquals(200, village.capitalExpansionLimit());
        verify(wallets).setBalance(buyer, 50_000);
        assertTrue(store.dirty);
        store.save();
        assertEquals(200, new CivStore(directory).village(village.id).capitalExpansionLimit());
    }

    @Test void ordinaryMembersGetPromotionMessageForClaimsAndUpgrade() {
        claims.command(member, village, new String[]{"claim"});
        claims.command(member, village, new String[]{"claim", "mode"});
        interact(member, beacon);
        verify(member, times(3)).sendMessage(ClaimBuying.PROMOTION_MESSAGE);
        verify(member, never()).openInventory(any(Inventory.class));
        verify(wallets, never()).setBalance(any(), anyLong());
    }

    @Test void offhandAndNormalRightClicksNeverOpenOrBuyUpgrade() {
        when(elder.isSneaking()).thenReturn(false);
        interact(elder, beacon);
        when(elder.isSneaking()).thenReturn(true);
        PlayerInteractEvent offhand = mock(PlayerInteractEvent.class);
        when(offhand.getPlayer()).thenReturn(elder);
        when(offhand.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(offhand.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        when(offhand.getClickedBlock()).thenReturn(beacon);
        claims.click(offhand);
        verify(elder, never()).openInventory(any(Inventory.class));
        verify(wallets, never()).setBalance(any(), anyLong());
    }

    @ParameterizedTest @ValueSource(strings = {"demote", "leave", "disband", "move", "creative", "missingBeacon"})
    void confirmationRechecksRoleMembershipVillageAndBeacon(String change) {
        openUpgrade(elder);
        switch (change) {
            case "demote" -> store.setElder(village, elder.getUniqueId(), false);
            case "leave" -> store.removeMember(village, elder.getUniqueId());
            case "disband" -> store.disband(village);
            case "move" -> when(elder.getEyeLocation()).thenReturn(new Location(world, 100, 80, 100));
            case "creative" -> when(elder.getGameMode()).thenReturn(GameMode.CREATIVE);
            case "missingBeacon" -> when(beacon.getType()).thenReturn(Material.AIR);
        }
        choose(elder, 11);
        assertFalse(village.expandedClaims);
        verify(wallets, never()).setBalance(any(), anyLong());
    }

    @Test void confirmationUsesCurrentBalanceAndCannotOverdraw() {
        openUpgrade(elder);
        when(wallets.balance(elder)).thenReturn(99_999L);
        choose(elder, 11);
        assertFalse(village.expandedClaims);
        verify(wallets, never()).setBalance(any(), anyLong());
    }

    @Test void twoOpenMenusCannotPurchaseTwice() {
        openUpgrade(owner);
        openUpgrade(elder);
        choose(owner, 11);
        choose(elder, 11);
        verify(wallets).setBalance(owner, 50_000);
        verify(wallets, never()).setBalance(eq(elder), anyLong());
        assertEquals(200, village.capitalExpansionLimit());
    }

    @Test void menuItemsCannotBeDraggedIntoPlayerInventory() {
        openUpgrade(elder);
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        InventoryView view = elder.getOpenInventory();
        when(event.getView()).thenReturn(view);
        claims.menuDrag(event);
        verify(event).setCancelled(true);
    }

    @Test void elderCanBuyAfterUpgradeAndBothCapacityLimitsAreEnforced() {
        for (int x = 1; x <= 100; x++) {
            store.addPlot(village, new CivStore.Plot(world.getUID(), x, 0));
        }
        when(elder.getLocation()).thenReturn(new Location(world, 101 * 16 + 1, 80, 1));
        claims.command(elder, village, new String[]{"claim"});
        assertEquals(100, village.capitalExpansionCount());
        verify(wallets, never()).setBalance(any(), anyLong());
        openUpgrade(elder);
        choose(elder, 11);
        claims.command(elder, village, new String[]{"claim"});
        assertEquals(101, village.capitalExpansionCount());
        verify(wallets).setBalance(elder, 149_000);
        for (int x = 102; x <= 200; x++) {
            store.addPlot(village, new CivStore.Plot(world.getUID(), x, 0));
        }
        when(elder.getLocation()).thenReturn(new Location(world, 201 * 16 + 1, 80, 1));
        claims.command(elder, village, new String[]{"claim"});
        assertEquals(200, village.capitalExpansionCount());
        verify(wallets, times(2)).setBalance(eq(elder), anyLong());
    }

    @Test void demotionRevokesActiveClaimBuyingMode() {
        claims.command(elder, village, new String[]{"claim", "mode"});
        store.setElder(village, elder.getUniqueId(), false);
        Block ground = mock(Block.class);
        when(ground.getType()).thenReturn(Material.STONE);
        interact(elder, ground);
        verify(elder).sendMessage(ClaimBuying.PROMOTION_MESSAGE);
        verify(wallets, never()).setBalance(any(), anyLong());
    }
}
