package dev.civmaker;

import dev.civmaker.RaidEquipment.Tool;
import dev.civmaker.SettlementResources.Reward;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.Vault;
import org.bukkit.block.data.Brushable;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExplorationLootTest {
    private CivMaker plugin;
    private SettlementResources resources;
    private RaidEquipment equipment;
    private RandomGenerator random;
    private ExplorationLoot loot;

    @BeforeEach void setup() {
        plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        resources = mock(SettlementResources.class);
        equipment = mock(RaidEquipment.class);
        random = mock(RandomGenerator.class);
        loot = new ExplorationLoot(plugin, resources, equipment, random);
        when(random.nextInt(anyInt())).thenReturn(0);
        when(random.nextInt(anyInt(), anyInt())).thenAnswer(call -> call.getArgument(0));
    }

    @ParameterizedTest
    @CsvSource({"desert,GUNPOWDER", "plains,IRON", "savanna,COPPER", "taiga,TOTEM",
            "snowy_plains,OBSIDIAN", "meadow,AMETHYST", "snowy_taiga,TOTEM", "old_growth_pine_taiga,TOTEM"})
    void villageProfessionChestsAndGenericStructuresFollowTheSettlementBiome(String biome, Reward reward) {
        assertEquals(List.of(reward), ExplorationLoot.chestRule("chests/village/village_toolsmith", biome).rewards());
        assertEquals(List.of(reward), ExplorationLoot.chestRule("chests/abandoned_mineshaft", biome).rewards());
    }

    @Test void villageHouseTablesPreserveTheirThemeAcrossBiomeBordersAndMeadowsGetSaddles() {
        assertEquals(List.of(Reward.GUNPOWDER), ExplorationLoot.chestRule("chests/village/village_desert_house", "plains").rewards());
        var meadow = ExplorationLoot.chestRule("chests/village/village_plains_house", "meadow");
        assertEquals(List.of(Reward.AMETHYST), meadow.rewards());
        assertEquals(.01, meadow.gearChance());
        assertEquals(List.of(Tool.SUPER_SADDLE), meadow.gear());
        assertEquals(.01, ExplorationLoot.chestRule("chests/village/village_taiga_house", "taiga").chance());
        var igloo = ExplorationLoot.chestRule("chests/igloo_chest", "snowy_plains");
        assertEquals(.50, igloo.chance());
        assertEquals(8, igloo.minimum());
        assertEquals(16, igloo.maximum());
    }

    @Test void everyVanillaStructureContainerHasAProfileExceptTrapsAndVaultSubtables() {
        for (LootTables table : LootTables.values()) {
            String key = table.getKey().getKey();
            if (!(key.startsWith("chests/") || key.startsWith("barrels/") || key.startsWith("pots/"))) continue;
            if (key.contains("dispenser") || key.equals("chests/spawn_bonus_chest")
                    || key.startsWith("chests/trial_chambers/reward")) continue;
            assertNotNull(ExplorationLoot.chestRule(key, "forest"), key);
        }
        assertNull(ExplorationLoot.chestRule("chests/jungle_temple_dispenser", "jungle"));
        assertNull(ExplorationLoot.chestRule("chests/trial_chambers/reward_rare", "plains"));
        assertNull(ExplorationLoot.chestRule("chests/spawn_bonus_chest", "plains"));
    }

    @Test void archaeologicalPoolsFollowThemesAndTaigaTotemsOnlyRollOnRareTrailLoot() {
        for (LootTables table : LootTables.values()) {
            if (table.getKey().getKey().startsWith("archaeology/")) {
                assertNotNull(ExplorationLoot.archaeologyRule(table.getKey().getKey(), "forest"));
            }
        }
        assertEquals(.15, ExplorationLoot.archaeologyRule("archaeology/desert_well", "desert").chance());
        assertEquals(List.of(Reward.GUNPOWDER), ExplorationLoot.archaeologyRule("archaeology/desert_pyramid", "desert").rewards());
        assertEquals(0, ExplorationLoot.archaeologyRule("archaeology/trail_ruins_common", "taiga").chance());
        assertEquals(.01, ExplorationLoot.archaeologyRule("archaeology/trail_ruins_rare", "taiga").chance());
        assertEquals(List.of(Reward.TOTEM), ExplorationLoot.archaeologyRule("archaeology/trail_ruins_rare", "taiga").rewards());
    }

    private ItemStack stack() {
        ItemStack item = mock(ItemStack.class);
        when(item.asQuantity(anyInt())).thenReturn(item);
        return item;
    }

    @Test void resourceAndGearRollsAreIndependentAndDoNotUseShopBundleQuantities() {
        ItemStack currency = stack();
        ItemStack rockets = stack();
        when(resources.stack(Reward.GUNPOWDER)).thenReturn(currency);
        when(equipment.item(Tool.BLAST_ROCKET, null)).thenReturn(rockets);
        var rule = new ExplorationLoot.Rule(.35, 1, 3, List.of(Reward.GUNPOWDER), .03, List.of(Tool.BLAST_ROCKET));
        when(random.nextDouble()).thenReturn(.35, .029);
        assertEquals(List.of(rockets), loot.roll(rule));
        verify(resources, never()).stack(any());
        verify(rockets).asQuantity(8);
        when(random.nextDouble()).thenReturn(.349, .03);
        assertEquals(List.of(currency), loot.roll(rule));
        verify(currency).asQuantity(1);
        ItemStack guards = stack();
        when(equipment.item(Tool.PILLAGER_GUARD, null)).thenReturn(guards);
        loot.roll(new ExplorationLoot.Rule(0, 1, 1, List.of(Reward.AMETHYST), 1, List.of(Tool.PILLAGER_GUARD)));
        verify(guards).asQuantity(1);
    }

    @Test void chestBonusesWaitUntilVanillaFinishesAndNeverChangeItsGeneratedList() {
        World world = mock(World.class);
        Location location = new Location(world, 1, 64, 1);
        InventoryHolder holder = mock(InventoryHolder.class);
        Inventory inventory = mock(Inventory.class);
        when(holder.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        LootContext context = mock(LootContext.class);
        when(context.getLocation()).thenReturn(location);
        ItemStack vanilla = stack();
        ItemStack bonus = stack();
        var event = new LootGenerateEvent(world, null, holder, mock(LootTable.class), context, List.of(vanilla), false);
        var tasks = new ArrayList<Runnable>();
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { tasks.add(call.getArgument(1)); return null; });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ExplorationLoot.afterGeneration(plugin, event, List.of(bonus));
            assertEquals(List.of(vanilla), event.getLoot());
            verifyNoInteractions(inventory);
            tasks.removeFirst().run();
            verify(inventory).addItem(bonus);
            assertEquals(List.of(vanilla), event.getLoot());
            ExplorationLoot.afterGeneration(plugin, event, List.of(bonus));
            event.setCancelled(true);
            tasks.removeFirst().run();
            verify(inventory, times(1)).addItem(bonus);
        }
    }

    @Test void fullContainersDropOnlyOverflowWithoutRemovingAnyVanillaItem() {
        Inventory inventory = mock(Inventory.class);
        World world = mock(World.class);
        Location location = new Location(world, 0, 64, 0);
        ItemStack bonus = stack();
        ItemStack overflow = stack();
        when(inventory.addItem(bonus)).thenReturn(new HashMap<>(java.util.Map.of(0, overflow)));
        ExplorationLoot.deliver(inventory, location, List.of(bonus));
        verify(world).dropItemNaturally(location, overflow);
        verify(inventory).addItem(bonus);
        verifyNoMoreInteractions(inventory);
    }

    @Test void destroyedContainersDropTheBonusInsteadOfWritingToADetachedInventory() {
        World world = mock(World.class);
        Location location = new Location(world, 0, 64, 0);
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.CHEST, Material.AIR);
        BlockInventoryHolder holder = mock(BlockInventoryHolder.class);
        when(holder.getBlock()).thenReturn(block);
        LootContext context = mock(LootContext.class);
        when(context.getLocation()).thenReturn(location);
        var event = new LootGenerateEvent(world, null, holder, mock(LootTable.class), context, List.of(stack()), false);
        ItemStack bonus = stack();
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        var tasks = new ArrayList<Runnable>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { tasks.add(call.getArgument(1)); return null; });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ExplorationLoot.afterGeneration(plugin, event, List.of(bonus));
            tasks.removeFirst().run();
        }
        verify(world).dropItemNaturally(location, bonus);
        verify(holder, never()).getInventory();
    }

    @Test void vaultKeepsAllVanillaAndExistingFarmStationDropsAndOnlyAddsBonuses() {
        Block block = mock(Block.class);
        when(block.getState(false)).thenReturn(mock(Vault.class));
        LootTable table = mock(LootTable.class);
        when(table.getKey()).thenReturn(NamespacedKey.minecraft("chests/trial_chambers/reward_ominous"));
        ItemStack vanilla = stack();
        ItemStack station = stack();
        ItemStack currency = stack();
        ItemStack radar = stack();
        when(resources.stack(Reward.COPPER)).thenReturn(currency);
        when(equipment.item(Tool.RADAR, null)).thenReturn(radar);
        var event = new BlockDispenseLootEvent(null, block, List.of(vanilla, station), table);
        loot.dispense(event);
        assertEquals(List.of(vanilla, station, currency, radar), event.getDispensedLoot());
        verify(currency).asQuantity(2);
    }

    @Test void cancelledPluginGeneratedAndRepeatableSpawnerLootReceiveNothing() {
        LootGenerateEvent cancelled = mock(LootGenerateEvent.class);
        when(cancelled.isCancelled()).thenReturn(true);
        loot.generate(cancelled);
        LootGenerateEvent fromPlugin = mock(LootGenerateEvent.class);
        when(fromPlugin.isPlugin()).thenReturn(true);
        loot.generate(fromPlugin);
        BlockDispenseLootEvent spawner = mock(BlockDispenseLootEvent.class);
        LootTable table = mock(LootTable.class);
        when(spawner.getLootTable()).thenReturn(table);
        when(table.getKey()).thenReturn(NamespacedKey.minecraft("spawners/trial_chamber/items_to_drop_when_ominous"));
        loot.dispense(spawner);
        verify(spawner, never()).setDispensedLoot(any());
        verifyNoInteractions(resources, equipment);
    }

    @Test void breakingUnfinishedSuspiciousBlocksDoesNotAwardArchaeologyBonuses() {
        BlockDropItemEvent event = mock(BlockDropItemEvent.class);
        BrushableBlock state = mock(BrushableBlock.class);
        Brushable data = mock(Brushable.class);
        when(event.getBlockState()).thenReturn(state);
        when(state.getBlockData()).thenReturn(data);
        when(data.getDusted()).thenReturn(1);
        when(data.getMaximumDusted()).thenReturn(3);
        loot.brushed(event);
        verifyNoInteractions(resources, equipment);
    }
}
