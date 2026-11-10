package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.WarpPortal;
import dev.civmaker.RaidEquipment.Tool;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WarpPortals implements Listener {
    private static final int PORTAL_RADIUS = 4;
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int COMPANION_RANGE = 16;
    private static final int LANDING_RADIUS = 4;

    private record Destination(WarpPortal warp, String name, Position bell) {}
    private record Companion(Mob mob, Location arrival, boolean leashed) {}

    private static final class Menu implements InventoryHolder {
        private final Inventory inventory = Bukkit.createInventory(this, 54, Component.text("Choose a warp destination"));
        private final WarpPortal source;
        private final List<Destination> destinations;
        private final Village village;
        private final int page;
        private boolean busy;

        Menu(WarpPortal source, List<Destination> destinations, Village village, int page, SettlementCapture capture) {
            this.source = source;
            this.destinations = destinations;
            this.village = village;
            this.page = page;
            refreshDestinations(capture);
            if (page > 0) {
                inventory.setItem(PREVIOUS_SLOT, button(Material.ARROW, "Previous page"));
            }
            if ((page + 1) * PAGE_SIZE < destinations.size()) {
                inventory.setItem(NEXT_SLOT, button(Material.ARROW, "Next page"));
            }
        }

        void refreshDestinations(SettlementCapture capture) {
            int start = page * PAGE_SIZE;
            for (int i = start; i < Math.min(start + PAGE_SIZE, destinations.size()); i++) {
                Destination destination = destinations.get(i);
                boolean contested = destination.bell() != null && capture.recentlyContested(destination.bell(), village);
                Material icon = contested ? Material.ENDER_EYE : Material.ENDER_PEARL;
                int slot = i - start;
                ItemStack current = inventory.getItem(slot);
                if (current != null && current.getType() == icon) {
                    continue;
                }
                Position spot = destination.warp().spot();
                Component coordinates = Component.text("X: " + spot.x() + "   Z: " + spot.z());
                inventory.setItem(slot, contested
                        ? button(icon, destination.name(), coordinates, Component.text("Recent raider activity", NamedTextColor.RED))
                        : button(icon, destination.name(), coordinates));
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final CivMaker plugin;
    private final CivStore store;
    private final RaidEquipment equipment;
    private final CombatTimeout combat;
    private final SettlementCapture capture;
    private final NamespacedKey cooldown;
    private final Map<WarpPortal, WanderingTrader> traders = new HashMap<>();
    private final Map<UUID, WarpPortal> byEntity = new HashMap<>();

    WarpPortals(CivMaker plugin, CivStore store, RaidEquipment equipment, CombatTimeout combat, SettlementCapture capture) {
        this.plugin = plugin;
        this.store = store;
        this.equipment = equipment;
        this.combat = combat;
        this.capture = capture;
        cooldown = new NamespacedKey(plugin, "warp_cooldown_until");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void place(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || equipment.identify(event.getItem()) != Tool.WARP_PORTAL) {
            return;
        }
        boolean denied = event.useItemInHand() == Event.Result.DENY;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (denied || event.getHand() != EquipmentSlot.HAND || player.getGameMode() != GameMode.SURVIVAL
                || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getBlockFace() != BlockFace.UP) {
            return;
        }
        Block floor = event.getClickedBlock();
        if (floor == null || floor.getY() + 2 >= floor.getWorld().getMaxHeight()) {
            return;
        }
        Block feet = floor.getRelative(BlockFace.UP);
        Village village = store.registeredVillage(player.getUniqueId());
        Position site = store.warpSite(village, Plot.at(feet));
        if (site == null) {
            player.sendMessage("Place a warp trader inside your faction's capital or settlement.");
            return;
        }
        if (village.warpPortals.stream().anyMatch(warp -> site.equals(store.warpSite(village, warp.spot().plot())))) {
            player.sendMessage("This capital or settlement already has a warp trader.");
            return;
        }
        if (!safeSpot(feet)) {
            player.sendMessage("Pick a solid floor with two clear blocks above it.");
            return;
        }
        Block portal = nearbyPortal(feet, village, site);
        if (portal == null) {
            player.sendMessage("Pick a spot within four blocks of a lit Nether portal in this claim.");
            return;
        }
        WarpPortal warp = new WarpPortal(Position.at(feet), Position.at(portal));
        if (!spawnTrader(warp, feet, traderName(village))) {
            player.sendMessage("The warp trader could not be placed here.");
            return;
        }
        village.warpPortals.add(warp);
        store.dirty = true;
        ItemStack held = player.getInventory().getItemInMainHand();
        player.getInventory().setItemInMainHand(held.getAmount() == 1 ? null : held.asQuantity(held.getAmount() - 1));
        player.sendMessage("Warp trader placed. Right-click it to travel.");
    }

    private Block nearbyPortal(Block feet, Village village, Position site) {
        Block nearest = null;
        int distance = PORTAL_RADIUS * PORTAL_RADIUS + 1;
        for (int dx = -PORTAL_RADIUS; dx <= PORTAL_RADIUS; dx++) {
            for (int dz = -PORTAL_RADIUS; dz <= PORTAL_RADIUS; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int squared = dx * dx + dy * dy + dz * dz;
                    int y = feet.getY() + dy;
                    if (squared >= distance || y < feet.getWorld().getMinHeight() || y >= feet.getWorld().getMaxHeight()) {
                        continue;
                    }
                    Block candidate = feet.getRelative(dx, dy, dz);
                    Plot plot = Plot.at(candidate);
                    if (feet.getWorld().isChunkLoaded(plot.x(), plot.z())
                            && candidate.getType() == Material.NETHER_PORTAL && site.equals(store.warpSite(village, plot))) {
                        nearest = candidate;
                        distance = squared;
                    }
                }
            }
        }
        return nearest;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void interact(PlayerInteractEntityEvent event) {
        WarpPortal source = byEntity.get(event.getRightClicked().getUniqueId());
        if (source == null) {
            return;
        }
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (cancelled || event.getHand() != EquipmentSlot.HAND || player.getGameMode() != GameMode.SURVIVAL
                || player.isDead() || combat.locked(player)) {
            return;
        }
        Village village = store.registeredVillage(player.getUniqueId());
        if (!belongsTo(source, village)) {
            player.sendMessage("Only the faction that owns this warp portal can use it.");
            return;
        }
        if (player.isSneaking() && village.owner.equals(player.getUniqueId())) {
            pack(player, village, source);
            return;
        }
        if (!canDepart(player, source)) {
            return;
        }
        List<Destination> destinations = village.warpPortals.stream()
                .filter(warp -> !warp.equals(source) && hasClaim(warp, village))
                .map(warp -> destination(village, warp))
                .toList();
        if (destinations.isEmpty()) {
            player.sendMessage("Your faction needs another warp trader before you can travel.");
            return;
        }
        player.openInventory(new Menu(source, destinations, village, 0, capture).inventory);
    }

    private void pack(Player player, Village village, WarpPortal warp) {
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents().clone();
        if (ItemTransfers.insert(contents, equipment.item(Tool.WARP_PORTAL, null), inventory.getMaxStackSize()) > 0) {
            player.sendMessage("Make room in your inventory to pick up the warp trader.");
            return;
        }
        village.warpPortals.remove(warp);
        store.dirty = true;
        removeTrader(warp);
        inventory.setStorageContents(contents);
        player.sendMessage("Picked up the warp portal kit.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || menu.busy || event.getClick() != ClickType.LEFT
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= menu.inventory.getSize() || menu.inventory.getItem(slot) == null) {
            return;
        }
        menu.busy = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!menuOpen(player, menu) || !canDepart(player, menu.source)) {
                menu.busy = false;
                return;
            }
            if (slot == PREVIOUS_SLOT || slot == NEXT_SLOT) {
                int page = menu.page + (slot == PREVIOUS_SLOT ? -1 : 1);
                menu.busy = false;
                player.openInventory(new Menu(menu.source, menu.destinations, menu.village, page, capture).inventory);
                return;
            }
            WarpPortal destination = menu.destinations.get(menu.page * PAGE_SIZE + slot).warp();
            loadDestination(player, menu, destination);
        });
    }

    private void loadDestination(Player player, Menu menu, WarpPortal destination) {
        World world = Bukkit.getWorld(destination.spot().world());
        if (world == null || !belongsTo(destination, store.registeredVillage(player.getUniqueId()))) {
            menu.busy = false;
            player.sendMessage("That warp portal is no longer available to your faction.");
            return;
        }
        Plot spot = destination.spot().plot();
        Plot portal = destination.portal().plot();
        var spotChunk = world.getChunkAtAsync(spot.x(), spot.z(), false);
        var portalChunk = spot.equals(portal) ? spotChunk : world.getChunkAtAsync(portal.x(), portal.z(), false);
        spotChunk.thenCombine(portalChunk, (a, b) -> a != null && b != null)
                .whenComplete((loaded, error) -> {
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, () -> finishTravel(player, menu, destination,
                                error == null && Boolean.TRUE.equals(loaded)));
                    }
                });
    }

    private void finishTravel(Player player, Menu menu, WarpPortal destination, boolean loaded) {
        menu.busy = false;
        if (!menuOpen(player, menu) || !canDepart(player, menu.source)) {
            return;
        }
        if (!belongsTo(destination, store.registeredVillage(player.getUniqueId()))) {
            player.sendMessage("That warp portal is no longer available to your faction.");
            return;
        }
        Block feet = loaded ? loadedBlock(destination.spot()) : null;
        if (feet == null || !safeSpot(feet) || !isLit(destination)) {
            player.sendMessage("That warp portal needs a lit portal and a clear landing spot.");
            return;
        }
        Mob mount = player.getVehicle() instanceof Mob ridden ? ridden : null;
        if (mount != null && (mount.getPassengers().size() != 1 || mount.isInsideVehicle()
                || (mount.isLeashed() && !player.equals(mount.getLeashHolder())))) {
            player.sendMessage("Dismount before warping if your mount has another rider or is tethered.");
            return;
        }
        Village village = store.registeredVillage(player.getUniqueId());
        Position site = store.warpSite(village, Plot.at(feet));
        Location mountArrival = mount == null ? null : companionLanding(mount, player, feet, village, site);
        if (mount != null && mountArrival == null) {
            player.sendMessage("Your mount needs more room beside the destination warp trader.");
            return;
        }
        Location arrival = mountArrival == null ? center(feet) : mountArrival.clone();
        arrival.setYaw(player.getYaw());
        List<Companion> companions = new ArrayList<>();
        for (var entity : player.getNearbyEntities(COMPANION_RANGE, COMPANION_RANGE, COMPANION_RANGE)) {
            if (!(entity instanceof Mob mob) || mob.equals(mount) || !mob.isValid()
                    || mob.isInsideVehicle() || !mob.getPassengers().isEmpty()) {
                continue;
            }
            boolean leashed = mob.isLeashed() && player.equals(mob.getLeashHolder());
            boolean followingPet = !mob.isLeashed() && mob instanceof Tameable pet
                    && player.getUniqueId().equals(pet.getOwnerUniqueId())
                    && mob instanceof Sittable sitting && !sitting.isSitting();
            if (leashed || followingPet) {
                Location landing = companionLanding(mob, null, arrival.getBlock(), village, site);
                if (landing == null) {
                    player.sendMessage("Your " + mob.getName() + " needs more room beside the destination warp trader.");
                    return;
                }
                companions.add(new Companion(mob, landing, leashed));
            }
        }
        boolean mountLeashed = mount != null && mount.isLeashed();
        if (player.teleport(arrival)) {
            Location actual = player.getLocation();
            if (arrival.getWorld().equals(actual.getWorld()) && arrival.distanceSquared(actual) < 1) {
                if (mount != null && moveCompanion(player, new Companion(mount, mountArrival, mountLeashed))) {
                    if (!mount.addPassenger(player)) {
                        player.sendMessage("Your mount arrived beside you.");
                    }
                }
                for (Companion companion : companions) {
                    moveCompanion(player, companion);
                }
            }
            long seconds = cooldownSeconds();
            if (seconds > 0) {
                player.getPersistentDataContainer().set(cooldown, PersistentDataType.LONG,
                        System.currentTimeMillis() + seconds * 1_000);
            }
            player.closeInventory();
        }
    }

    private static boolean moveCompanion(Player player, Companion companion) {
        Mob mob = companion.mob();
        if (!mob.teleport(companion.arrival())) {
            player.sendMessage("Your " + mob.getName() + " could not warp with you.");
            return false;
        }
        if (companion.leashed() && (!mob.isLeashed() || !player.equals(mob.getLeashHolder()))) {
            mob.setLeashHolder(player);
        }
        return true;
    }

    private Location companionLanding(Mob mob, Player rider, Block feet, Village village, Position site) {
        double height = mob.getHeight();
        if (rider != null) {
            height = Math.max(height, rider.getY() - mob.getY() + rider.getHeight());
        }
        for (int radius = 0; radius <= LANDING_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    Block candidate = feet.getRelative(dx, 0, dz);
                    Plot plot = Plot.at(candidate);
                    if (feet.getWorld().isChunkLoaded(plot.x(), plot.z())
                            && site.equals(store.warpSite(village, plot)) && safeSpot(candidate)
                            && hasRoom(center(candidate), mob.getWidth(), height)) {
                        Location landing = center(candidate);
                        landing.setYaw(mob.getYaw());
                        return landing;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasRoom(Location center, double width, double height) {
        World world = center.getWorld();
        int minX = (int) Math.floor(center.getX() - width / 2);
        int maxX = (int) Math.ceil(center.getX() + width / 2);
        int minZ = (int) Math.floor(center.getZ() - width / 2);
        int maxZ = (int) Math.ceil(center.getZ() + width / 2);
        int maxY = (int) Math.ceil(center.getY() + height);
        if (maxY > world.getMaxHeight()) {
            return false;
        }
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)
                        || !world.getWorldBorder().isInside(new Location(world, x + 0.5, center.getY(), z + 0.5))) {
                    return false;
                }
                for (int y = center.getBlockY(); y < maxY; y++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean canDepart(Player player, WarpPortal warp) {
        WanderingTrader trader = traders.get(warp);
        if (player.isDead() || player.getGameMode() != GameMode.SURVIVAL || combat.locked(player)
                || trader == null || !trader.isValid() || !trader.getWorld().equals(player.getWorld())
                || trader.getLocation().distanceSquared(player.getLocation()) > 25
                || !belongsTo(warp, store.registeredVillage(player.getUniqueId()))) {
            player.sendMessage("Stand beside an active warp trader owned by your faction.");
            return false;
        }
        if (!isLit(warp)) {
            player.sendMessage("Light the Nether portal beside this trader to travel.");
            return false;
        }
        long until = player.getPersistentDataContainer().getOrDefault(cooldown, PersistentDataType.LONG, 0L);
        long remaining = until - System.currentTimeMillis();
        if (remaining > 0 && cooldownSeconds() > 0) {
            player.sendMessage("You can warp again in " + Math.ceilDiv(remaining, 1_000) + " seconds.");
            return false;
        }
        return true;
    }

    private boolean belongsTo(WarpPortal warp, Village village) {
        return hasClaim(warp, village) && village.warpPortals.contains(warp);
    }

    private boolean hasClaim(WarpPortal warp, Village village) {
        Position site = store.warpSite(village, warp.spot().plot());
        return site != null && site.equals(store.warpSite(village, warp.portal().plot()));
    }

    private Destination destination(Village village, WarpPortal warp) {
        var settlement = store.settlementAt(warp.spot().plot());
        return settlement == null ? new Destination(warp, village.name + " Capital", null)
                : new Destination(warp, settlement.displayName(loadedBlock(settlement.bell())), settlement.bell());
    }

    private int cooldownSeconds() {
        return plugin.getConfig().getInt("warp-portal.cooldown-seconds", 60);
    }

    @EventHandler
    void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    void damage(EntityDamageEvent event) {
        if (byEntity.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    void teleport(EntityTeleportEvent event) {
        if (byEntity.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    void refresh() {
        var loaded = new HashSet<WarpPortal>();
        for (Village village : store.villages()) {
            Component name = traderName(village);
            for (WarpPortal warp : village.warpPortals) {
                Block feet = loadedBlock(warp.spot());
                if (feet == null) {
                    continue;
                }
                loaded.add(warp);
                WanderingTrader trader = traders.get(warp);
                if (trader == null || !trader.isValid()) {
                    removeTrader(warp);
                    spawnTrader(warp, feet, name);
                } else if (!name.equals(trader.customName())) {
                    trader.customName(name);
                }
            }
        }
        for (WarpPortal warp : List.copyOf(traders.keySet())) {
            if (!loaded.contains(warp)) {
                removeTrader(warp);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu
                    && store.registeredVillage(player.getUniqueId()) == menu.village) {
                menu.refreshDestinations(capture);
            }
        }
    }

    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                player.closeInventory();
            }
        }
        List.copyOf(traders.keySet()).forEach(this::removeTrader);
    }

    private static Component traderName(Village village) {
        return Component.text("Warp Trader", TextColor.color(village.color));
    }

    private boolean spawnTrader(WarpPortal warp, Block feet, Component name) {
        WanderingTrader trader = feet.getWorld().spawn(center(feet), WanderingTrader.class, false, npc -> {
            npc.customName(name);
            npc.setCustomNameVisible(true);
            npc.setCanDrinkPotion(false);
            npc.setAI(false);
            npc.setGravity(false);
            npc.setInvulnerable(true);
            npc.setCollidable(false);
            npc.setPersistent(false);
            npc.setRemoveWhenFarAway(false);
            npc.setDespawnDelay(0);
        });
        if (!trader.isValid()) {
            return false;
        }
        traders.put(warp, trader);
        byEntity.put(trader.getUniqueId(), warp);
        return true;
    }

    private void removeTrader(WarpPortal warp) {
        WanderingTrader trader = traders.remove(warp);
        if (trader != null) {
            byEntity.remove(trader.getUniqueId());
            trader.remove();
        }
    }

    private static boolean isLit(WarpPortal warp) {
        Block block = loadedBlock(warp.portal());
        return block != null && block.getType() == Material.NETHER_PORTAL;
    }

    private static Block loadedBlock(Position position) {
        World world = Bukkit.getWorld(position.world());
        Plot plot = position.plot();
        return world != null && world.isChunkLoaded(plot.x(), plot.z())
                ? world.getBlockAt(position.x(), position.y(), position.z()) : null;
    }

    private static boolean safeSpot(Block feet) {
        Block floor = feet.getRelative(BlockFace.DOWN);
        if (!feet.getType().isAir() || !feet.getRelative(BlockFace.UP).getType().isAir()
                || !floor.getType().isSolid() || floor.getBoundingBox().getMaxY() != feet.getY()
                || !feet.getWorld().getWorldBorder().isInside(center(feet))) {
            return false;
        }
        return switch (floor.getType()) {
            case MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, CACTUS, POINTED_DRIPSTONE -> false;
            default -> true;
        };
    }

    private static Location center(Block feet) {
        return feet.getLocation().add(0.5, 0, 0.5);
    }

    private static boolean menuOpen(Player player, Menu menu) {
        return player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == menu;
    }

    private static ItemStack button(Material material, String label, Component... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(label));
            meta.lore(List.of(lore));
        });
        return item;
    }
}
