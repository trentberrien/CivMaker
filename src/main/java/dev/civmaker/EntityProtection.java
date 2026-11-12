package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Village;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

import java.util.UUID;

/** Protect claim inhabitants and decorations without disabling ordinary mob farms or wilderness combat. */
final class EntityProtection implements Listener {
    private final CivStore store;
    private final NaturalVillages natural;
    private final PillagerGuards guards;

    EntityProtection(CivStore store, NaturalVillages natural, PillagerGuards guards) {
        this.store = store;
        this.natural = natural;
        this.guards = guards;
    }

    static UUID playerSource(Entity source) {
        if (source instanceof Player player) {
            return player.getUniqueId();
        }
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return playerSource(shooter);
        }
        if (source instanceof TNTPrimed tnt) {
            return playerSource(tnt.getSource());
        }
        return source instanceof Tameable pet ? pet.getOwnerUniqueId() : null;
    }

    private static UUID playerSource(DamageSource source) {
        if (source == null) {
            return null;
        }
        UUID player = playerSource(source.getCausingEntity());
        return player == null ? playerSource(source.getDirectEntity()) : player;
    }

    private static boolean protectedEntity(Entity entity) {
        return entity instanceof Mob && !(entity instanceof Enemy)
                || entity instanceof ArmorStand || entity instanceof Hanging
                || entity instanceof Vehicle || entity instanceof EnderCrystal;
    }

    boolean forbidden(Entity entity, UUID player) {
        Village guardOwner = entity instanceof Pillager guard ? guards.owner(guard) : null;
        if (guardOwner != null) {
            // Guards are military posts at any height. Only eligible enemy raiders may damage them.
            return player == null || store.memberOf(player) == guardOwner || !raidAllowed(entity, player);
        }
        if (!protectedEntity(entity)) {
            return false;
        }
        var block = entity.getLocation().getBlock();
        Village village = store.protectedClaimAt(block);
        if (village == null) {
            return natural.reserved(block.getWorld(), Plot.at(block));
        }
        if (player != null && store.memberOf(player) == village) {
            return false;
        }
        return player == null || !raidAllowed(entity, player);
    }

    private boolean raidAllowed(Entity entity, UUID player) {
        return store.activeWindow(System.currentTimeMillis()) != null
                && !store.raidRestricted(player) && store.raidCovers(Plot.at(entity.getLocation()));
    }

    private boolean restrictedAttack(Entity target, UUID player) {
        if (player == null || !store.raidRestricted(player)) {
            return false;
        }
        Village village = store.claimAt(target.getLocation());
        return village != null && store.registeredVillage(player) != village;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void damage(EntityDamageEvent event) {
        Entity target = event.getEntity();
        DamageSource source = event.getDamageSource();
        UUID player = playerSource(source);
        boolean causedByEntity = source != null && (source.getCausingEntity() != null || source.getDirectEntity() != null);
        boolean explosion = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        boolean guard = target instanceof Pillager pillager && guards.owner(pillager) != null;
        if (restrictedAttack(target, player) || ((guard || causedByEntity || explosion) && forbidden(target, player))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void combust(EntityCombustByEntityEvent event) {
        UUID player = playerSource(event.getCombuster());
        if (restrictedAttack(event.getEntity(), player) || forbidden(event.getEntity(), player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void knockback(EntityPushedByEntityAttackEvent event) {
        UUID player = playerSource(event.getPushedBy());
        if (restrictedAttack(event.getEntity(), player) || forbidden(event.getEntity(), player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void hanging(HangingBreakEvent event) {
        UUID player = null;
        if (event instanceof HangingBreakByEntityEvent attack) {
            player = playerSource(attack.getDamageSource());
            if (player == null) {
                player = playerSource(attack.getRemover());
            }
        }
        if (forbidden(event.getEntity(), player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void interact(PlayerInteractEntityEvent event) {
        // Trading remains public; ownership restrictions for trading belong to VillagerRework.
        if (event.getRightClicked() instanceof AbstractVillager
                && event.getPlayer().getInventory().getItem(event.getHand()).getType() != Material.LEAD) {
            return;
        }
        // Guard stance changes have their own membership check. Damage uses the stricter rule above.
        if (event.getRightClicked() instanceof Pillager guard && guards.owner(guard) != null) {
            return;
        }
        if (forbidden(event.getRightClicked(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void armorStand(PlayerArmorStandManipulateEvent event) {
        if (forbidden(event.getRightClicked(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void leash(PlayerLeashEntityEvent event) {
        if (forbidden(event.getEntity(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void unleash(PlayerUnleashEntityEvent event) {
        if (forbidden(event.getEntity(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void shear(PlayerShearEntityEvent event) {
        if (forbidden(event.getEntity(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void bucket(PlayerBucketEntityEvent event) {
        if (forbidden(event.getEntity(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void fish(PlayerFishEvent event) {
        if (event.getCaught() != null && forbidden(event.getCaught(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void mount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player && forbidden(event.getMount(), player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void vehicleDamage(VehicleDamageEvent event) {
        UUID player = playerSource(event.getDamageSource());
        if (player == null) {
            player = playerSource(event.getAttacker());
        }
        if (forbidden(event.getVehicle(), player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void vehicleDestroy(VehicleDestroyEvent event) {
        UUID player = playerSource(event.getDamageSource());
        if (player == null) {
            player = playerSource(event.getAttacker());
        }
        if (forbidden(event.getVehicle(), player)) {
            event.setCancelled(true);
        }
    }
}
