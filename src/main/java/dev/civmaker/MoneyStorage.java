package dev.civmaker;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.type.Shelf;
import org.bukkit.entity.Allay;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.Arrays;

final class MoneyStorage implements Listener {
    private static final String STORAGE_MESSAGE = "Store banknotes in your inventory or in a Secure Vault inside your village.";
    static final String WITHDRAW_MESSAGE = "Only elders and owners can withdraw banknotes or settlement currency from faction vaults. Ask your owner to promote you to elder.";
    private final Notes notes;
    private final CivStore store;
    private final RaidEquipment equipment;
    private final SettlementResources resources;

    MoneyStorage(Notes notes, CivStore store, RaidEquipment equipment, SettlementResources resources) {
        this.notes = notes;
        this.store = store;
        this.equipment = equipment;
        this.resources = resources;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void click(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }

        if (restrictedVault(top, event.getWhoClicked()) && takesCurrency(event, top, clicked)) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(WITHDRAW_MESSAGE);
            return;
        }

        var inventory = event.getWhoClicked().getInventory();
        boolean blocked = switch (event.getAction()) {
            case PICKUP_ALL_INTO_BUNDLE, PICKUP_SOME_INTO_BUNDLE -> notes.isNote(event.getCurrentItem());
            case PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE -> notes.isNote(event.getCursor());
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR ->
                    clicked == top && notes.isNote(event.getCursor()) && !allowsDeposit(top, event.getWhoClicked());
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD ->
                    clicked == top && notes.isNote(hotbarItem(event, inventory))
                            && !allowsDeposit(top, event.getWhoClicked());
            case MOVE_TO_OTHER_INVENTORY ->
                    clicked != top && notes.isNote(event.getCurrentItem()) && !allowsDeposit(top, event.getWhoClicked());
            case PLACE_FROM_BUNDLE -> clicked == top && event.getCursor() != null
                    && event.getCursor().getItemMeta() instanceof BundleMeta bundle
                    && bundle.getItems().stream().anyMatch(notes::isNote) && !allowsDeposit(top, event.getWhoClicked());
            default -> false;
        };
        if (blocked) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(STORAGE_MESSAGE);
        }
    }

    private boolean restrictedVault(Inventory inventory, HumanEntity player) {
        var blocks = Protection.inventoryBlocks(inventory);
        if (blocks.stream().noneMatch(equipment::isVault)) {
            return false;
        }
        var village = store.registeredVillage(player.getUniqueId());
        return village == null || !village.canManage(player.getUniqueId())
                || blocks.stream().anyMatch(block -> store.protectedClaimAt(block) != village);
    }

    private boolean takesCurrency(InventoryClickEvent event, Inventory top, Inventory clicked) {
        if (event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
            ItemStack cursor = event.getCursor();
            return isVaultCurrency(cursor) && Arrays.stream(top.getContents())
                    .anyMatch(item -> isVaultCurrency(item) && item.isSimilar(cursor));
        }
        if (clicked != top || !isVaultCurrency(event.getCurrentItem())) {
            return false;
        }
        return switch (event.getAction()) {
            case NOTHING, PLACE_ALL, PLACE_SOME, PLACE_ONE, PLACE_FROM_BUNDLE,
                    PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE -> false;
            default -> true;
        };
    }

    private boolean isVaultCurrency(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        if (notes.isNote(item) || resources.isCurrency(item)) {
            return true;
        }
        var meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundle) {
            return bundle.getItems().stream().anyMatch(this::isVaultCurrency);
        }
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof org.bukkit.inventory.InventoryHolder holder) {
            return Arrays.stream(holder.getInventory().getContents()).anyMatch(this::isVaultCurrency);
        }
        return false;
    }

    private ItemStack hotbarItem(InventoryClickEvent event, PlayerInventory inventory) {
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return inventory.getItemInOffHand();
        }
        int slot = event.getHotbarButton();
        return slot < 0 ? null : inventory.getItem(slot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void drag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (notes.isNote(event.getOldCursor()) && event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())
                && !allowsDeposit(top, event.getWhoClicked())) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(STORAGE_MESSAGE);
        }
    }

    private boolean allowsDeposit(Inventory inventory, HumanEntity player) {
        if (!(inventory instanceof DoubleChestInventory) && !(inventory.getHolder() instanceof BlockInventoryHolder)) {
            return false;
        }
        var blocks = Protection.inventoryBlocks(inventory);
        var village = store.memberOf(player.getUniqueId());
        return village != null && !blocks.isEmpty()
                && blocks.stream().allMatch(block -> store.protectedClaimAt(block) == village)
                && blocks.stream().anyMatch(equipment::isVault);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void hopperPickup(InventoryPickupItemEvent event) {
        if (notes.isNote(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void hopperTransfer(InventoryMoveItemEvent event) {
        if (notes.isNote(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void mobPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player) && notes.isNote(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void giveToAllay(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Allay && notes.isNote(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(STORAGE_MESSAGE);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void frame(PlayerItemFrameChangeEvent event) {
        if (event.getAction() == PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE && notes.isNote(event.getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(STORAGE_MESSAGE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    void directBlockDeposit(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        boolean blocked = block.getType() == Material.DECORATED_POT && notes.isNote(event.getItem());
        if (block.getBlockData() instanceof Shelf shelf) {
            var inventory = event.getPlayer().getInventory();
            blocked = notes.isNote(event.getItem()) || (shelf.isPowered()
                    && Arrays.stream(inventory.getStorageContents(), 0, 9).anyMatch(notes::isNote));
        }
        if (blocked) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.getPlayer().sendMessage(STORAGE_MESSAGE);
        }
    }

    // A broken shulker box keeps its inventory in the dropped box item.
    private boolean containsCashShulker(Block block) {
        return Tag.SHULKER_BOXES.isTagged(block.getType()) && block.getState(false) instanceof ShulkerBox box
                && Arrays.stream(box.getInventory().getStorageContents()).anyMatch(notes::isNote);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void breakVault(BlockBreakEvent event) {
        var storage = equipment.storageAt(event.getBlock());
        if (storage != null && restrictedVault(storage.getInventory(), event.getPlayer())
                && Arrays.stream(storage.getInventory().getContents()).anyMatch(this::isVaultCurrency)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(WITHDRAW_MESSAGE);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void breakShulker(BlockBreakEvent event) {
        if (containsCashShulker(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Withdraw the banknotes before breaking this shulker box.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void pushShulker(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::containsCashShulker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void blockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::containsCashShulker);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void entityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::containsCashShulker);
    }
}
