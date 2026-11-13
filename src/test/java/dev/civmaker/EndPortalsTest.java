package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EndPortalsTest {
    @TempDir Path directory;
    private CivStore store;
    private EndPortals portals;
    private Wallets wallets;
    private Village village;
    private Player member;
    private Villager keeper;
    private World overworld;
    private World end;
    private final List<Runnable> tasks = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(call -> { tasks.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        wallets = mock(Wallets.class);
        portals = new EndPortals(plugin, store, wallets);
        overworld = mock(World.class);
        when(overworld.getUID()).thenReturn(UUID.randomUUID());
        when(overworld.getEnvironment()).thenReturn(World.Environment.NORMAL);
        end = mock(World.class);
        when(end.getEnvironment()).thenReturn(World.Environment.THE_END);
        member = mock(Player.class);
        when(member.getUniqueId()).thenReturn(UUID.randomUUID());
        when(member.isOnline()).thenReturn(true);
        when(wallets.balance(member)).thenReturn(1_000_000L);
        village = new Village("Test", UUID.randomUUID(), new Position(overworld.getUID(), 0, 80, 0));
        store.addVillage(village);
        store.addMember(village, member.getUniqueId());
        // Four settlements plus the capital reach the existing 500-point threshold.
        for (int i = 1; i <= 4; i++) {
            Plot plot = new Plot(overworld.getUID(), i, 0);
            village.settlements.add(new CivStore.Settlement("minecraft:village_plains",
                    new Position(overworld.getUID(), i * 16, 80, 0), java.util.Set.of(plot)));
            store.addPlot(village, plot);
        }
        assertEquals(500, village.points());
        keeper = mock(Villager.class);
        when(keeper.getUniqueId()).thenReturn(UUID.randomUUID());
        // Register a discovered keeper without depending on stronghold generation.
        Class<?> keeperType = Class.forName(EndPortals.class.getName() + "$Keeper");
        var constructor = keeperType.getDeclaredConstructor(Position.class, Villager.class);
        constructor.setAccessible(true);
        Object entry = constructor.newInstance(new Position(overworld.getUID(), 100, 30, 100), keeper);
        var field = EndPortals.class.getDeclaredField("keepers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID, Object> keepers = (Map<UUID, Object>) field.get(portals);
        keepers.put(keeper.getUniqueId(), entry);
    }

    private void talk() {
        PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
        when(event.getPlayer()).thenReturn(member);
        when(event.getRightClicked()).thenReturn(keeper);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        portals.interact(event);
        verify(event).setCancelled(true);
    }

    private Inventory openMenu() {
        Inventory inventory = mock(Inventory.class);
        try (var bukkit = mockStatic(Bukkit.class); var icons = mockConstruction(ItemStack.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(Component.class)))
                    .thenAnswer(call -> {
                        when(inventory.getHolder()).thenReturn(call.getArgument(0));
                        return inventory;
                    });
            talk();
            verify(member).openInventory(inventory);
        }
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(member.getOpenInventory()).thenReturn(view);
        return inventory;
    }

    @Test void pointsAndMoneyCannotUnlockTheEndWithoutAnUpgradedBeacon() {
        talk();
        verify(member, never()).openInventory(any(Inventory.class));
        verify(member).sendMessage(contains("Upgrade your capital beacon"));
        verify(wallets, never()).setBalance(any(), anyLong());
        assertFalse(village.endUnlocked);
    }

    @Test void anUpgradedBeaconAndExistingRequirementsAllowAnyMemberToOpenTheMenu() {
        village.expandedClaims = true;
        openMenu();
        assertFalse(village.canManage(member.getUniqueId()));
        assertFalse(village.endUnlocked);
        verify(wallets, never()).setBalance(any(), anyLong());
    }

    @ParameterizedTest @ValueSource(strings = {"points", "money", "membership"})
    void theUpgradeDoesNotReplaceExistingEndRequirements(String missing) {
        village.expandedClaims = true;
        switch (missing) {
            case "points" -> village.settlements.clear();
            case "money" -> when(wallets.balance(member)).thenReturn(999_999L);
            case "membership" -> store.removeMember(village, member.getUniqueId());
        }
        talk();
        verify(member, never()).openInventory(any(Inventory.class));
        verify(wallets, never()).setBalance(any(), anyLong());
    }

    @Test void confirmationRechecksTheBeaconUpgradeBeforePayment() {
        village.expandedClaims = true;
        openMenu();
        village.expandedClaims = false;
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = member.getOpenInventory();
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(member);
        when(event.getRawSlot()).thenReturn(13);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        portals.click(event);
        tasks.removeFirst().run();
        assertFalse(village.endUnlocked);
        verify(wallets, never()).setBalance(any(), anyLong());
        verify(member).sendMessage(contains("Upgrade your capital beacon"));
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void enteringAnActivePortalRequiresBothTheUpgradeAndFactionUnlock(boolean upgraded, boolean unlocked) {
        village.expandedClaims = upgraded;
        village.endUnlocked = unlocked;
        EntityPortalEnterEvent enter = mock(EntityPortalEnterEvent.class);
        when(enter.getPortalType()).thenReturn(PortalType.ENDER);
        when(enter.getLocation()).thenReturn(new Location(overworld, 0, 30, 0));
        when(enter.getEntity()).thenReturn(member);
        portals.enter(enter);
        PlayerTeleportEvent teleport = mock(PlayerTeleportEvent.class);
        when(teleport.getPlayer()).thenReturn(member);
        when(teleport.getCause()).thenReturn(PlayerTeleportEvent.TeleportCause.END_PORTAL);
        when(teleport.getFrom()).thenReturn(new Location(overworld, 0, 30, 0));
        when(teleport.getTo()).thenReturn(new Location(end, 0, 60, 0));
        portals.teleport(teleport);
        int blocked = upgraded && unlocked ? 0 : 1;
        verify(enter, times(blocked)).setCancelled(true);
        verify(teleport, times(blocked)).setCancelled(true);
    }

    @Test void anExistingUnlockStillRequiresTheBeaconUpgradeToOpenAPortal() {
        village.endUnlocked = true;
        talk();
        verify(member).sendMessage(contains("Upgrade your capital beacon"));
        verify(wallets, never()).setBalance(any(), anyLong());
        assertTrue(village.endUnlocked, "Keep the paid unlock so another End payment is unnecessary");
    }

    @Test void playersCanLeaveTheEndWithoutAnUpgradedBeacon() {
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        when(event.getCause()).thenReturn(PlayerTeleportEvent.TeleportCause.END_PORTAL);
        when(event.getFrom()).thenReturn(new Location(end, 0, 60, 0));
        portals.teleport(event);
        verify(event, never()).setCancelled(true);
    }
}
