package dev.civmaker;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementVaultProductionTest {
    private static final NamespacedKey DEPOSIT = new NamespacedKey("civmaker", "settlement_vault_last_deposit_tick");
    private static final NamespacedKey WINDOW = new NamespacedKey("civmaker", "settlement_vault_deposit_window");
    private static final NamespacedKey LEGACY = new NamespacedKey("civmaker", "settlement_vault_last_deposit");
    @TempDir Path directory;
    private CivStore store;
    private SettlementVaults vaults;
    private CivStore.Settlement settlement;
    private Block block;
    private Chest chest;
    private World world;
    private ItemStack reward;
    private final Map<NamespacedKey, Long> data = new HashMap<>();
    private final ItemStack[] contents = new ItemStack[SettlementVaults.SLOTS];

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        store.setRaidMode(CivStore.RaidMode.ON);
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        SettlementResources resources = mock(SettlementResources.class);
        reward = mock(ItemStack.class);
        when(resources.stack(SettlementResources.Reward.COPPER)).thenReturn(reward);
        vaults = new SettlementVaults(plugin, store, resources);
        world = mock(World.class);
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.CHEST);
        chest = mock(Chest.class);
        when(block.getState(false)).thenReturn(chest);
        when(block.getState()).thenReturn(chest);
        when(chest.update(false, false)).thenReturn(true);
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(chest.getPersistentDataContainer()).thenReturn(container);
        when(container.has(any(NamespacedKey.class), eq(PersistentDataType.LONG)))
                .thenAnswer(call -> data.containsKey(call.getArgument(0)));
        when(container.get(any(NamespacedKey.class), eq(PersistentDataType.LONG)))
                .thenAnswer(call -> data.get(call.getArgument(0)));
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(container).set(any(NamespacedKey.class), eq(PersistentDataType.LONG), anyLong());
        doAnswer(call -> { data.remove(call.getArgument(0)); return null; })
                .when(container).remove(any(NamespacedKey.class));
        Inventory inventory = mock(Inventory.class);
        when(chest.getSnapshotInventory()).thenReturn(inventory);
        when(inventory.getItem(anyInt())).thenAnswer(call -> contents[(int) call.getArgument(0)]);
        doAnswer(call -> { contents[(int) call.getArgument(0)] = call.getArgument(1); return null; })
                .when(inventory).setItem(anyInt(), any());
        var bell = new CivStore.Position(UUID.randomUUID(), 0, 80, 0);
        settlement = new CivStore.Settlement("minecraft:village_savanna", bell, Set.of(bell.plot()));
        settlement.vault().reward = SettlementResources.Reward.COPPER;
        data.put(DEPOSIT, 0L);
    }

    private void at(long tick) {
        when(world.getFullTime()).thenReturn(tick);
        vaults.deposit(block, settlement);
    }

    @Test void closedRaidsDoNotProduceEvenWithAnOverdueDeposit() {
        store.setRaidMode(CivStore.RaidMode.OFF);
        at(240_000);
        assertArrayEquals(new ItemStack[5], contents);
        verify(chest, never()).update(anyBoolean(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(value = CivStore.RaidMode.class, names = {"ON", "SETTLEMENTS", "AUTO"})
    void producesOncePerMinecraftDayInEveryOpenRaidMode(CivStore.RaidMode mode) {
        long now = System.currentTimeMillis();
        store.windows().add(new CivStore.Window(now - 60_000, now + 3_600_000));
        store.setRaidMode(mode);
        at(0);
        at(23_999);
        assertNull(contents[0]);
        at(24_000);
        assertSame(reward, contents[0]);
        assertNull(contents[1]);
        assertEquals(24_000L, data.get(DEPOSIT));
    }

    @Test void newWindowDiscardsOffHoursBacklogEvenIfVaultWasUnloaded() {
        at(0);
        store.setRaidMode(CivStore.RaidMode.OFF);
        // No deposit calls while the vault's chunk is unloaded.
        store.setRaidMode(CivStore.RaidMode.ON);
        at(240_000);
        assertNull(contents[0]);
        assertEquals(240_000L, data.get(DEPOSIT));
        at(264_000);
        assertSame(reward, contents[0]);
        assertNull(contents[1]);
    }

    @Test void closingAndResumingTheSameScheduledWindowStartsAFreshCycle() {
        long now = System.currentTimeMillis();
        store.windows().add(new CivStore.Window(now - 60_000, now + 3_600_000));
        store.setRaidMode(CivStore.RaidMode.AUTO);
        at(0);
        store.setRaidMode(CivStore.RaidMode.OFF);
        at(24_000);
        assertFalse(data.containsKey(WINDOW));
        store.setRaidMode(CivStore.RaidMode.AUTO);
        at(240_000);
        assertNull(contents[0]);
        assertEquals(240_000L, data.get(DEPOSIT));
    }

    @Test void catchesUpWithinTheSameRaidAndLosesProductionWhileFull() {
        at(0);
        at(10 * 24_000);
        for (ItemStack item : contents) assertSame(reward, item);
        at(11 * 24_000);
        contents[0] = null;
        at(11 * 24_000 + 1);
        assertNull(contents[0]);
        at(12 * 24_000);
        assertSame(reward, contents[0]);
    }

    @Test void upgradingLegacyVaultPreservesContentsAndDiscardsOldBacklog() {
        data.remove(DEPOSIT);
        data.put(LEGACY, 1L);
        ItemStack stored = mock(ItemStack.class);
        contents[0] = stored;
        at(240_000);
        assertSame(stored, contents[0]);
        assertNull(contents[1]);
        assertFalse(data.containsKey(LEGACY));
        assertEquals(240_000L, data.get(DEPOSIT));
    }

    @Test void adminTestDepositCannotBypassClosedRaids() {
        Player player = mock(Player.class);
        when(player.getTargetBlockExact(5)).thenReturn(block);
        CivStore testStore = mock(CivStore.class);
        when(testStore.settlementVaultAt(block)).thenReturn(settlement);
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        new SettlementVaults(plugin, testStore, mock(SettlementResources.class)).testDeposit(player);
        verify(player).sendMessage(contains("only produce during raid hours"));
        verify(chest, never()).update(anyBoolean(), anyBoolean());
    }
}
