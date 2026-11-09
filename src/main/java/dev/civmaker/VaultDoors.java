package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import dev.civmaker.RaidEquipment.Tool;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashSet;

final class VaultDoors implements Listener {
    record Doorway(Block bottom, Door lower, Block top, Door upper) {
        static Doorway at(Block target) {
            if (!(target.getBlockData() instanceof Door door)) {
                return null;
            }
            Block bottom = door.getHalf() == Bisected.Half.BOTTOM ? target : target.getRelative(BlockFace.DOWN);
            Block top = bottom.getRelative(BlockFace.UP);
            if (bottom.getType() != top.getType() || !(bottom.getBlockData() instanceof Door lower)
                    || !(top.getBlockData() instanceof Door upper)
                    || lower.getHalf() != Bisected.Half.BOTTOM || upper.getHalf() != Bisected.Half.TOP) {
                return null;
            }
            return new Doorway(bottom, lower, top, upper);
        }

        void setOpen(boolean open) {
            lower.setOpen(open);
            upper.setOpen(open);
            bottom.setBlockData(lower, false);
            top.setBlockData(upper, false);
        }
    }

    private final CivStore store;
    private final RaidEquipment equipment;

    VaultDoors(CivStore store, RaidEquipment equipment) {
        this.store = store;
        this.equipment = equipment;
    }

    boolean isVaultDoor(Block block, Village village) {
        if (village == null || village.vaultDoors == null || block.getType() != Material.IRON_DOOR
                || !(block.getBlockData() instanceof Door door)) {
            return false;
        }
        Block bottom = door.getHalf() == Bisected.Half.BOTTOM ? block : block.getRelative(BlockFace.DOWN);
        return village.vaultDoors.contains(Position.at(bottom));
    }

    private boolean isLockedVaultDoor(Block block, Village village) {
        if (!isVaultDoor(block, village)) {
            return false;
        }
        var door = (Door) block.getBlockData();
        Block otherHalf = block.getRelative(door.getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN);
        return store.protectedClaimAt(block) != null || store.protectedClaimAt(otherHalf) != null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void validatePlacement(BlockPlaceEvent event) {
        if (equipment.identify(event.getItemInHand()) != Tool.VAULT_DOOR) {
            return;
        }
        Village village = store.protectedClaimAt(event.getBlockPlaced());
        Doorway doorway = Doorway.at(event.getBlockPlaced());
        if (village == null || store.memberOf(event.getPlayer().getUniqueId()) != village
                || doorway == null || store.protectedClaimAt(doorway.bottom()) != village
                || store.protectedClaimAt(doorway.top()) != village) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Place both halves of a Vault Door in protected blocks inside your village.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void recordPlacement(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!event.canBuild() || block.getType() != Material.IRON_DOOR) {
            return;
        }
        Village village = store.claimAt(block);
        Doorway doorway = Doorway.at(block);
        if (village == null || doorway == null) {
            return;
        }
        if (equipment.identify(event.getItemInHand()) == Tool.VAULT_DOOR
                && store.memberOf(event.getPlayer().getUniqueId()) == village) {
            if (village.vaultDoors == null) {
                village.vaultDoors = new HashSet<>();
            }
            village.vaultDoors.add(Position.at(doorway.bottom()));
            store.dirty = true;
            doorway.lower().setPowered(false);
            doorway.upper().setPowered(false);
            doorway.setOpen(false);
        } else {
            forget(village, doorway.bottom());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void broken(BlockBreakEvent event) {
        forgetIfVaultDoor(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void exploded(EntityExplodeEvent event) {
        event.blockList().forEach(this::forgetIfVaultDoor);
    }

    private void forgetIfVaultDoor(Block block) {
        Village village = store.claimAt(block);
        if (isVaultDoor(block, village)) {
            var door = (Door) block.getBlockData();
            forget(village, door.getHalf() == Bisected.Half.BOTTOM ? block : block.getRelative(BlockFace.DOWN));
        }
    }

    void forget(Village village, Block bottom) {
        if (village.vaultDoors != null && village.vaultDoors.remove(Position.at(bottom))) {
            store.dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void redstone(BlockRedstoneEvent event) {
        if (isLockedVaultDoor(event.getBlock(), store.claimAt(event.getBlock()))) {
            event.setNewCurrent(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void interact(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || event.getPlayer().isSneaking()
                || equipment.identify(event.getItem()) != null) {
            return;
        }
        Village village = store.claimAt(block);
        if (village == null || block.getType() != Material.IRON_DOOR) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        var player = event.getPlayer();
        if (isLockedVaultDoor(block, village) && store.memberOf(player.getUniqueId()) != village) {
            player.sendMessage("This Vault Door is locked to members of " + village.name + ".");
            return;
        }
        Doorway doorway = Doorway.at(block);
        if (doorway != null) {
            boolean open = !doorway.lower().isOpen();
            doorway.setOpen(open);
            block.getWorld().playSound(block.getLocation(),
                    open ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE, 1, 1);
        }
    }
}
