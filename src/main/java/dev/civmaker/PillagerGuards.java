package dev.civmaker;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Village;
import dev.civmaker.RaidEquipment.Tool;
import io.papermc.paper.event.entity.EntityMoveEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.TriState;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

final class PillagerGuards implements Listener {
    private static final double RANGE = 4 * 16.0;
    private static final double ROCKET_SPEED = 1.4;
    // Paper moves the rocket before checking whether its age has exceeded the fuse.
    private static final int ROCKET_FUSE_TICKS = (int) Math.ceil(RANGE / ROCKET_SPEED) - 1;
    private static final Component GUARD_NAME = Component.text("guard");

    private final CivStore store;
    private final RaidEquipment equipment;
    private final DefenseStance stance;
    private final NamespacedKey villageKey;
    private final NamespacedKey raidKey;
    private final Map<Pillager, DefenseStance.RaidAggro> loaded = new HashMap<>();

    PillagerGuards(CivMaker plugin, CivStore store, RaidEquipment equipment) {
        this.store = store;
        this.equipment = equipment;
        stance = new DefenseStance(plugin, store, true);
        villageKey = new NamespacedKey(plugin, "guard_village");
        raidKey = new NamespacedKey(plugin, "guard_raid");
    }

    boolean deploy(Player player, Block ground) {
        Village village = store.claimAt(ground);
        if (village == null || store.memberOf(player.getUniqueId()) != village) {
            player.sendMessage("Post your Pillager Guard inside your own village.");
            return false;
        }
        Block feet = ground.getRelative(BlockFace.UP);
        if (!ground.getType().isSolid() || feet.getY() + 1 >= ground.getWorld().getMaxHeight()
                || !feet.isEmpty() || !feet.getRelative(BlockFace.UP).isEmpty()
                || loaded.keySet().stream().anyMatch(guard -> guard.getLocation().getBlock().equals(feet))) {
            player.sendMessage("Choose a clear spot with two empty blocks above solid ground.");
            return false;
        }

        Location post = feet.getLocation().add(0.5, 0, 0.5);
        post.setYaw(player.getLocation().getYaw());
        Pillager guard = post.getWorld().spawn(post, Pillager.class, false, pillager -> {
            pillager.getPersistentDataContainer().set(villageKey, PersistentDataType.STRING, village.id.toString());
            pillager.setPersistent(true);
            pillager.setRemoveWhenFarAway(false);
            pillager.setDespawnInPeacefulOverride(TriState.FALSE);
            pillager.setCanJoinRaid(false);
            pillager.setAI(false);
            pillager.setGravity(false);
            pillager.setCollidable(false);
            pillager.setCanPickupItems(false);
            var gear = pillager.getEquipment();
            gear.setItemInMainHand(new ItemStack(Material.CROSSBOW));
            gear.setItemInMainHandDropChance(0);
        });
        if (!guard.isValid()) {
            player.sendMessage("The Pillager Guard could not spawn here.");
            return false;
        }
        player.sendMessage("Posted a Pillager Guard for " + village.name + ".");
        return true;
    }

    void track(Pillager guard) {
        if (isGuard(guard)) {
            guard.customName(GUARD_NAME);
            guard.setCustomNameVisible(true);
            loaded.computeIfAbsent(guard, stance::raidAggro);
        }
    }

    Stream<Location> enemyPosts(World world, Village viewerVillage) {
        return loaded.keySet().stream()
                .filter(guard -> guard.isValid() && !guard.isDead() && guard.getWorld().equals(world))
                .map(guard -> {
                    Location post = guard.getLocation();
                    Village village = store.claimAt(post);
                    return village != viewerVillage && belongsTo(guard, village) ? post : null;
                })
                .filter(Objects::nonNull);
    }

    Village owner(Pillager guard) {
        Village village = store.claimAt(guard.getLocation());
        return belongsTo(guard, village) ? village : null;
    }

    @EventHandler
    void added(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Pillager guard) {
            track(guard);
        }
    }

    @EventHandler
    void removed(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Pillager guard) {
            loaded.remove(guard);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void toggle(PlayerInteractEntityEvent event) {
        if (!DefenseStance.wantsToggle(event) || !(event.getRightClicked() instanceof Pillager guard)) {
            return;
        }
        Village village = store.registeredVillage(event.getPlayer().getUniqueId());
        var aggro = loaded.get(guard);
        if (aggro != null && guard.isValid() && belongsTo(guard, village) && store.claimAt(guard.getLocation()) == village) {
            stance.toggle(event, guard, "Pillager Guard", store.settlementAt(Plot.at(guard.getLocation())));
            aggro.clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    void move(EntityMoveEvent event) {
        if (isGuard(event.getEntity()) && event.hasChangedPosition()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    void teleport(EntityTeleportEvent event) {
        if (isGuard(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    void portal(EntityPortalEvent event) {
        teleport(event);
    }

    @EventHandler(ignoreCancelled = true)
    void mount(EntityMountEvent event) {
        if (isGuard(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    void refresh() {
        var window = store.activeWindow(System.currentTimeMillis());
        for (Pillager guard : List.copyOf(loaded.keySet())) {
            if (!guard.isValid()) {
                continue;
            }
            Location post = guard.getLocation();
            Village village = store.claimAt(post);
            if (!belongsTo(guard, village)) {
                guard.remove();
            } else if (window != null && store.raidCovers(Plot.at(post))) {
                Location launch = guard.getEyeLocation();
                Location nearestAim = null;
                double nearestDistance = RANGE * RANGE;
                for (Player player : launch.getWorld().getNearbyPlayers(launch, RANGE)) {
                    Location aim = player.getLocation().add(0, player.getHeight() * 0.5, 0);
                    double distance = aim.distanceSquared(launch);
                    if (distance <= nearestDistance && canAttack(guard, player, village, window.start())
                            && guard.hasLineOfSight(player)) {
                        nearestAim = aim;
                        nearestDistance = distance;
                    }
                }
                if (nearestAim != null) {
                    fire(guard, launch, nearestAim, village, window.start());
                }
            }
        }
    }

    private void fire(Pillager guard, Location launch, Location aim, Village village, long raid) {
        var direction = aim.toVector().subtract(launch.toVector());
        if (direction.lengthSquared() == 0) {
            return;
        }
        launch.setDirection(direction);
        guard.setRotation(launch.getYaw(), launch.getPitch());
        guard.setBodyYaw(launch.getYaw());
        var ammo = equipment.item(Tool.BLAST_ROCKET, null).asOne();
        launch.getWorld().spawn(launch, Firework.class, rocket -> {
            rocket.setItem(ammo);
            rocket.setPersistent(false);
            rocket.setShooter(guard);
            rocket.setShotAtAngle(true);
            rocket.setTicksToDetonate(ROCKET_FUSE_TICKS);
            rocket.setVelocity(direction.normalize().multiply(ROCKET_SPEED));
            var data = rocket.getPersistentDataContainer();
            data.set(villageKey, PersistentDataType.STRING, village.id.toString());
            data.set(raidKey, PersistentDataType.LONG, raid);
        });
        launch.getWorld().playSound(launch, Sound.ITEM_CROSSBOW_SHOOT, 1, 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void damage(EntityDamageEvent event) {
        if (!(event.getDamageSource().getDirectEntity() instanceof Firework rocket)
                || !rocket.getPersistentDataContainer().has(raidKey, PersistentDataType.LONG)) {
            return;
        }
        var window = store.activeWindow(System.currentTimeMillis());
        if (window == null || window.start() != rocket.getPersistentDataContainer().get(raidKey, PersistentDataType.LONG)) {
            event.setCancelled(true);
            return;
        }
        String owner = rocket.getPersistentDataContainer().get(villageKey, PersistentDataType.STRING);
        Village village = owner == null ? null : store.village(UUID.fromString(owner));
        if (!(rocket.getShooter() instanceof Pillager guard) || !belongsTo(guard, village)) {
            event.setCancelled(true);
            return;
        }
        Entity victim = event.getEntity();
        boolean enemy = victim instanceof Player player && canAttack(guard, player, village, window.start());
        boolean enemyMount = victim instanceof Mob && victim.getPassengers().stream()
                .anyMatch(rider -> rider instanceof Player player && canAttack(guard, player, village, window.start()));
        if (!enemy && !enemyMount) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void provoked(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Pillager guard) || event.getFinalDamage() <= 0
                || !(event.getDamageSource().getCausingEntity() instanceof Player player)) {
            return;
        }
        var aggro = loaded.get(guard);
        if (aggro == null || alwaysHostile(guard, aggro)) {
            return;
        }
        var window = store.activeWindow(System.currentTimeMillis());
        Village village = store.claimAt(guard.getLocation());
        if (window != null && belongsTo(guard, village) && store.raidCovers(Plot.at(guard.getLocation()))
                && isEnemy(player, village)) {
            aggro.provoke(window.start(), player, store.memberOf(player.getUniqueId()));
        }
    }

    private boolean canAttack(Pillager guard, Player player, Village village, long raid) {
        var aggro = loaded.get(guard);
        return isEnemy(player, village) && (alwaysHostile(guard, aggro)
                || (aggro != null && aggro.includes(raid, player, store.memberOf(player.getUniqueId()))));
    }

    private boolean alwaysHostile(Pillager guard, DefenseStance.RaidAggro aggro) {
        var settlement = store.settlementAt(Plot.at(guard.getLocation()));
        if (aggro != null) {
            aggro.sync(settlement);
        }
        return stance.alwaysHostile(guard, settlement);
    }

    private boolean isGuard(Entity entity) {
        return entity instanceof Pillager && entity.getPersistentDataContainer().has(villageKey, PersistentDataType.STRING);
    }

    private boolean belongsTo(Entity entity, Village village) {
        return village != null && village.id.toString().equals(
                entity.getPersistentDataContainer().get(villageKey, PersistentDataType.STRING));
    }

    private boolean isEnemy(Player player, Village village) {
        return village != null && !player.isDead()
                && store.raidCovers(Plot.at(player.getLocation()))
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && store.memberOf(player.getUniqueId()) != village;
    }
}
