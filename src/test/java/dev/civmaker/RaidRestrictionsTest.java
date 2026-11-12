package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Settlement;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaidRestrictionsTest {
    private CivMaker plugin;
    private CivStore store;
    private World world;
    private Player player;
    private Village defender;
    private Village attacker;
    private Position bell;

    @BeforeEach void setup() {
        plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        store = mock(CivStore.class);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        bell = new Position(world.getUID(), 0, 80, 0);
        defender = new Village("Defenders", UUID.randomUUID(), bell);
        attacker = new Village("Attackers", UUID.randomUUID(), new Position(world.getUID(), 32, 80, 0));
        player = player(attacker.owner);
        when(store.memberOf(player.getUniqueId())).thenReturn(attacker);
    }

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 80.5, 0.5));
        when(player.getWorld()).thenReturn(world);
        return player;
    }

    @ParameterizedTest
    @EnumSource(value = RaidEquipment.Tool.class, names = {"LOCKPICK", "RAM", "C4", "GRENADE"})
    void restrictedPlayersCannotStartOffensiveToolsOrConsumeTheirItems(RaidEquipment.Tool tool) {
        RaidEquipment equipment = mock(RaidEquipment.class);
        Protection protection = mock(Protection.class);
        RaidTools tools = new RaidTools(plugin, store, equipment, protection, mock(VaultDoors.class),
                mock(PillagerGuards.class), mock(DefenderGolems.class), mock(SettlementVaults.class));
        ItemStack held = mock(ItemStack.class);
        when(equipment.identify(held)).thenReturn(tool);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(store.raidRestricted(player.getUniqueId())).thenReturn(true);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, held,
                null, org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND);
        tools.use(event);
        verify(player).sendMessage(contains("You cannot raid"));
        verify(held, never()).setAmount(anyInt());
        verifyNoInteractions(protection);
        verify(world, never()).spawn(any(Location.class), any(Class.class), any(java.util.function.Consumer.class));
    }

    @Test void becomingRestrictedInvalidatesAnExistingLockpickOrLootSession() throws Exception {
        RaidTools tools = new RaidTools(plugin, store, mock(RaidEquipment.class), mock(Protection.class),
                mock(VaultDoors.class), mock(PillagerGuards.class), mock(DefenderGolems.class), mock(SettlementVaults.class));
        Window active = new Window(1, Long.MAX_VALUE);
        when(store.activeWindow(anyLong())).thenReturn(active);
        when(store.raidCovers(any())).thenReturn(true);
        Method reach = RaidTools.class.getDeclaredMethod("canReachDuringRaid", Player.class, Location.class, Village.class, Window.class);
        reach.setAccessible(true);
        assertEquals(true, reach.invoke(tools, player, player.getLocation(), defender, active));
        when(store.raidRestricted(player.getUniqueId())).thenReturn(true);
        assertEquals(false, reach.invoke(tools, player, player.getLocation(), defender, active));
    }

    @Test void protectedFactionCannotStartCaptureOrContestAnotherAttacker() throws Exception {
        SettlementCapture capture = new SettlementCapture(plugin, store, mock(DefenderGolems.class));
        Settlement settlement = new Settlement(null, bell, Set.of(bell.plot()));
        Location center = new Location(world, 0.5, 80.5, 0.5);
        when(store.raidRestricted(player.getUniqueId())).thenReturn(true);
        Method update = SettlementCapture.class.getDeclaredMethod("updateFight", Settlement.class, Village.class, Location.class, List.class);
        update.setAccessible(true);
        var field = SettlementCapture.class.getDeclaredField("fights");
        field.setAccessible(true);
        Map<?, ?> fights = (Map<?, ?>) field.get(capture);
        update.invoke(capture, settlement, defender, center, List.of(player));
        assertTrue(fights.isEmpty());
        verify(plugin, never()).discordRaidAlert(any(), any(), any(), any());

        Village third = new Village("Third", UUID.randomUUID(), new Position(world.getUID(), 64, 80, 0));
        Player raider = player(third.owner);
        when(store.memberOf(raider.getUniqueId())).thenReturn(third);
        when(world.getPlayers()).thenReturn(List.of(player, raider));
        when(store.hasVillage(third.id)).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            update.invoke(capture, settlement, defender, center, List.of(player, raider));
            Object fight = fights.get(bell);
            assertNotNull(fight);
            var progress = fight.getClass().getDeclaredField("progress");
            progress.setAccessible(true);
            assertEquals(1, progress.getInt(fight));
            update.invoke(capture, settlement, defender, center, List.of(player, raider));
            assertEquals(2, progress.getInt(fight));
        }
    }
}
