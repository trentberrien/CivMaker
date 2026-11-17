package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProtectionChestAccessTest {
    @TempDir Path directory;
    private CivStore store;
    private RaidEquipment equipment;
    private NaturalVillages natural;
    private Protection protection;
    private World world;
    private Village village;
    private Player visitor;
    private Block left;
    private Block right;
    private Inventory inventory;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        equipment = mock(RaidEquipment.class);
        natural = mock(NaturalVillages.class);
        protection = new Protection(store, equipment, mock(VaultDoors.class), natural,
                mock(DefenderGolems.class), mock(FarmStations.class));
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        village = new Village("Ashford", UUID.randomUUID(), new Position(world.getUID(), 0, 80, 0));
        store.addVillage(village);
        visitor = mock(Player.class);
        when(visitor.getUniqueId()).thenReturn(UUID.randomUUID());
        inventory = mock(Inventory.class);
        left = chest(4);
        right = chest(5);
        Chest holder = (Chest) left.getState(false);
        when(inventory.getHolder()).thenReturn(holder);
    }

    private Block chest(int x) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.CHEST);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(80);
        when(block.getZ()).thenReturn(4);
        Chest chest = mock(Chest.class);
        when(chest.getBlock()).thenReturn(block);
        when(chest.getInventory()).thenReturn(inventory);
        when(block.getState(false)).thenReturn(chest);
        return block;
    }

    private void doubleChest() {
        DoubleChest holder = mock(DoubleChest.class);
        Chest first = (Chest) left.getState(false);
        Chest second = (Chest) right.getState(false);
        when(holder.getLeftSide()).thenReturn(first);
        when(holder.getRightSide()).thenReturn(second);
        when(inventory.getHolder()).thenReturn(holder);
    }

    private void assertAccess(boolean allowed) {
        for (Block block : List.of(left, right)) {
            PlayerInteractEvent click = mock(PlayerInteractEvent.class);
            when(click.getClickedBlock()).thenReturn(block);
            when(click.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
            when(click.getPlayer()).thenReturn(visitor);
            protection.interact(click);
            if (allowed) {
                verify(click, never()).setCancelled(true);
                verify(click, never()).setUseInteractedBlock(Event.Result.DENY);
            } else {
                verify(click).setCancelled(true);
            }
        }
        InventoryOpenEvent open = mock(InventoryOpenEvent.class);
        when(open.getInventory()).thenReturn(inventory);
        when(open.getPlayer()).thenReturn(visitor);
        protection.open(open);
        if (allowed) verify(open, never()).setCancelled(true);
        else verify(open).setCancelled(true);
    }

    @ParameterizedTest
    @CsvSource({"capital,unaffiliated", "capital,enemy", "capital,member", "bell,unaffiliated", "natural,enemy"})
    void unsecuredChestsArePublicAcrossFactionsAndProtectedAreas(String area, String faction) {
        if (faction.equals("member")) store.addMember(village, visitor.getUniqueId());
        if (faction.equals("enemy")) {
            store.addVillage(new Village("Other", visitor.getUniqueId(), new Position(world.getUID(), 64, 80, 0)));
        }
        if (area.equals("bell")) when(natural.inBellProtection(any())).thenReturn(true);
        if (area.equals("natural")) {
            store.disband(village);
            when(natural.reserved(eq(world), any())).thenReturn(true);
        }
        for (Material type : List.of(Material.CHEST, Material.TRAPPED_CHEST)) {
            when(left.getType()).thenReturn(type);
            when(right.getType()).thenReturn(type);
            assertAccess(true);
            doubleChest();
            assertAccess(true);
            Chest holder = (Chest) left.getState(false);
            when(inventory.getHolder()).thenReturn(holder);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"secureLeft", "secureRight", "treasuryLeft", "treasuryRight", "settlement"})
    void securingEitherHalfKeepsTheEntireDoubleChestPrivate(String kind) {
        doubleChest();
        Block secured = kind.endsWith("Right") ? right : left;
        if (kind.startsWith("secure")) when(equipment.isVault(secured)).thenReturn(true);
        else if (kind.startsWith("treasury")) village.treasuries.add(Position.at(secured));
        else {
            Position bell = new Position(world.getUID(), 32, 80, 4);
            Position vault = Position.at(left);
            var settlement = new CivStore.Settlement("minecraft:village_plains", bell, Set.of(bell.plot(), vault.plot()));
            settlement.vault().position = vault;
            store.addSettlement(village, settlement);
        }
        assertAccess(false);
    }

    @Test void publicAccessDoesNotPermitBreakingChestsOrOpeningOtherContainers() {
        BlockBreakEvent breaking = new BlockBreakEvent(left, visitor);
        protection.breakBlock(breaking);
        assertTrue(breaking.isCancelled());
        when(left.getType()).thenReturn(Material.BARREL);
        when(right.getType()).thenReturn(Material.BARREL);
        assertAccess(false);
    }
}
