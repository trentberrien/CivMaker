package dev.civmaker;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VaultWithdrawalsTest {
    @TempDir Path directory;
    private CivStore store;
    private MoneyStorage storage;
    private Notes notes;
    private SettlementResources resources;
    private RaidEquipment equipment;
    private CivStore.Village village;
    private Player member;
    private Player elder;
    private Player owner;
    private Inventory vault;
    private Chest chest;
    private Block block;
    private ItemStack currency;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        notes = mock(Notes.class);
        resources = mock(SettlementResources.class);
        equipment = mock(RaidEquipment.class);
        storage = new MoneyStorage(notes, store, equipment, resources);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        owner = player();
        elder = player();
        member = player();
        village = new CivStore.Village("Test", owner.getUniqueId(), new CivStore.Position(world.getUID(), 0, 80, 0));
        store.addVillage(village);
        store.addMember(village, elder.getUniqueId());
        store.addMember(village, member.getUniqueId());
        store.setElder(village, elder.getUniqueId(), true);
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(2);
        when(block.getY()).thenReturn(80);
        when(block.getZ()).thenReturn(2);
        chest = mock(Chest.class);
        when(chest.getBlock()).thenReturn(block);
        vault = mock(Inventory.class);
        when(vault.getHolder()).thenReturn(chest);
        when(chest.getInventory()).thenReturn(vault);
        when(equipment.storageAt(block)).thenReturn(chest);
        when(equipment.isVault(block)).thenReturn(true);
        currency = mock(ItemStack.class);
        when(resources.isCurrency(currency)).thenReturn(true);
        when(currency.isSimilar(currency)).thenReturn(true);
        when(vault.getContents()).thenReturn(new ItemStack[]{currency});
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        return player;
    }

    private InventoryClickEvent click(Player player, InventoryAction action, boolean inVault) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(vault);
        when(event.getView()).thenReturn(view);
        Inventory clicked = inVault ? vault : player.getInventory();
        when(event.getClickedInventory()).thenReturn(clicked);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(currency);
        when(event.getAction()).thenReturn(action);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        return event;
    }

    @ParameterizedTest
    @EnumSource(value = InventoryAction.class, names = {"PICKUP_ALL", "PICKUP_HALF", "PICKUP_ONE", "PICKUP_SOME",
            "MOVE_TO_OTHER_INVENTORY", "HOTBAR_SWAP", "HOTBAR_MOVE_AND_READD", "SWAP_WITH_CURSOR",
            "DROP_ALL_SLOT", "DROP_ONE_SLOT", "PICKUP_ALL_INTO_BUNDLE", "PICKUP_SOME_INTO_BUNDLE", "CLONE_STACK"})
    void ordinaryMembersCannotExtractSettlementCurrencyByAnyInventoryAction(InventoryAction action) {
        InventoryClickEvent event = click(member, action, true);
        storage.click(event);
        verify(event).setCancelled(true);
        verify(member).sendMessage(MoneyStorage.WITHDRAW_MESSAGE);
    }

    @Test void banknotesAreAlsoRestricted() {
        when(resources.isCurrency(currency)).thenReturn(false);
        when(notes.isNote(currency)).thenReturn(true);
        InventoryClickEvent event = click(member, InventoryAction.PICKUP_ALL, true);
        storage.click(event);
        verify(event).setCancelled(true);
    }

    @Test void doubleClickFromPlayerInventoryCannotCollectCurrencyFromVault() {
        InventoryClickEvent event = click(member, InventoryAction.COLLECT_TO_CURSOR, false);
        when(event.getCursor()).thenReturn(currency);
        storage.click(event);
        verify(event).setCancelled(true);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ownerAndElderCanWithdraw(boolean asOwner) {
        InventoryClickEvent event = click(asOwner ? owner : elder, InventoryAction.MOVE_TO_OTHER_INVENTORY, true);
        storage.click(event);
        verify(event, never()).setCancelled(true);
    }

    @Test void ordinaryMembersCanDepositAndRemoveOrdinaryMaterials() {
        when(notes.isNote(currency)).thenReturn(true);
        InventoryClickEvent deposit = click(member, InventoryAction.PLACE_ALL, true);
        when(deposit.getCursor()).thenReturn(currency);
        storage.click(deposit);
        verify(deposit, never()).setCancelled(true);
        when(notes.isNote(currency)).thenReturn(false);
        when(resources.isCurrency(currency)).thenReturn(false);
        InventoryClickEvent ordinary = click(member, InventoryAction.PICKUP_ALL, true);
        storage.click(ordinary);
        verify(ordinary, never()).setCancelled(true);
    }

    @Test void ordinaryStorageAndRaidLootMenusKeepTheirExistingBehavior() {
        when(equipment.isVault(block)).thenReturn(false);
        InventoryClickEvent ordinary = click(member, InventoryAction.PICKUP_ALL, true);
        storage.click(ordinary);
        verify(ordinary, never()).setCancelled(true);
        when(vault.getHolder()).thenReturn(mock(org.bukkit.inventory.InventoryHolder.class));
        InventoryClickEvent raidLoot = click(member, InventoryAction.PICKUP_ALL, true);
        storage.click(raidLoot);
        verify(raidLoot, never()).setCancelled(true);
    }

    @Test void demotionTakesEffectImmediatelyForAnAlreadyOpenVault() {
        store.setElder(village, elder.getUniqueId(), false);
        InventoryClickEvent event = click(elder, InventoryAction.PICKUP_ALL, true);
        storage.click(event);
        verify(event).setCancelled(true);
    }

    @Test void membershipInAnotherFactionDoesNotGrantWithdrawalRights() {
        Player foreignOwner = player();
        store.addVillage(new CivStore.Village("Other", foreignOwner.getUniqueId(),
                new CivStore.Position(UUID.randomUUID(), 0, 80, 0)));
        InventoryClickEvent event = click(foreignOwner, InventoryAction.PICKUP_ALL, true);
        storage.click(event);
        verify(event).setCancelled(true);
    }

    @Test void currencyCannotBeTakenByRemovingAContainingBundle() {
        ItemStack bundle = mock(ItemStack.class);
        BundleMeta meta = mock(BundleMeta.class);
        when(bundle.getItemMeta()).thenReturn(meta);
        when(meta.getItems()).thenReturn(List.of(currency));
        InventoryClickEvent event = click(member, InventoryAction.PICKUP_ALL, true);
        when(event.getCurrentItem()).thenReturn(bundle);
        storage.click(event);
        verify(event).setCancelled(true);
    }

    @Test void breakingACurrencyVaultCannotBypassWithdrawalPermissions() {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getPlayer()).thenReturn(member);
        when(event.getBlock()).thenReturn(block);
        storage.breakVault(event);
        verify(event).setCancelled(true);
    }

    @Test void eitherHalfOfADoubleChestUsesTheVaultWithdrawalRestriction() {
        var doubleChest = mock(org.bukkit.block.DoubleChest.class);
        Chest otherSide = mock(Chest.class);
        Block otherBlock = mock(Block.class);
        World world = block.getWorld();
        when(otherBlock.getWorld()).thenReturn(world);
        when(otherBlock.getX()).thenReturn(3);
        when(otherBlock.getY()).thenReturn(80);
        when(otherBlock.getZ()).thenReturn(2);
        when(otherSide.getBlock()).thenReturn(otherBlock);
        when(doubleChest.getLeftSide()).thenReturn(otherSide);
        when(doubleChest.getRightSide()).thenReturn(chest);
        when(vault.getHolder()).thenReturn(doubleChest);
        InventoryClickEvent event = click(member, InventoryAction.PICKUP_ALL, true);
        storage.click(event);
        verify(event).setCancelled(true);
    }
}
