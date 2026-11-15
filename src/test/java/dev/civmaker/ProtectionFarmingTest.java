package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProtectionFarmingTest {
    @TempDir Path directory;
    private CivStore store;
    private NaturalVillages natural;
    private Protection protection;
    private World world;
    private Block block;
    private Village village;
    private Player member;
    private Player outsider;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        natural = mock(NaturalVillages.class);
        protection = new Protection(store, mock(RaidEquipment.class), mock(VaultDoors.class),
                natural, mock(DefenderGolems.class), mock(FarmStations.class));
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        block = block(4, Material.FARMLAND);
        village = new Village("Ashford", UUID.randomUUID(), new Position(world.getUID(), 0, 80, 0));
        store.addVillage(village);
        member = mock(Player.class);
        when(member.getUniqueId()).thenReturn(village.owner);
        outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    private Block block(int x, Material type) {
        Block value = mock(Block.class);
        when(value.getWorld()).thenReturn(world);
        when(value.getX()).thenReturn(x);
        when(value.getY()).thenReturn(80);
        when(value.getZ()).thenReturn(4);
        when(value.getType()).thenReturn(type);
        return value;
    }

    private BlockState state(Material material) {
        BlockState state = mock(BlockState.class);
        when(state.getType()).thenReturn(material);
        return state;
    }

    private void region(String kind) {
        if (kind.equals("bell")) {
            when(natural.inBellProtection(block)).thenReturn(true);
        } else if (kind.equals("natural")) {
            store.disband(village);
            when(natural.reserved(eq(world), any())).thenReturn(true);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"capital", "bell", "natural"})
    void cropsCanGrowThroughoutProtectedAreas(String kind) {
        region(kind);
        for (Material crop : List.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
                Material.PUMPKIN_STEM, Material.MELON, Material.SWEET_BERRY_BUSH, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP)) {
            when(block.getType()).thenReturn(crop);
            BlockGrowEvent event = new BlockGrowEvent(block, state(crop));
            protection.grow(event);
            assertFalse(event.isCancelled(), crop + " in " + kind);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"capital", "bell", "natural"})
    void dryFarmlandCanReturnToDirtNaturally(String kind) {
        region(kind);
        BlockFadeEvent event = new BlockFadeEvent(block, state(Material.DIRT));
        protection.fade(event);
        assertFalse(event.isCancelled());
    }

    @ParameterizedTest @ValueSource(strings = {"capital", "bell", "natural"})
    void farmingPlantsCanSpreadWithinTheirProtectedArea(String kind) {
        region(kind);
        Block source = block(5, Material.CAVE_VINES);
        when(natural.inBellProtection(source)).thenReturn(kind.equals("bell"));
        when(block.getType()).thenReturn(Material.AIR);
        BlockSpreadEvent event = new BlockSpreadEvent(block, source, state(Material.CAVE_VINES));
        protection.spread(event);
        assertFalse(event.isCancelled());
    }

    @Test void membersCanTrampleTheirOwnFarmlandButOutsidersAndMobsCannot() {
        for (var actor : List.of(member, outsider, mock(Zombie.class))) {
            EntityChangeBlockEvent event = mock(EntityChangeBlockEvent.class);
            when(event.getBlock()).thenReturn(block);
            when(event.getTo()).thenReturn(Material.DIRT);
            when(event.getEntity()).thenReturn(actor);
            protection.entityChange(event);
            if (actor == member) verify(event, never()).setCancelled(true);
            else verify(event).setCancelled(true);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void onlyMembersCanBreakOrTrampleFarmingBlocksIncludingNearBells(boolean nearBell) {
        when(natural.inBellProtection(block)).thenReturn(nearBell);
        for (Material material : List.of(Material.WHEAT, Material.FARMLAND)) {
            when(block.getType()).thenReturn(material);
            BlockBreakEvent own = new BlockBreakEvent(block, member);
            protection.breakBlock(own);
            assertFalse(own.isCancelled());
            BlockBreakEvent foreign = new BlockBreakEvent(block, outsider);
            protection.breakBlock(foreign);
            assertTrue(foreign.isCancelled());
            PlayerInteractEvent trample = mock(PlayerInteractEvent.class);
            when(trample.getClickedBlock()).thenReturn(block);
            when(trample.getPlayer()).thenReturn(outsider);
            when(trample.getAction()).thenReturn(Action.PHYSICAL);
            protection.interact(trample);
            verify(trample).setCancelled(true);
        }
    }

    @Test void membersCanTillAndPlantNearTheirBellWhileOutsidersCannot() {
        when(natural.inBellProtection(block)).thenReturn(true);
        for (Player player : List.of(member, outsider)) {
            when(block.getType()).thenReturn(Material.DIRT);
            PlayerInteractEvent till = mock(PlayerInteractEvent.class);
            when(till.getClickedBlock()).thenReturn(block);
            when(till.getPlayer()).thenReturn(player);
            when(till.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
            ItemStack hoe = mock(ItemStack.class);
            when(hoe.getType()).thenReturn(Material.IRON_HOE);
            when(till.getItem()).thenReturn(hoe);
            protection.interact(till);
            if (player == member) {
                verify(till, never()).setUseItemInHand(Event.Result.DENY);
                verify(till, never()).setCancelled(true);
            } else verify(till).setCancelled(true);
            EntityBlockFormEvent form = new EntityBlockFormEvent(player, block, state(Material.FARMLAND));
            protection.form(form);
            assertEquals(player != member, form.isCancelled());
            when(block.getType()).thenReturn(Material.WHEAT);
            BlockPlaceEvent plant = mock(BlockPlaceEvent.class);
            when(plant.getPlayer()).thenReturn(player);
            when(plant.getBlockPlaced()).thenReturn(block);
            protection.place(plant);
            if (player == member) verify(plant, never()).setCancelled(true);
            else verify(plant).setCancelled(true);
        }
    }

    @Test void soilStateUpdatesCanProceedButSolidBlockPlacementStillCannotObstructTheBell() {
        when(natural.inBellProtection(block)).thenReturn(true);
        BlockFormEvent soil = new BlockFormEvent(block, state(Material.FARMLAND));
        protection.form(soil);
        assertFalse(soil.isCancelled());
        when(block.getType()).thenReturn(Material.COBBLESTONE);
        BlockPlaceEvent obstruction = mock(BlockPlaceEvent.class);
        when(obstruction.getPlayer()).thenReturn(member);
        when(obstruction.getBlockPlaced()).thenReturn(block);
        protection.place(obstruction);
        verify(obstruction).setCancelled(true);
    }

    @Test void outsidePlantsCannotGrowAcrossTheClaimBoundaryAndNonFarmProtectionRemains() {
        Block source = block(32, Material.CAVE_VINES);
        BlockSpreadEvent spread = new BlockSpreadEvent(block, source, state(Material.CAVE_VINES));
        protection.spread(spread);
        assertTrue(spread.isCancelled());
        when(natural.inBellProtection(block)).thenReturn(true);
        when(block.getType()).thenReturn(Material.ICE);
        BlockFadeEvent melt = new BlockFadeEvent(block, state(Material.WATER));
        protection.fade(melt);
        assertTrue(melt.isCancelled());
        BlockFormEvent snow = new BlockFormEvent(block, state(Material.SNOW));
        protection.form(snow);
        assertTrue(snow.isCancelled());
    }

    @Test void plantGrowthCannotObstructTheBeaconOrReplaceSettlementFixtures() {
        Block sky = block(0, Material.AIR);
        when(sky.getZ()).thenReturn(0);
        when(sky.getY()).thenReturn(81);
        BlockGrowEvent growAboveBeacon = new BlockGrowEvent(sky, state(Material.MELON));
        protection.grow(growAboveBeacon);
        assertTrue(growAboveBeacon.isCancelled());
        Block bell = block(32, Material.BELL);
        var position = Position.at(bell);
        store.addSettlement(village, new CivStore.Settlement("minecraft:village_plains", position, Set.of(position.plot())));
        BlockGrowEvent growOverBell = new BlockGrowEvent(bell, state(Material.WHEAT));
        protection.grow(growOverBell);
        assertTrue(growOverBell.isCancelled());
    }
}
