package dev.civmaker;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Protection implements Listener {
    private record Charge(Village village, Player grenadier) {}

    private final CivStore store;
    private final RaidEquipment equipment;
    private final VaultDoors vaultDoors;
    private final NaturalVillages natural;
    private final DefenderGolems defenders;
    private final Map<UUID, List<Block>> pickedOpen = new HashMap<>();
    private final Map<UUID, Charge> c4Sources = new HashMap<>();

    Protection(CivStore store, RaidEquipment equipment, VaultDoors vaultDoors, NaturalVillages natural,
               DefenderGolems defenders) {
        this.store = store;
        this.equipment = equipment;
        this.vaultDoors = vaultDoors;
        this.natural = natural;
        this.defenders = defenders;
    }

    InventoryView openPickedStorage(Player player, Inventory inventory, List<Block> blocks) {
        UUID id = player.getUniqueId();
        pickedOpen.put(id, blocks);
        try {
            return player.openInventory(inventory);
        } finally {
            pickedOpen.remove(id);
        }
    }

    boolean detonateC4(Location center, Village village) {
        return detonateCharge(center, village, null);
    }

    void detonateGrenade(Location center, Village village, Player grenadier) {
        detonateCharge(center, village, grenadier);
    }

    private boolean detonateCharge(Location center, Village village, Player grenadier) {
        var world = center.getWorld();
        // The unspawned source selects Paper's TNT rules and identifies this blast in events.
        TNTPrimed source = world.createEntity(center, TNTPrimed.class);
        if (grenadier != null) {
            source.setSource(grenadier);
        }
        UUID id = source.getUniqueId();
        c4Sources.put(id, new Charge(village, grenadier));
        try {
            return world.createExplosion(source, center, 4.0f, false, true);
        } finally {
            c4Sources.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void place(BlockPlaceEvent event) {
        if (cannotPlace(event.getPlayer(), event.getBlockPlaced())
                || (event instanceof BlockMultiPlaceEvent multi && multi.getReplacedBlockStates().stream()
                .anyMatch(state -> cannotPlace(event.getPlayer(), state.getBlock())))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("That block is protected.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void breakBlock(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (naturalPlot(block) || settlementAccess(block)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("That block is protected.");
            return;
        }
        Village village = store.protectedClaimAt(block);
        if (village == null) {
            return;
        }
        Position position = Position.at(block);
        if (store.isClaimAnchor(village, block) || store.settlementVaultAt(block) != null
                || village.treasuries.contains(position) || forbidden(event.getPlayer(), block)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("That block is protected by " + village.name + ".");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void interact(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block != null && event.getAction() == Action.RIGHT_CLICK_BLOCK && settlementAccess(block)) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
        if (block != null && (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.PHYSICAL)
                && forbidden(event.getPlayer(), block)) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                    && ((block.getBlockData() instanceof Door && !vaultDoors.isVaultDoor(block, store.protectedClaimAt(block)))
                    || (block.getType() == Material.BELL && naturalPlot(block)))) {
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void emptyBucket(PlayerBucketEmptyEvent event) {
        if (cannotPlace(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void fillBucket(PlayerBucketFillEvent event) {
        if (forbidden(event.getPlayer(), event.getBlock()) || settlementAccess(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void open(InventoryOpenEvent event) {
        var blocks = inventoryBlocks(event.getInventory());
        if (event.getInventory().getType() == InventoryType.MERCHANT && blocks.stream().anyMatch(this::naturalPlot)) {
            return;
        }
        if (!blocks.equals(pickedOpen.get(event.getPlayer().getUniqueId()))
                && blocks.stream().anyMatch(block -> forbidden(event.getPlayer(), block))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void hopper(InventoryMoveItemEvent event) {
        var from = inventoryBlocks(event.getSource());
        var to = inventoryBlocks(event.getDestination());
        if (from.stream().anyMatch(this::protectedStorage) || to.stream().anyMatch(this::protectedStorage)
                || crossesClaim(from, to)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void blockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::claimed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void entityExplosion(EntityExplodeEvent event) {
        Charge charge = c4Charge(event.getEntity());
        if (charge == null) {
            event.blockList().removeIf(this::claimed);
        } else {
            event.blockList().removeIf(block -> !canBreakWithC4(block, charge));
        }
    }

    private boolean canBreakWithC4(Block block, Charge charge) {
        Village claim = store.protectedClaimAt(block);
        return !naturalPlot(block) && !settlementAccess(block)
                && (claim == null || (claim == charge.village() && store.raidCovers(Plot.at(block))
                && !store.isClaimAnchor(claim, block)))
                && !c4ProtectedStorage(block);
    }

    private boolean c4ProtectedStorage(Block block) {
        if (protectedStorage(block)) {
            return true;
        }
        // Keep both halves intact if either half belongs to a vault or treasury.
        if ((block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST)
                && block.getState(false) instanceof Chest chest) {
            return inventoryBlocks(chest.getInventory()).stream().anyMatch(this::protectedStorage);
        }
        return false;
    }

    private Charge c4Charge(Entity source) {
        return source == null ? null : c4Sources.get(source.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void c4Damage(EntityDamageEvent event) {
        Charge charge = c4Charge(event.getDamageSource().getDirectEntity());
        if (charge != null && (charge.grenadier() == null
                || !defenders.canDamageWithGrenade(event.getEntity(), charge.grenadier()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void c4Knockback(EntityPushedByEntityAttackEvent event) {
        if (c4Charge(event.getPushedBy()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void c4HangingBreak(HangingBreakByEntityEvent event) {
        if (c4Charge(event.getRemover()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void c4Chain(TNTPrimeEvent event) {
        Charge charge = c4Charge(event.getPrimingEntity());
        if (charge != null) {
            // Remove TNT hit by C4 without creating an unrestricted follow-up explosion.
            event.setCancelled(true);
            Block block = event.getBlock();
            if (canBreakWithC4(block, charge)) {
                block.setType(Material.AIR, false);
            }
        } else if (naturalPlot(event.getBlock()) || settlementAccess(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void entityChange(EntityChangeBlockEvent event) {
        if (claimed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void ignite(BlockIgniteEvent event) {
        Block source = event.getIgnitingBlock();
        Block target = event.getBlock();
        if (naturalPlot(target) || settlementAccess(target) || (claimed(target) && (beaconColumn(target) || (event.getPlayer() != null
                ? forbidden(event.getPlayer(), target) : source == null || store.protectedClaimAt(source) != store.protectedClaimAt(target))))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void burn(BlockBurnEvent event) {
        if (claimed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void destroy(BlockDestroyEvent event) {
        if (store.isSettlementFixture(event.getBlock()) || naturalPlot(event.getBlock()) || settlementAccess(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void liquid(BlockFromToEvent event) {
        Block target = event.getToBlock();
        if (blocksEnvironmentalChange(event.getBlock(), target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void dispense(BlockDispenseEvent event) {
        Block source = event.getBlock();
        if (source.getBlockData() instanceof Directional dispenser) {
            Block target = source.getRelative(dispenser.getFacing());
            if (blocksEnvironmentalChange(source, target)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void spread(BlockSpreadEvent event) {
        if (blocksEnvironmentalChange(event.getSource(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void form(BlockFormEvent event) {
        if (beaconColumn(event.getBlock()) || naturalPlot(event.getBlock()) || settlementAccess(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void grow(BlockGrowEvent event) {
        if (naturalPlot(event.getBlock()) || settlementAccess(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void fade(BlockFadeEvent event) {
        if (naturalPlot(event.getBlock()) || settlementAccess(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void decay(LeavesDecayEvent event) {
        if (naturalPlot(event.getBlock()) || settlementAccess(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void growth(StructureGrowEvent event) {
        Block source = event.getLocation().getBlock();
        if (event.getBlocks().stream().map(state -> state.getBlock()).anyMatch(target ->
                blocksEnvironmentalChange(source, target))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void fertilize(BlockFertilizeEvent event) {
        if (event.getBlocks().stream().map(state -> state.getBlock()).anyMatch(target ->
                blocksEnvironmentalChange(event.getBlock(), target))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void sponge(SpongeAbsorbEvent event) {
        event.getBlocks().removeIf(state -> naturalPlot(state.getBlock()) || settlementAccess(state.getBlock())
                || store.protectedClaimAt(event.getBlock()) != store.protectedClaimAt(state.getBlock()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void extend(BlockPistonExtendEvent event) {
        if (pistonBlocked(event.getBlock(), event.getBlocks(), event.getDirection(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void retract(BlockPistonRetractEvent event) {
        if (event.getBlock().getBlockData() instanceof Directional piston
                && pistonBlocked(event.getBlock(), event.getBlocks(), piston.getFacing(), piston.getFacing().getOppositeFace())) {
            event.setCancelled(true);
        }
    }

    private boolean pistonBlocked(Block base, List<Block> blocks, BlockFace facing, BlockFace motion) {
        Village village = store.protectedClaimAt(base);
        Block head = base.getRelative(facing);
        if (store.protectedClaimAt(head) != village || beaconColumn(head) || store.isSettlementFixture(head)
                || naturalPlot(head) || settlementAccess(head)) {
            return true;
        }
        for (Block block : blocks) {
            Block destination = block.getRelative(motion);
            if (store.protectedClaimAt(block) != village || store.protectedClaimAt(destination) != village
                    || beaconColumn(destination) || store.isSettlementFixture(block) || store.isSettlementFixture(destination)
                    || naturalPlot(block) || naturalPlot(destination) || settlementAccess(block) || settlementAccess(destination)) {
                return true;
            }
        }
        return false;
    }

    private boolean blocksEnvironmentalChange(Block source, Block target) {
        Village village = store.protectedClaimAt(target);
        return naturalPlot(target) || settlementAccess(target)
                || (village != null && (beaconColumn(target) || village != store.protectedClaimAt(source)));
    }

    private boolean cannotPlace(HumanEntity player, Block block) {
        return forbidden(player, block) || beaconColumn(block) || settlementAccess(block);
    }

    private boolean forbidden(HumanEntity player, Block block) {
        Village village = store.protectedClaimAt(block);
        return naturalPlot(block) || (village != null && store.memberOf(player.getUniqueId()) != village);
    }

    private boolean claimed(Block block) {
        return store.protectedClaimAt(block) != null || naturalPlot(block) || settlementAccess(block);
    }

    private boolean naturalPlot(Block block) {
        return natural.reserved(block.getWorld(), Plot.at(block));
    }

    boolean settlementAccess(Block block) {
        return natural.inBellProtection(block);
    }

    boolean protectedStorage(Block block) {
        Village village = store.protectedClaimAt(block);
        return village != null && (store.settlementVaultAt(block) != null
                || village.treasuries.contains(Position.at(block)) || equipment.isVault(block));
    }

    private boolean beaconColumn(Block block) {
        Village village = store.protectedClaimAt(block);
        if (village == null) {
            return false;
        }
        Position beacon = village.beacon;
        return block.getX() == beacon.x() && block.getZ() == beacon.z() && block.getY() > beacon.y();
    }

    private boolean crossesClaim(List<Block> from, List<Block> to) {
        if (from.isEmpty()) {
            return to.stream().anyMatch(this::claimed);
        }
        if (to.isEmpty()) {
            return from.stream().anyMatch(this::claimed);
        }
        return from.stream().anyMatch(source -> to.stream().anyMatch(target ->
                naturalPlot(source) || naturalPlot(target) || store.protectedClaimAt(source) != store.protectedClaimAt(target)));
    }

    static List<Block> inventoryBlocks(Inventory inventory) {
        var holder = inventory.getHolder();
        if (holder instanceof DoubleChest chest) {
            var left = chest.getLeftSide();
            var right = chest.getRightSide();
            if (left instanceof org.bukkit.block.Chest first && right instanceof org.bukkit.block.Chest second) {
                return List.of(first.getBlock(), second.getBlock());
            }
        }
        if (holder instanceof BlockInventoryHolder block) {
            return List.of(block.getBlock());
        }
        return holder instanceof Entity entity ? List.of(entity.getLocation().getBlock()) : List.of();
    }
}
