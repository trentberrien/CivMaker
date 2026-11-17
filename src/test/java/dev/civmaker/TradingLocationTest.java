package dev.civmaker;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradingLocationTest {
    @TempDir Path directory;
    private CivStore store;
    private Trading trading;
    private World world;
    private Player first;
    private Player second;
    private final List<Runnable> tasks = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.namespace()).thenReturn("civmaker");
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(call -> { tasks.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        trading = new Trading(plugin, store);
        world = world();
        first = player("First", 0, 0);
        second = player("Second", 1600, 0);
        when(server.getPlayerExact("First")).thenReturn(first);
        when(server.getPlayerExact("Second")).thenReturn(second);
    }

    private World world() {
        World result = mock(World.class);
        when(result.getUID()).thenReturn(UUID.randomUUID());
        return result;
    }

    private Player player(String name, double x, double z) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.canSee(any(Player.class))).thenReturn(true);
        when(player.getClientViewDistance()).thenReturn(10);
        when(player.getSendViewDistance()).thenReturn(10);
        when(player.getLocation()).thenReturn(new Location(world, x, 80, z));
        when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        PlayerInventory storage = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(storage);
        when(storage.getStorageContents()).thenReturn(new ItemStack[36]);
        when(storage.getMaxStackSize()).thenReturn(64);
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            when(view.getTopInventory()).thenReturn(call.getArgument(0));
            return view;
        });
        return player;
    }

    private CivStore.Village claim(Player owner) {
        Location location = owner.getLocation();
        var village = new CivStore.Village(owner.getName(), owner.getUniqueId(),
                new CivStore.Position(location.getWorld().getUID(), location.getBlockX(), 80, location.getBlockZ()));
        store.addVillage(village);
        return village;
    }

    @Test void bothInOwnClaimsCanTradeRemotely() {
        claim(first);
        claim(second);
        assertTrue(trading.locationsAllowTrade(first, second));
    }

    @Test void ordinaryFactionMembersCanTradeFromTheirClaims() {
        var village = claim(first);
        Player member = player("Member", 0, 0);
        store.addMember(village, member.getUniqueId());
        claim(second);
        assertTrue(trading.locationsAllowTrade(member, second));
    }

    @Test void purchasedSettlementAndExpansionPlotsCountAsOwnLand() {
        var village = claim(first);
        claim(second);
        var settlementPlot = new CivStore.Plot(world.getUID(), -100, 0);
        store.addSettlement(village, new CivStore.Settlement("minecraft:village_plains",
                new CivStore.Position(world.getUID(), -1600, 80, 0), Set.of(settlementPlot)));
        when(first.getLocation()).thenReturn(new Location(world, -1600, 80, 0));
        assertTrue(trading.locationsAllowTrade(first, second));
        store.addPlot(village, new CivStore.Plot(world.getUID(), -101, 0));
        when(first.getLocation()).thenReturn(new Location(world, -1616, 80, 0));
        assertTrue(trading.locationsAllowTrade(first, second));
    }

    @Test void bothMustBeOnTheirOwnLandForRemoteTrading() {
        claim(first);
        assertFalse(trading.locationsAllowTrade(first, second));
        claim(second);
        Location secondLocation = second.getLocation();
        when(first.getLocation()).thenReturn(secondLocation);
        when(second.getLocation()).thenReturn(new Location(world, 0, 80, 0));
        assertFalse(trading.locationsAllowTrade(first, second));
    }

    @Test void unclaimedLandDoesNotCountAsOwnLand() {
        assertFalse(trading.locationsAllowTrade(first, second));
    }

    @Test void closePlayersCanTradeWithoutFactionsRegardlessOfHeight() {
        when(second.getLocation()).thenReturn(new Location(world, 160, -60, 160));
        assertTrue(trading.locationsAllowTrade(first, second));
        when(second.getLocation()).thenReturn(new Location(world, 176, 80, 0));
        assertFalse(trading.locationsAllowTrade(first, second));
    }

    @Test void bothClientAndServerSendingDistancesLimitNearbyTrading() {
        when(second.getLocation()).thenReturn(new Location(world, 80, 80, 0));
        assertTrue(trading.locationsAllowTrade(first, second));
        when(second.getClientViewDistance()).thenReturn(4);
        assertFalse(trading.locationsAllowTrade(first, second));
        when(second.getClientViewDistance()).thenReturn(10);
        when(first.getSendViewDistance()).thenReturn(4);
        assertFalse(trading.locationsAllowTrade(first, second));
    }

    @Test void negativeCoordinatesRespectChunkBoundaries() {
        when(first.getLocation()).thenReturn(new Location(world, -0.1, 80, 0));
        when(second.getLocation()).thenReturn(new Location(world, 159.9, 80, 0));
        assertTrue(trading.locationsAllowTrade(first, second));
        when(second.getLocation()).thenReturn(new Location(world, 160, 80, 0));
        assertFalse(trading.locationsAllowTrade(first, second));
    }

    @Test void separateWorldsRequireBothPlayersToBeInOwnClaims() {
        World otherWorld = world();
        when(second.getLocation()).thenReturn(new Location(otherWorld, 0, 80, 0));
        assertFalse(trading.locationsAllowTrade(first, second));
        claim(first);
        claim(second);
        assertTrue(trading.locationsAllowTrade(first, second));
    }

    private CivStore.Village enemyClaim(Player visitor) {
        Location location = visitor.getLocation();
        var village = new CivStore.Village("Enemy", UUID.randomUUID(),
                new CivStore.Position(location.getWorld().getUID(), location.getBlockX(), 80, location.getBlockZ()));
        store.addVillage(village);
        return village;
    }

    @ParameterizedTest
    @EnumSource(value = CivStore.RaidMode.class, names = {"ON", "SETTLEMENTS"})
    void enemyClaimBlocksNearbyTradingDuringEitherManualRaidMode(CivStore.RaidMode mode) {
        when(second.getLocation()).thenReturn(new Location(world, 16, 80, 0));
        enemyClaim(first);
        store.setRaidMode(mode);
        assertFalse(trading.locationsAllowTrade(first, second));
        assertFalse(trading.locationsAllowTrade(second, first));
        command(first, "Second");
        verify(first).sendMessage(contains("enemy faction's claim during a raid period"));
        verify(second, never()).sendMessage(any(Component.class));
    }

    @Test void scheduledRaidPeriodBlocksEnemyLandUntilItEnds() {
        when(second.getLocation()).thenReturn(new Location(world, 16, 80, 0));
        enemyClaim(first);
        long now = System.currentTimeMillis();
        store.windows().add(new CivStore.Window(now - 60_000, now + 60_000));
        assertFalse(trading.locationsAllowTrade(first, second));
        store.windows().clear();
        store.windows().add(new CivStore.Window(now - 120_000, now - 60_000));
        assertTrue(trading.locationsAllowTrade(first, second));
    }

    @Test void raidOffAllowsNearbyVisitorsOnAnotherFactionsLand() {
        when(second.getLocation()).thenReturn(new Location(world, 16, 80, 0));
        enemyClaim(first);
        store.setRaidMode(CivStore.RaidMode.OFF);
        assertTrue(trading.locationsAllowTrade(first, second));
    }

    @Test void ownClaimsAndNearbyWildernessRemainAvailableDuringRaids() {
        store.setRaidMode(CivStore.RaidMode.ON);
        when(second.getLocation()).thenReturn(new Location(world, 16, 80, 0));
        assertTrue(trading.locationsAllowTrade(first, second));
        claim(first);
        when(second.getLocation()).thenReturn(new Location(world, 1600, 80, 0));
        claim(second);
        assertTrue(trading.locationsAllowTrade(first, second));
    }

    @Test void enemySettlementBlocksNearbyTradingDuringRaid() {
        var enemy = enemyClaim(first);
        var settlementPlot = new CivStore.Plot(world.getUID(), 2, 0);
        store.addSettlement(enemy, new CivStore.Settlement("minecraft:village_plains",
                new CivStore.Position(world.getUID(), 32, 80, 0), Set.of(settlementPlot)));
        when(first.getLocation()).thenReturn(new Location(world, 32, 80, 0));
        when(second.getLocation()).thenReturn(new Location(world, 48, 80, 0));
        store.setRaidMode(CivStore.RaidMode.ON);
        assertFalse(trading.locationsAllowTrade(first, second));
    }

    @Test void raidStartingBeforeAcceptanceRejectsPendingRequest() {
        when(second.getLocation()).thenReturn(new Location(world, 16, 80, 0));
        enemyClaim(first);
        command(first, "Second");
        store.setRaidMode(CivStore.RaidMode.ON);
        command(second, "First");
        verify(second).sendMessage(contains("enemy faction's claim during a raid period"));
        verify(first, never()).openInventory(any(Inventory.class));
        verify(second, never()).openInventory(any(Inventory.class));
    }

    @Test void raidRestrictionBlocksTestTradeButStillAllowsItemRecovery() {
        enemyClaim(first);
        store.setRaidMode(CivStore.RaidMode.ON);
        when(first.hasPermission("civmaker.trade.test")).thenReturn(true);
        command(first, "test");
        verify(first).sendMessage(contains("enemy faction's claim during a raid period"));
        verify(first, never()).openInventory(any(Inventory.class));
        command(first, "--recover");
        verify(first).sendMessage("You have no trade items to recover.");
    }

    private void command(Player player, String target) {
        assertTrue(trading.onCommand(player, null, "trade", new String[]{target}));
    }

    @Test void invalidRequestIsRejectedBeforeSendingInvitation() {
        command(first, "Second");
        verify(first).sendMessage(contains("within render distance"));
        verify(second, never()).sendMessage(any(Component.class));
    }

    @Test void pendingRequestIsRecheckedWhenAccepted() {
        when(second.getLocation()).thenReturn(new Location(world, 16, 80, 0));
        command(first, "Second");
        when(second.getLocation()).thenReturn(new Location(world, 1600, 80, 0));
        command(second, "First");
        verify(second).sendMessage(contains("within render distance"));
        verify(first, never()).openInventory(any(Inventory.class));
        verify(second, never()).openInventory(any(Inventory.class));
    }

    private void start() {
        when(second.getLocation()).thenReturn(new Location(world, 16, 80, 0));
        try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(45), any(Component.class)))
                    .thenAnswer(call -> {
                        Inventory inventory = mock(Inventory.class);
                        when(inventory.getHolder()).thenReturn(call.getArgument(0));
                        when(inventory.getSize()).thenReturn(45);
                        return inventory;
                    });
            command(first, "Second");
            command(second, "First");
        }
    }

    private void confirm(Player player) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        Inventory top = player.getOpenInventory().getTopInventory();
        when(event.getInventory()).thenReturn(top);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getRawSlot()).thenReturn(22);
        try (var items = mockConstruction(ItemStack.class)) {
            trading.click(event);
        }
        verify(event).setCancelled(true);
    }

    @Test void movingOutOfRangeBeforeConfirmingCancelsBothSides() {
        start();
        when(second.getLocation()).thenReturn(new Location(world, 1600, 80, 0));
        confirm(first);
        verify(first).sendMessage("Trade cancelled.");
        verify(second).sendMessage("Trade cancelled.");
        tasks.removeFirst().run();
        verify(first).closeInventory();
        verify(second).closeInventory();
    }

    @Test void movingAfterConfirmationCannotBypassCompletionCheck() {
        start();
        confirm(first);
        confirm(second);
        assertEquals(1, tasks.size());
        when(second.getLocation()).thenReturn(new Location(world, 1600, 80, 0));
        tasks.removeFirst().run();
        verify(first).sendMessage("Trade cancelled.");
        verify(second).sendMessage("Trade cancelled.");
        verify(first.getInventory(), never()).setStorageContents(any());
        verify(second.getInventory(), never()).setStorageContents(any());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void eitherPlayerEnteringEnemyLandDuringRaidCancelsTrade(boolean firstMoves) {
        claim(first);
        claim(second);
        start();
        enemyClaim(player("EnemyOwner", 32, 0));
        store.setRaidMode(CivStore.RaidMode.ON);
        Player moving = firstMoves ? first : second;
        when(moving.getLocation()).thenReturn(new Location(world, 32, 80, 0));
        confirm(first);
        verify(first).sendMessage("Trade cancelled.");
        verify(second).sendMessage("Trade cancelled.");
        verify(first).sendMessage(contains("enemy faction's claim during a raid period"));
    }

    @Test void raidStartingAfterConfirmationCannotBypassCompletionCheck() {
        start();
        enemyClaim(first);
        confirm(first);
        confirm(second);
        store.setRaidMode(CivStore.RaidMode.ON);
        tasks.removeFirst().run();
        verify(first).sendMessage("Trade cancelled.");
        verify(second).sendMessage("Trade cancelled.");
        verify(first.getInventory(), never()).setStorageContents(any());
        verify(second.getInventory(), never()).setStorageContents(any());
    }

    @Test void locationCancellationReturnsEscrowToItsOwner() {
        start();
        byte[] saved = {1};
        when(first.getPersistentDataContainer().get(any(), eq(PersistentDataType.BYTE_ARRAY))).thenReturn(saved);
        ItemStack offered = mock(ItemStack.class);
        when(offered.getAmount()).thenReturn(3);
        when(offered.getMaxStackSize()).thenReturn(64);
        when(offered.asQuantity(3)).thenReturn(offered);
        when(second.getLocation()).thenReturn(new Location(world, 1600, 80, 0));
        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeItemsFromBytes(saved)).thenReturn(new ItemStack[]{offered});
            confirm(first);
        }
        verify(first.getInventory()).setStorageContents(argThat(contents -> contents[0] == offered));
        verify(second.getInventory(), never()).setStorageContents(any());
        verify(first).sendMessage("Trade cancelled.");
    }

    @Test void losingClaimBeforeCompletionCancelsRemoteTrade() {
        var firstVillage = claim(first);
        claim(second);
        start();
        when(second.getLocation()).thenReturn(new Location(world, 1600, 80, 0));
        confirm(first);
        confirm(second);
        store.removePlot(firstVillage, CivStore.Plot.at(first.getLocation()));
        tasks.removeFirst().run();
        verify(first).sendMessage("Trade cancelled.");
        verify(second).sendMessage("Trade cancelled.");
    }

    @Test void validNearbyTradeStillCompletes() {
        start();
        confirm(first);
        confirm(second);
        tasks.removeFirst().run();
        verify(first).sendMessage("Trade complete.");
        verify(second).sendMessage("Trade complete.");
        verify(first).closeInventory();
        verify(second).closeInventory();
    }
}
