package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

class VaultTrackingLabelsTest {
    private CivMaker plugin;
    private CivStore store;
    private RaidEquipment equipment;
    private VaultTracking tracking;
    private World world;
    private Block block;
    private Village village;
    private Plot plot;
    private Player member;
    private Player enemy;
    private Player unaffiliated;
    private final List<TextDisplay> displays = new ArrayList<>();

    @BeforeEach void setup() {
        plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        store = mock(CivStore.class);
        equipment = mock(RaidEquipment.class);
        Notes notes = mock(Notes.class);
        tracking = new VaultTracking(plugin, store, equipment, notes);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(server.getWorlds()).thenReturn(List.of(world));
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
        village = new Village("Owners", UUID.randomUUID(), new Position(world.getUID(), 0, 80, 0));
        plot = village.beacon.plot();
        when(store.claimAt(plot)).thenReturn(village);
        when(store.raidCovers(plot)).thenReturn(true);
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getY()).thenReturn(80);
        when(store.claimAt(block)).thenReturn(village);
        when(equipment.isVault(block)).thenReturn(true);
        Chest chest = mock(Chest.class);
        when(chest.getBlock()).thenReturn(block);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(chest);
        when(chest.getInventory()).thenReturn(inventory);
        when(chest.getBlockInventory()).thenReturn(inventory);
        when(notes.value(inventory, Integer.MAX_VALUE)).thenReturn(25L);
        when(chunk.getTileEntities(false)).thenReturn(new BlockState[]{chest});
        member = player(village.owner);
        enemy = player(UUID.randomUUID());
        unaffiliated = player(UUID.randomUUID());
        Village other = new Village("Others", enemy.getUniqueId(), new Position(world.getUID(), 32, 80, 0));
        when(store.memberOf(member.getUniqueId())).thenReturn(village);
        when(store.memberOf(enemy.getUniqueId())).thenReturn(other);
        when(world.getPlayers()).thenReturn(List.of(member, enemy, unaffiliated));
        when(world.spawn(any(Location.class), eq(TextDisplay.class),
                org.mockito.ArgumentMatchers.<Consumer<TextDisplay>>any())).thenAnswer(call -> {
            TextDisplay display = mock(TextDisplay.class);
            when(display.isValid()).thenReturn(true);
            displays.add(display);
            call.<Consumer<TextDisplay>>getArgument(2).accept(display);
            return display;
        });
    }

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private void assertView(Player viewer, boolean throughWalls) {
        TextDisplay shown = displays.get(throughWalls ? 1 : 0);
        TextDisplay hidden = displays.get(throughWalls ? 0 : 1);
        verify(viewer).showEntity(plugin, shown);
        verify(viewer).hideEntity(plugin, hidden);
        verify(viewer, never()).showEntity(plugin, hidden);
    }

    @Test void outsideRaidsEveryoneNeedsAClearView() {
        tracking.refresh();

        assertView(member, false);
        assertView(enemy, false);
        assertView(unaffiliated, false);
        verify(displays.get(0)).setSeeThrough(false);
        verify(displays.get(1)).setSeeThrough(true);
        for (TextDisplay display : displays) {
            verify(display).setVisibleByDefault(false);
            verify(display).setPersistent(false);
            verify(display).text(Component.text("Secure Vault", NamedTextColor.GREEN));
        }
        verify(store).updateVaultBalance(plot, 25L);
    }

    @Test void raidStartAndEndSwitchExistingLabelsForOutsiders() {
        tracking.refresh();
        clearInvocations(member, enemy, unaffiliated);
        when(store.activeWindow(anyLong())).thenReturn(new Window(1, Long.MAX_VALUE));
        tracking.refresh();

        assertView(member, false);
        assertView(enemy, true);
        assertView(unaffiliated, true);

        clearInvocations(member, enemy, unaffiliated);
        when(store.activeWindow(anyLong())).thenReturn(null);
        tracking.refresh();

        assertView(member, false);
        assertView(enemy, false);
        assertView(unaffiliated, false);
        verify(world, times(2)).spawn(any(Location.class), eq(TextDisplay.class),
                org.mockito.ArgumentMatchers.<Consumer<TextDisplay>>any());
    }

    @Test void protectedClaimsDoNotRevealVaultsToOutsidersDuringRaids() {
        when(store.activeWindow(anyLong())).thenReturn(new Window(1, Long.MAX_VALUE));
        when(store.raidCovers(plot)).thenReturn(false);
        tracking.refresh();

        assertView(member, false);
        assertView(enemy, false);
        assertView(unaffiliated, false);
    }

    @Test void joiningAndLeavingTheFactionUpdatesThroughWallAccess() {
        when(store.activeWindow(anyLong())).thenReturn(new Window(1, Long.MAX_VALUE));
        tracking.refresh();
        clearInvocations(unaffiliated);
        when(store.memberOf(unaffiliated.getUniqueId())).thenReturn(village);
        tracking.refresh();
        assertView(unaffiliated, false);

        clearInvocations(unaffiliated);
        when(store.memberOf(unaffiliated.getUniqueId())).thenReturn(null);
        tracking.refresh();
        assertView(unaffiliated, true);
    }

    @Test void newlyArrivingPlayersReceiveOnlyTheAllowedLabel() {
        tracking.refresh();
        Player newcomer = player(UUID.randomUUID());
        when(world.getPlayers()).thenReturn(List.of(member, enemy, unaffiliated, newcomer));
        tracking.refresh();
        assertView(newcomer, false);
    }

    @Test void removedVaultsAndShutdownRemoveBothLabelVariants() {
        tracking.refresh();
        when(equipment.isVault(block)).thenReturn(false);
        tracking.refresh();
        verify(displays.get(0)).remove();
        verify(displays.get(1)).remove();

        when(equipment.isVault(block)).thenReturn(true);
        tracking.refresh();
        tracking.clear();
        verify(displays.get(2)).remove();
        verify(displays.get(3)).remove();
    }

    @Test void invalidLabelReplacesBothVariantsWithoutLeavingAnOrphan() {
        tracking.refresh();
        when(displays.get(0).isValid()).thenReturn(false);
        tracking.refresh();

        verify(displays.get(0)).remove();
        verify(displays.get(1)).remove();
        verify(enemy).showEntity(plugin, displays.get(2));
        verify(enemy).hideEntity(plugin, displays.get(3));
    }

    @Test void balanceRefreshDoesNotCreateLabels() {
        tracking.refreshBalances();
        verify(store).updateVaultBalance(plot, 25L);
        verify(world, never()).spawn(any(Location.class), eq(TextDisplay.class),
                org.mockito.ArgumentMatchers.<Consumer<TextDisplay>>any());
    }
}
