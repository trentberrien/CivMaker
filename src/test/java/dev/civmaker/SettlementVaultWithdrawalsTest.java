package dev.civmaker;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementVaultWithdrawalsTest {
    @TempDir Path directory;
    private CivStore store;
    private CivStore.Village village;
    private SettlementVaults vaults;
    private SettlementResources resources;
    private Player player;
    private Inventory source;
    private CivStore.Position position;
    private ItemStack item;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        resources = mock(SettlementResources.class);
        vaults = new SettlementVaults(plugin, store, resources);
        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(19);
        when(block.getY()).thenReturn(80);
        when(block.getZ()).thenReturn(2);
        when(block.getType()).thenReturn(Material.CHEST);
        when(block.getLocation()).thenReturn(new Location(world, 19, 80, 2));
        when(world.getBlockAt(19, 80, 2)).thenReturn(block);
        Chest chest = mock(Chest.class);
        when(chest.getBlock()).thenReturn(block);
        when(block.getState(false)).thenReturn(chest);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(chest.getPersistentDataContainer()).thenReturn(data);
        NamespacedKey depositKey = new NamespacedKey("civmaker", "settlement_vault_last_deposit_tick");
        when(data.has(depositKey, PersistentDataType.LONG)).thenReturn(true);
        when(data.get(depositKey, PersistentDataType.LONG)).thenReturn(0L);
        source = mock(Inventory.class);
        when(source.getHolder()).thenReturn(chest);
        when(chest.getBlockInventory()).thenReturn(source);
        item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(5);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.asQuantity(5)).thenReturn(item);
        when(source.getItem(0)).thenReturn(item);
        when(resources.isCurrency(item)).thenReturn(true);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 19.5, 80, 3));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        when(inventory.getMaxStackSize()).thenReturn(64);
        village = new CivStore.Village("Test", UUID.randomUUID(), new CivStore.Position(worldId, 0, 80, 0));
        store.addVillage(village);
        store.addMember(village, player.getUniqueId());
        CivStore.Position bell = new CivStore.Position(worldId, 18, 80, 2);
        var settlement = new CivStore.Settlement("minecraft:village_savanna", bell, Set.of(bell.plot()));
        settlement.vault().position = CivStore.Position.at(block);
        position = settlement.vault().position;
        settlement.vault().reward = SettlementResources.Reward.COPPER;
        store.addSettlement(village, settlement);
    }

    private void withdraw() {
        vaults.withdraw(player, position, 0, item);
    }

    @Test void ordinaryMembersCannotWithdrawSettlementCopper() {
        withdraw();
        verify(source, never()).setItem(eq(0), any());
        verify(player).sendMessage(MoneyStorage.WITHDRAW_MESSAGE);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ownersAndEldersCanWithdrawSettlementCurrency(boolean asOwner) {
        if (asOwner) {
            store.removeMember(village, player.getUniqueId());
            when(player.getUniqueId()).thenReturn(village.owner);
        } else {
            store.setElder(village, player.getUniqueId(), true);
        }
        withdraw();
        verify(source).setItem(0, null);
        verify(player.getInventory()).setStorageContents(any(ItemStack[].class));
    }

    @Test void nonCurrencySettlementRewardsRemainAvailableToMembers() {
        when(resources.isCurrency(item)).thenReturn(false);
        withdraw();
        verify(source).setItem(0, null);
    }

    @Test void demotingAPlayerWithAnOpenMenuStopsCurrencyWithdrawal() {
        store.setElder(village, player.getUniqueId(), true);
        store.setElder(village, player.getUniqueId(), false);
        withdraw();
        verify(source, never()).setItem(eq(0), any());
    }
}
