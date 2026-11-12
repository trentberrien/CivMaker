package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import dev.civmaker.RaidEquipment.Tool;
import dev.civmaker.VaultDoors.Doorway;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class RaidTools implements Listener {
    private static final int GRENADE_FUSE_TICKS = 100;
    private static final double GRENADE_SPEED = 0.9;

    private record Grenade(UUID thrower, Window window) {}
    private record Picking(InventoryView view, LockpickPuzzle puzzle, Location location, Material material,
                           Village village, Window window) {}
    private record Storage(Inventory inventory, List<Block> blocks) {}
    private record PickedStorage(InventoryView view, Location location, Village village, Window window,
                                 List<Block> blocks, VaultLoot loot) {}

    private final CivMaker plugin;
    private final CivStore store;
    private final RaidEquipment equipment;
    private final Protection protection;
    private final VaultDoors vaultDoors;
    private final PillagerGuards guards;
    private final DefenderGolems defenders;
    private final SettlementVaults settlementVaults;
    private final Map<TNTPrimed, Grenade> grenades = new HashMap<>();
    private final Map<UUID, Picking> picking = new HashMap<>();
    private final Map<UUID, PickedStorage> picked = new HashMap<>();

    RaidTools(CivMaker plugin, CivStore store, RaidEquipment equipment, Protection protection, VaultDoors vaultDoors,
              PillagerGuards guards, DefenderGolems defenders, SettlementVaults settlementVaults) {
        this.plugin = plugin;
        this.store = store;
        this.equipment = equipment;
        this.protection = protection;
        this.vaultDoors = vaultDoors;
        this.guards = guards;
        this.defenders = defenders;
        this.settlementVaults = settlementVaults;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void use(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }

        Player player = event.getPlayer();
        Tool tool = equipment.identify(event.getItem());
        if (tool == Tool.VAULT_DOOR || tool == Tool.HAPPY_HARNESS || tool == Tool.BLAST_ROCKET
                || tool == Tool.SUPER_TOTEM || tool == Tool.RADAR || tool == Tool.SUPER_SADDLE || tool == Tool.RAID_SHIELD
                || tool == Tool.WARP_PORTAL) {
            return;
        }
        if (tool == null) {
            Tool mainHand = equipment.identify(player.getInventory().getItemInMainHand());
            if (event.getHand() == EquipmentSlot.OFF_HAND && mainHand != null
                    && mainHand != Tool.VAULT_DOOR && mainHand != Tool.HAPPY_HARNESS && mainHand != Tool.BLAST_ROCKET
                    && mainHand != Tool.SUPER_TOTEM && mainHand != Tool.RADAR && mainHand != Tool.SUPER_SADDLE) {
                event.setCancelled(true);
            }
            return;
        }
        boolean itemDenied = event.useItemInHand() == Result.DENY;
        boolean blockDenied = event.useInteractedBlock() == Result.DENY;
        event.setCancelled(true);

        if (event.getHand() != EquipmentSlot.HAND || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (equipment.identify(held) != tool) {
            return;
        }
        if ((tool == Tool.GRENADE || tool == Tool.C4 || tool == Tool.RAM || tool == Tool.LOCKPICK)
                && store.raidRestricted(player.getUniqueId())) {
            player.sendMessage("Your faction has Raid Protection for this window. You cannot raid until it ends.");
            return;
        }
        if (tool == Tool.GRENADE) {
            if (throwGrenade(player)) {
                consumeMainHand(player, held);
            }
            return;
        }

        Block target = event.getClickedBlock();
        if (tool == Tool.DEFENDER_GOLEM) {
            // Settlement protection denies vanilla item placement on the bell.
            if (!blockDenied && target != null && defenders.deploy(player, target)) {
                consumeMainHand(player, held);
            }
            return;
        }
        if (tool == Tool.PILLAGER_GUARD) {
            if (!itemDenied && target != null && event.getBlockFace() == BlockFace.UP && guards.deploy(player, target)) {
                consumeMainHand(player, held);
            }
            return;
        }
        if (target == null || player.isSneaking()) {
            return;
        }
        Village village = store.protectedClaimAt(target);
        if (village == null && tool != Tool.C4) {
            player.sendMessage("This item needs a target inside a village claim.");
            return;
        }

        boolean member = village != null && store.memberOf(player.getUniqueId()) == village;
        Window window = store.activeWindow(System.currentTimeMillis());
        if (village != null && tool != Tool.VAULT && (window == null || member || !store.raidCovers(Plot.at(target)))) {
            player.sendMessage("This claim must be open to raids and belong to another village.");
            return;
        }
        boolean consume = switch (tool) {
            case LOCKPICK -> startPicking(player, target, village, window);
            case RAM -> ram(player, target, village);
            case C4 -> blast(player, target, event.getBlockFace(), village);
            case VAULT -> reinforce(player, target, village, member);
            case VAULT_DOOR, GRENADE, HAPPY_HARNESS, BLAST_ROCKET, PILLAGER_GUARD, DEFENDER_GOLEM,
                    SUPER_TOTEM, RADAR, SUPER_SADDLE, RAID_SHIELD, WARP_PORTAL -> false;
        };
        if (consume) {
            if (village != null && tool != Tool.VAULT) {
                String kind = switch (tool) {
                    case LOCKPICK -> "lockpick";
                    case RAM -> "battering_ram";
                    default -> "c4";
                };
                plugin.discordRaidAlert(village, player.getName(), target.getLocation(), kind);
            }
            consumeMainHand(player, held);
        }
    }

    private void consumeMainHand(Player player, ItemStack held) {
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() == 0 ? null : held);
    }

    private boolean throwGrenade(Player player) {
        Window window = store.activeWindow(System.currentTimeMillis());
        Location launch = grenadeLaunchPoint(player);
        TNTPrimed tnt = player.getWorld().spawn(launch, TNTPrimed.class, grenade -> {
            grenade.setPersistent(false);
            // The fuse uses the protected C4 blast. Keep the vanilla charge inert.
            grenade.setYield(0);
            grenade.setFuseTicks(GRENADE_FUSE_TICKS);
            grenade.setVelocity(launch.getDirection().multiply(GRENADE_SPEED));
        });
        if (!tnt.isValid()) {
            return false;
        }
        grenades.put(tnt, new Grenade(player.getUniqueId(), window));
        return true;
    }

    private Location grenadeLaunchPoint(Player player) {
        Location eye = player.getEyeLocation();
        if (!(player.getVehicle() instanceof HappyGhast ghast)) {
            return eye;
        }

        // Clear the ghast's hitbox even when the rider aims straight down.
        Location edge = eye.clone();
        edge.setPitch(0);
        double clearance = ghast.getWidth() / Math.sqrt(2) + 0.55;
        var forward = edge.getDirection().multiply(clearance);
        Location mount = ghast.getLocation();
        edge.setX(mount.getX() + forward.getX());
        edge.setZ(mount.getZ() + forward.getZ());
        edge.setPitch(eye.getPitch());
        var offset = edge.toVector().subtract(eye.toVector());
        return offset.lengthSquared() > 0 && player.getWorld().rayTraceBlocks(eye, offset, offset.length(),
                FluidCollisionMode.NEVER, true) == null ? edge : eye;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void grenadePrime(ExplosionPrimeEvent event) {
        Grenade grenade = grenades.remove(event.getEntity());
        if (grenade == null) {
            return;
        }
        event.setCancelled(true);
        if (store.raidRestricted(grenade.thrower())) {
            return;
        }
        Location center = event.getEntity().getLocation();
        Village village = store.protectedClaimAt(center.getBlock());
        boolean sameRaid = grenade.window() != null
                && grenade.window().equals(store.activeWindow(System.currentTimeMillis()));
        if (village == null || (sameRaid
                && store.raidCovers(Plot.at(center))
                && store.memberOf(grenade.thrower()) != village)) {
            protection.detonateGrenade(center, village, sameRaid ? Bukkit.getPlayer(grenade.thrower()) : null);
            if (village != null) {
                Player thrower = Bukkit.getPlayer(grenade.thrower());
                plugin.discordRaidAlert(village, thrower == null ? "Unknown" : thrower.getName(), center, "grenade");
            }
        }
    }

    private boolean reinforce(Player player, Block target, Village village, boolean member) {
        var storage = equipment.storageAt(target);
        if (!member || storage == null) {
            player.sendMessage("Use the kit on a chest, barrel, or shulker box in your own village.");
            return false;
        }
        var blocks = Protection.inventoryBlocks(storage.getInventory());
        if (blocks.isEmpty() || blocks.stream().anyMatch(block -> store.protectedClaimAt(block) != village)) {
            player.sendMessage("The entire container must be inside your village.");
            return false;
        }
        if (blocks.stream().anyMatch(protection::protectedStorage)) {
            player.sendMessage("This storage is already a secure vault.");
            return false;
        }
        if (!equipment.reinforce(storage)) {
            return false;
        }
        player.sendMessage("Storage reinforced as a Secure Vault.");
        return true;
    }

    private boolean startPicking(Player player, Block target, Village village, Window window) {
        UUID id = player.getUniqueId();
        if (picking.containsKey(id)) {
            return false;
        }
        Doorway door = Doorway.at(target);
        if (door == null ? pickableStorage(player, target, village) == null : !pickableDoor(player, target, village, door)) {
            return false;
        }
        LockpickPuzzle puzzle = new LockpickPuzzle(ThreadLocalRandom.current());
        InventoryView view = player.openInventory(puzzle.getInventory());
        if (view == null) {
            return false;
        }
        Location center = new Location(target.getWorld(), target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        picking.put(id, new Picking(view, puzzle, center, target.getType(), village, window));
        player.sendMessage("Follow the glowing panes. " + LockpickPuzzle.BREAK_ODDS + " " + LockpickPuzzle.SUCCESS_ODDS);
        return true;
    }

    private boolean pickableDoor(Player player, Block target, Village village, Doorway door) {
        if (!vaultDoors.isVaultDoor(target, village)) {
            player.sendMessage("Ordinary doors can be opened without a lockpick.");
            return false;
        }
        if (door.lower().isOpen()) {
            player.sendMessage("That door is already open.");
            return false;
        }
        return true;
    }

    private Storage pickableStorage(Player player, Block target, Village village) {
        var storage = equipment.storageAt(target);
        if (storage == null) {
            player.sendMessage("Use the lockpick on a door, chest, barrel, or shulker box.");
            return null;
        }
        var inventory = storage.getInventory();
        var blocks = Protection.inventoryBlocks(inventory);
        if (blocks.isEmpty() || blocks.stream().anyMatch(block ->
                store.protectedClaimAt(block) != village || !store.raidCovers(Plot.at(block)))) {
            player.sendMessage("Every part of this container must be in the same village and open to raids.");
            return null;
        }
        return new Storage(inventory, blocks);
    }

    private void finishPicking(Player player, Picking attempt, LockpickPuzzle.Result result) {
        if (!picking.remove(player.getUniqueId(), attempt) || !attempt.view().equals(player.getOpenInventory())) {
            return;
        }
        if (result == LockpickPuzzle.Result.BROKEN) {
            player.closeInventory();
            player.sendMessage("The lockpick snapped.");
            return;
        }
        if (!canContinuePicking(player, attempt)) {
            player.closeInventory();
            return;
        }

        Block target = attempt.location().getBlock();
        Doorway door = Doorway.at(target);
        if (door != null) {
            player.closeInventory();
            if (pickableDoor(player, target, attempt.village(), door)) {
                door.setOpen(true);
                player.sendMessage("Vault Door picked open.");
            }
            return;
        }
        Storage storage = pickableStorage(player, target, attempt.village());
        if (storage == null) {
            player.closeInventory();
            return;
        }
        var settlement = store.settlementVaultAt(target);
        if (settlement != null) {
            settlementVaults.deposit(target, settlement);
        }
        VaultLoot loot = storage.blocks().stream().anyMatch(protection::protectedStorage)
                ? new VaultLoot(storage.inventory(), settlement != null) : null;
        InventoryView view = loot == null ? protection.openPickedStorage(player, storage.inventory(), storage.blocks())
                : player.openInventory(loot.getInventory());
        if (view == null) {
            if (attempt.view().equals(player.getOpenInventory())) {
                player.closeInventory();
            }
            return;
        }
        picked.put(player.getUniqueId(), new PickedStorage(view, attempt.location(),
                attempt.village(), attempt.window(), storage.blocks(), loot));
        player.sendMessage(loot == null ? "Lock picked. Closing the container ends this access."
                : "Vault picked. Click a hidden slot to take its whole stack. You can take up to five slots.");
    }

    private boolean ram(Player player, Block target, Village village) {
        Doorway door = Doorway.at(target);
        if (door == null) {
            player.sendMessage("Use the battering ram on a door.");
            return false;
        }
        if (protection.settlementAccess(door.top()) || protection.settlementAccess(door.bottom())) {
            player.sendMessage("The settlement center is protected.");
            return false;
        }
        door.top().setType(Material.AIR, false);
        door.bottom().setType(Material.AIR, false);
        vaultDoors.forget(village, door.bottom());
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1, 1);
        player.sendMessage("Door breached.");
        return true;
    }

    private boolean blast(Player player, Block target, BlockFace face, Village village) {
        Location center = new Location(target.getWorld(), target.getX() + 0.5 + face.getModX() * 0.55,
                target.getY() + 0.5 + face.getModY() * 0.55, target.getZ() + 0.5 + face.getModZ() * 0.55);
        if (!protection.detonateC4(center, village)) {
            player.sendMessage("The C4 blast was blocked.");
            return false;
        }
        player.sendMessage("C4 detonated.");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void dispense(BlockDispenseEvent event) {
        Tool tool = equipment.identify(event.getItem());
        if (tool != null && tool != Tool.HAPPY_HARNESS) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void craft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (equipment.identify(item) != null) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    void close(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Picking attempt = picking.get(id);
        if (attempt != null && attempt.view().equals(event.getView())) {
            picking.remove(id);
        }
        PickedStorage access = picked.get(id);
        if (access != null && access.view().equals(event.getView())) {
            picked.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof LockpickPuzzle puzzle) {
            clickPuzzle(event, player, puzzle);
        } else if (event.getInventory().getHolder() instanceof VaultLoot loot) {
            boolean alreadyCancelled = event.isCancelled();
            event.setCancelled(true);
            PickedStorage access = picked.get(player.getUniqueId());
            int slot = event.getRawSlot();
            var cursor = event.getCursor();
            if (alreadyCancelled || access == null || access.loot() != loot || !access.view().equals(event.getView())
                    || event.getClick() != ClickType.LEFT || (cursor != null && !cursor.isEmpty())
                    || slot < 0 || slot >= event.getInventory().getSize() || event.getCurrentItem() == null) {
                return;
            }
            Inventory source = availableStorage(player, access);
            if (source != null) {
                boolean first = !loot.hasTakenItems();
                if (loot.take(player, source, slot) && first) {
                    access.village().timesRaided++;
                    store.dirty = true;
                }
            }
        } else if (!event.isCancelled() && accessExpired(player, event.getView())) {
            event.setCancelled(true);
        }
    }

    private void clickPuzzle(InventoryClickEvent event, Player player, LockpickPuzzle puzzle) {
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        Picking attempt = picking.get(player.getUniqueId());
        var cursor = event.getCursor();
        if (alreadyCancelled || event.getClick() != ClickType.LEFT || (cursor != null && !cursor.isEmpty())
                || attempt == null || attempt.puzzle() != puzzle || !attempt.view().equals(event.getView())
                || !canContinuePicking(player, attempt)) {
            return;
        }
        LockpickPuzzle.Result result = puzzle.click(event.getRawSlot(), Bukkit.getCurrentTick());
        if (result == LockpickPuzzle.Result.SOLVED || result == LockpickPuzzle.Result.BROKEN) {
            plugin.getServer().getScheduler().runTask(plugin, () -> finishPicking(player, attempt, result));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void drag(InventoryDragEvent event) {
        var holder = event.getInventory().getHolder();
        if (holder instanceof LockpickPuzzle || holder instanceof VaultLoot
                || (event.getWhoClicked() instanceof Player player && accessExpired(player, event.getView()))) {
            event.setCancelled(true);
        }
    }

    private boolean accessExpired(Player player, InventoryView view) {
        PickedStorage access = picked.get(player.getUniqueId());
        return access != null && access.view().equals(view) && availableStorage(player, access) == null;
    }

    private Inventory availableStorage(Player player, PickedStorage access) {
        if (!canReachDuringRaid(player, access.location(), access.village(), access.window())
                || (access.loot() != null && access.loot().exhausted())) {
            return null;
        }
        Block target = access.location().getBlock();
        var storage = equipment.storageAt(target);
        if (storage == null) {
            return null;
        }
        Inventory inventory = storage.getInventory();
        if (!access.blocks().equals(Protection.inventoryBlocks(inventory))
                || access.blocks().stream().anyMatch(block ->
                store.protectedClaimAt(block) != access.village() || !store.raidCovers(Plot.at(block)))) {
            return null;
        }
        boolean secure = access.blocks().stream().anyMatch(protection::protectedStorage);
        var settlement = store.settlementVaultAt(target);
        if (settlement != null) {
            settlementVaults.deposit(target, settlement);
        }
        return secure == (access.loot() != null) ? inventory : null;
    }

    private boolean canContinuePicking(Player player, Picking attempt) {
        if (!canReachDuringRaid(player, attempt.location(), attempt.village(), attempt.window())) {
            return false;
        }
        Block target = attempt.location().getBlock();
        return target.getType() == attempt.material() && store.protectedClaimAt(target) == attempt.village();
    }

    private boolean canReachDuringRaid(Player player, Location location, Village village, Window window) {
        return window.equals(store.activeWindow(System.currentTimeMillis()))
                && !store.raidRestricted(player.getUniqueId())
                && store.raidCovers(Plot.at(location))
                && player.getGameMode() == GameMode.SURVIVAL && store.memberOf(player.getUniqueId()) != village
                && player.getWorld().equals(location.getWorld()) && player.getLocation().distanceSquared(location) <= 36;
    }

    void closeExpired() {
        grenades.keySet().removeIf(grenade -> !grenade.isValid());
        var attempts = picking.entrySet().iterator();
        while (attempts.hasNext()) {
            var entry = attempts.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            Picking attempt = entry.getValue();
            if (player == null || !attempt.view().equals(player.getOpenInventory())) {
                attempts.remove();
            } else if (!canContinuePicking(player, attempt)) {
                attempts.remove();
                player.closeInventory();
            }
        }

        var iterator = picked.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            PickedStorage access = entry.getValue();
            if (player == null || !access.view().equals(player.getOpenInventory())) {
                iterator.remove();
            } else {
                Inventory source = availableStorage(player, access);
                if (source == null) {
                    iterator.remove();
                    player.closeInventory();
                } else if (access.loot() != null) {
                    access.loot().refresh(source);
                }
            }
        }
    }

    void closeAll() {
        grenades.keySet().forEach(TNTPrimed::remove);
        grenades.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Picking attempt = picking.get(id);
            PickedStorage access = picked.get(id);
            var view = player.getOpenInventory();
            if ((attempt != null && attempt.view().equals(view)) || (access != null && access.view().equals(view))) {
                player.closeInventory();
            }
        }
        picking.clear();
        picked.clear();
    }
}
