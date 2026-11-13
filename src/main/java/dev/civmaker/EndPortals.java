package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.logging.Level;

final class EndPortals implements Listener {
    private static final int REQUIRED_POINTS = 500;
    private static final long PRICE = 1_000_000;
    private static final String COST = String.format(Locale.US, "$%,d", PRICE);
    private static final String REQUIREMENTS = "an upgraded capital beacon, " + REQUIRED_POINTS + " faction points, and " + COST;
    private static final int BUY_SLOT = 13;
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private record Keeper(Position portal, Villager villager) {}

    private static final class Menu implements InventoryHolder {
        final Keeper keeper;
        final Village village;
        final Inventory inventory;

        Menu(Keeper keeper, Village village) {
            this.keeper = keeper;
            this.village = village;
            inventory = Bukkit.createInventory(this, 27, Component.text("End Portal Keeper"));
            ItemStack purchase = new ItemStack(Material.ENDER_EYE);
            purchase.editMeta(meta -> {
                meta.displayName(Component.text("Unlock the End for " + COST, NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        Component.text("Upgraded capital beacon: " + (village.expandedClaims ? "Yes" : "Required")),
                        Component.text("Faction points: " + village.points() + " / " + REQUIRED_POINTS),
                        Component.text("One payment unlocks access for your whole faction.")));
            });
            inventory.setItem(BUY_SLOT, purchase);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final CivMaker plugin;
    private final CivStore store;
    private final Wallets wallets;
    private final SequencedMap<Plot, World> pending = new LinkedHashMap<>();
    private final Map<UUID, Keeper> keepers = new HashMap<>();

    EndPortals(CivMaker plugin, CivStore store, Wallets wallets) {
        this.plugin = plugin;
        this.store = store;
        this.wallets = wallets;
    }

    void queue(Chunk chunk) {
        World world = chunk.getWorld();
        if (world.getEnvironment() == World.Environment.NORMAL) {
            pending.put(new Plot(world.getUID(), chunk.getX(), chunk.getZ()), world);
        }
    }

    void discoverRooms() {
        for (int remaining = 32; remaining > 0 && !pending.isEmpty(); remaining--) {
            var entry = pending.pollFirstEntry();
            Plot plot = entry.getKey();
            World world = entry.getValue();
            if (!world.isChunkLoaded(plot.x(), plot.z())) {
                continue;
            }
            for (var stronghold : world.getStructures(plot.x(), plot.z(), Structure.STRONGHOLD)) {
                for (var piece : stronghold.getPieces()) {
                    discoverRoom(world, plot, piece.getBoundingBox());
                }
            }
        }
    }

    private void discoverRoom(World world, Plot plot, BoundingBox room) {
        int minX = (int) room.getMinX();
        int maxX = (int) room.getMaxX();
        int minZ = (int) room.getMinZ();
        int maxZ = (int) room.getMaxZ();
        // Paper exposes inclusive block coordinates. The vanilla portal room is 11 × 8 × 16.
        boolean longX = maxX - minX == 15 && maxZ - minZ == 10;
        boolean longZ = maxX - minX == 10 && maxZ - minZ == 15;
        if ((!longX && !longZ) || room.getHeight() != 7 || plot.x() < (minX >> 4) || plot.x() > (maxX >> 4)
                || plot.z() < (minZ >> 4) || plot.z() > (maxZ >> 4)) {
            return;
        }
        int floorY = (int) room.getMinY();
        for (int offset : new int[]{5, 10}) {
            int x = minX + (longX ? offset : 5);
            int z = minZ + (longZ ? offset : 5);
            Position portal = new Position(world.getUID(), x, floorY + 3, z);
            if (keepers.values().stream().anyMatch(keeper -> keeper.portal().equals(portal))
                    || frames(world, portal).isEmpty()) {
                continue;
            }
            int towardsEntrance = offset == 5 ? 1 : -1;
            Location spot = standingSpot(world, portal, floorY, longX, towardsEntrance);
            if (spot == null) {
                continue;
            }
            Villager villager = world.spawn(spot, Villager.class, npc -> {
                npc.customName(Component.text("End Portal Keeper", NamedTextColor.GOLD));
                npc.setCustomNameVisible(true);
                npc.setAI(false);
                npc.setGravity(false);
                npc.setInvulnerable(true);
                npc.setCollidable(false);
                npc.setPersistent(false);
                npc.setCanPickupItems(false);
                npc.setRemoveWhenFarAway(false);
            });
            if (villager.isValid()) {
                keepers.put(villager.getUniqueId(), new Keeper(portal, villager));
            }
            return;
        }
    }

    private static Location standingSpot(World world, Position portal, int floorY, boolean longX, int towardsEntrance) {
        for (int forward = 4; forward <= 7; forward++) {
            for (int side : new int[]{-2, 2, -3, 3}) {
                int x = portal.x() + (longX ? forward * towardsEntrance : side);
                int z = portal.z() + (longX ? side : forward * towardsEntrance);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Block feet = world.getBlockAt(x, floorY + 1, z);
                Block floor = feet.getRelative(BlockFace.DOWN);
                if (feet.getType().isAir() && feet.getRelative(BlockFace.UP).getType().isAir()
                        && floor.getType().isSolid() && floor.getBoundingBox().getMaxY() == feet.getY()) {
                    Location spot = feet.getLocation().add(0.5, 0, 0.5);
                    spot.setDirection(new Location(world, portal.x() + 0.5, spot.getY(), portal.z() + 0.5)
                            .subtract(spot).toVector());
                    return spot;
                }
            }
        }
        return null;
    }

    private static List<Block> frames(World world, Position center) {
        for (int x = (center.x() - 2) >> 4; x <= ((center.x() + 2) >> 4); x++) {
            for (int z = (center.z() - 2) >> 4; z <= ((center.z() + 2) >> 4); z++) {
                if (!world.isChunkLoaded(x, z)) {
                    return List.of();
                }
            }
        }
        Block middle = world.getBlockAt(center.x(), center.y(), center.z());
        List<Block> frames = new ArrayList<>(12);
        for (BlockFace side : SIDES) {
            Block edge = middle.getRelative(side, 2);
            for (int i = -1; i <= 1; i++) {
                Block frame = edge.getRelative(i * side.getModZ(), 0, i * side.getModX());
                if (!(frame.getBlockData() instanceof EndPortalFrame data) || data.getFacing() != side.getOppositeFace()) {
                    return List.of();
                }
                frames.add(frame);
            }
        }
        return frames;
    }

    @EventHandler
    void load(ChunkLoadEvent event) {
        queue(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void unload(ChunkUnloadEvent event) {
        var chunk = event.getChunk();
        Plot plot = new Plot(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        pending.remove(plot);
        keepers.values().removeIf(keeper -> {
            if (!Plot.at(keeper.villager().getLocation()).equals(plot)) {
                return false;
            }
            keeper.villager().remove();
            return true;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void unloadWorld(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        pending.keySet().removeIf(plot -> plot.world().equals(world));
        keepers.values().removeIf(keeper -> keeper.portal().world().equals(world));
    }

    void closeAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                player.closeInventory();
            }
        }
        keepers.values().forEach(keeper -> keeper.villager().remove());
        keepers.clear();
        pending.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void enter(EntityPortalEnterEvent event) {
        if (event.getPortalType() == PortalType.ENDER
                && event.getLocation().getWorld().getEnvironment() != World.Environment.THE_END
                && blockedEntry(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void teleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                && event.getFrom().getWorld().getEnvironment() != World.Environment.THE_END
                && event.getTo() != null && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END
                && blockedEntry(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private boolean blockedEntry(Entity entity) {
        if (entity instanceof Player player && !hasEndAccess(player)) {
            player.sendActionBar(Component.text("Talk to the End Portal Keeper. Unlocking the End requires " + REQUIREMENTS + "."));
            return true;
        }
        return entity.getPassengers().stream().anyMatch(this::blockedEntry);
    }

    private boolean hasEndAccess(Player player) {
        Village village = store.registeredVillage(player.getUniqueId());
        return village != null && village.expandedClaims && village.endUnlocked;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void interact(PlayerInteractEntityEvent event) {
        Keeper keeper = keepers.get(event.getRightClicked().getUniqueId());
        if (keeper == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Village village = store.registeredVillage(player.getUniqueId());
        if (!eligible(player, village)) {
            return;
        }
        if (village.endUnlocked) {
            openPortal(player, keeper, village);
        } else {
            player.openInventory(new Menu(keeper, village).inventory);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void damage(EntityDamageEvent event) {
        if (keepers.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != BUY_SLOT
                || !event.getClick().isLeftClick()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) {
                return;
            }
            player.closeInventory();
            if (store.registeredVillage(player.getUniqueId()) != menu.village) {
                player.sendMessage("Your faction changed. Talk to the End Portal Keeper again.");
            } else if (eligible(player, menu.village)) {
                openPortal(player, menu.keeper, menu.village);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    private boolean eligible(Player player, Village village) {
        if (village == null) {
            player.sendMessage("Join a faction to unlock the End. The unlock requires " + REQUIREMENTS + ".");
        } else if (!village.expandedClaims) {
            player.sendMessage("Upgrade your capital beacon before opening the End. An owner or elder must right-click it to deposit 1 ordinary beacon crafted with a Nether Star. Unlocking the End requires " + REQUIREMENTS + ".");
        } else if (village.endUnlocked) {
            return true;
        } else if (village.points() < REQUIRED_POINTS) {
            player.sendMessage("Unlocking the End requires " + REQUIREMENTS + ". Your faction has " + village.points() + " points.");
        } else if (wallets.balance(player) < PRICE) {
            player.sendMessage("You need " + COST + " in your wallet to unlock the End for your faction.");
        } else {
            return true;
        }
        return false;
    }

    private void openPortal(Player player, Keeper keeper, Village village) {
        Villager npc = keeper.villager();
        World world = npc.getWorld();
        if (keepers.get(npc.getUniqueId()) != keeper || !npc.isValid() || !player.getWorld().equals(world)
                || player.getLocation().distanceSquared(npc.getLocation()) > 64) {
            player.sendMessage("Move closer and talk to the End Portal Keeper again.");
            return;
        }
        Position center = keeper.portal();
        List<Block> frames = frames(world, center);
        if (frames.isEmpty()) {
            player.sendMessage("The portal frames must be intact and loaded.");
            return;
        }
        if (!plugin.getServer().getAllowEnd()) {
            player.sendMessage("The End is disabled on this server.");
            return;
        }
        List<Block> interior = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block block = world.getBlockAt(center.x() + dx, center.y(), center.z() + dz);
                if (block.getType() == Material.END_PORTAL) {
                    continue;
                }
                if (!block.getType().isAir()) {
                    player.sendMessage("Clear the blocks inside the portal frame and try again.");
                    return;
                }
                interior.add(block);
            }
        }
        boolean purchasing = !village.endUnlocked;
        if (purchasing && !purchase(player, village)) {
            return;
        }
        for (Block frame : frames) {
            EndPortalFrame data = (EndPortalFrame) frame.getBlockData();
            if (!data.hasEye()) {
                data.setEye(true);
                frame.setBlockData(data, false);
            }
        }
        interior.forEach(block -> block.setType(Material.END_PORTAL, false));
        player.sendMessage(purchasing ? "You paid " + COST + " and unlocked the End for " + village.name + "."
                : "The End portal is open for " + village.name + ".");
    }

    private boolean purchase(Player player, Village village) {
        long balance = wallets.balance(player);
        wallets.setBalance(player, balance - PRICE);
        village.endUnlocked = true;
        try {
            plugin.saveState();
        } catch (IOException error) {
            village.endUnlocked = false;
            wallets.setBalance(player, balance);
            plugin.getLogger().log(Level.SEVERE, "Could not save the End portal unlock for " + village.name, error);
            player.sendMessage("The End unlock could not be saved. Please try again.");
            return false;
        }
        player.saveData();
        return true;
    }
}
