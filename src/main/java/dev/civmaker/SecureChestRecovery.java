package dev.civmaker;

import dev.civmaker.CivStore.Position;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SecureChestRecovery implements Listener {
    private record Recovery(BlockBreakEvent event, Material material, List<Block> parts, boolean refundable) {}

    private final CivMaker plugin;
    private final CivStore store;
    private final RaidEquipment equipment;
    private final Map<Position, Recovery> pending = new HashMap<>();

    SecureChestRecovery(CivMaker plugin, CivStore store, RaidEquipment equipment) {
        this.plugin = plugin;
        this.store = store;
        this.equipment = equipment;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void recordBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (event.isCancelled() || !isChest(block.getType()) || !(equipment.storageAt(block) instanceof Chest chest)) {
            return;
        }
        List<Block> parts = List.copyOf(Protection.inventoryBlocks(chest.getInventory()));
        if (parts.isEmpty() || parts.stream().noneMatch(equipment::isVault)) {
            return;
        }
        boolean refundable = event.isDropItems() && event.getPlayer().getGameMode() == GameMode.SURVIVAL
                && owns(event.getPlayer(), parts);
        var recovery = new Recovery(event, block.getType(), parts, refundable);
        Position position = Position.at(block);
        pending.put(position, recovery);
        // Creative and suppressed-drop breaks do not emit BlockDropItemEvent. Preserve the
        // remaining half in those cases too, and never leave stale refund records behind.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (pending.remove(position, recovery) && !event.isCancelled() && block.getType() != recovery.material()) {
                preserveRemainingHalf(recovery);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void recover(BlockDropItemEvent event) {
        Recovery recovery = pending.remove(Position.at(event.getBlock()));
        if (recovery == null || recovery.event().isCancelled()
                || !recovery.event().getPlayer().getUniqueId().equals(event.getPlayer().getUniqueId())) {
            return;
        }
        // A cancelled drop event still represents a broken block. Keep a surviving
        // chest secure, but respect the cancellation when deciding whether to drop a kit.
        preserveRemainingHalf(recovery);
        if (recovery.parts().size() == 1 && recovery.refundable() && !event.isCancelled()
                && event.getPlayer().getGameMode() == GameMode.SURVIVAL && owns(event.getPlayer(), recovery.parts())) {
            Block block = event.getBlock();
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                    equipment.item(RaidEquipment.Tool.VAULT, null));
        }
    }

    private void preserveRemainingHalf(Recovery recovery) {
        if (recovery.parts().size() < 2) {
            return;
        }
        Block broken = recovery.event().getBlock();
        for (Block part : recovery.parts()) {
            if (!part.equals(broken) && isChest(part.getType()) && !equipment.isVault(part)
                    && equipment.storageAt(part) instanceof Chest remaining) {
                equipment.reinforce(remaining);
            }
        }
    }

    private boolean owns(Player player, List<Block> parts) {
        var village = store.registeredVillage(player.getUniqueId());
        return village != null && parts.stream().allMatch(block -> store.protectedClaimAt(block) == village
                && !store.isSettlementFixture(block) && !village.treasuries.contains(Position.at(block)));
    }

    private static boolean isChest(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }
}
