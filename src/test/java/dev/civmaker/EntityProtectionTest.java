package dev.civmaker;

import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.*;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityProtectionTest {
    private CivStore store;
    private NaturalVillages natural;
    private PillagerGuards guards;
    private EntityProtection protection;
    private CivStore.Village village;
    private Player outsider;
    private World world;
    private Block block;
    private Location location;

    @BeforeEach void setup() {
        store = mock(CivStore.class);
        natural = mock(NaturalVillages.class);
        guards = mock(PillagerGuards.class);
        protection = new EntityProtection(store, natural, guards);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getY()).thenReturn(80);
        location = new Location(world, 0, 80, 0);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(world.getBlockAt(0, 80, 0)).thenReturn(block);
        village = new CivStore.Village("Ashford", UUID.randomUUID(), CivStore.Position.at(block));
        when(store.protectedClaimAt(block)).thenReturn(village);
        when(store.claimAt(any(Location.class))).thenReturn(village);
        outsider = entity(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    private <T extends Entity> T entity(Class<T> type) {
        T entity = mock(type);
        when(entity.getLocation()).thenReturn(location);
        return entity;
    }

    private EntityDamageEvent hit(Entity target, Entity source) {
        DamageSource damage = mock(DamageSource.class);
        when(damage.getCausingEntity()).thenReturn(source);
        when(damage.getDirectEntity()).thenReturn(source);
        return new EntityDamageEvent(target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, damage, 5);
    }

    private void openRaid() {
        when(store.activeWindow(anyLong())).thenReturn(new CivStore.Window(1, Long.MAX_VALUE));
        when(store.raidCovers(any())).thenReturn(true);
    }

    @Test void peacetimeProtectsInhabitantsDecorationsAndVehiclesButPreservesMemberAccess() {
        for (Entity target : List.of(entity(Villager.class), entity(Cow.class), entity(Wolf.class),
                entity(ArmorStand.class), entity(ItemFrame.class), entity(Boat.class))) {
            var attack = hit(target, outsider);
            protection.damage(attack);
            assertTrue(attack.isCancelled(), target.toString());
        }
        when(store.memberOf(outsider.getUniqueId())).thenReturn(village);
        var memberAttack = hit(entity(Cow.class), outsider);
        protection.damage(memberAttack);
        assertFalse(memberAttack.isCancelled());
        var monster = hit(entity(Zombie.class), outsider);
        protection.damage(monster);
        assertFalse(monster.isCancelled());
    }

    @Test void eligibleRaidersCanDamageEntitiesButShieldedTargetsAndRestrictedRaidersCannot() {
        openRaid();
        var attack = hit(entity(Villager.class), outsider);
        protection.damage(attack);
        assertFalse(attack.isCancelled());
        when(store.raidCovers(any())).thenReturn(false);
        attack = hit(entity(Villager.class), outsider);
        protection.damage(attack);
        assertTrue(attack.isCancelled());
        when(store.raidCovers(any())).thenReturn(true);
        when(store.raidRestricted(outsider.getUniqueId())).thenReturn(true);
        attack = hit(entity(Villager.class), outsider);
        protection.damage(attack);
        assertTrue(attack.isCancelled());
        var pvp = hit(entity(Player.class), outsider);
        protection.damage(pvp);
        assertTrue(pvp.isCancelled());
        when(store.registeredVillage(outsider.getUniqueId())).thenReturn(village);
        pvp = hit(entity(Player.class), outsider);
        protection.damage(pvp);
        assertFalse(pvp.isCancelled());
    }

    @Test void guardsCannotBeKilledBeforeRaidsByFriendsOrEnvironmentAndRequireEligibleEnemyAttackers() {
        Pillager guard = entity(Pillager.class);
        when(guards.owner(guard)).thenReturn(village);
        // Military posts remain protected even outside the normal block-protection height band.
        when(store.protectedClaimAt(block)).thenReturn(null);
        var attack = hit(guard, outsider);
        protection.damage(attack);
        assertTrue(attack.isCancelled());
        var lava = new EntityDamageEvent(guard, EntityDamageEvent.DamageCause.LAVA, mock(DamageSource.class), 5);
        protection.damage(lava);
        assertTrue(lava.isCancelled());
        openRaid();
        attack = hit(guard, outsider);
        protection.damage(attack);
        assertFalse(attack.isCancelled());
        when(store.raidRestricted(outsider.getUniqueId())).thenReturn(true);
        attack = hit(guard, outsider);
        protection.damage(attack);
        assertTrue(attack.isCancelled());
        when(store.raidRestricted(outsider.getUniqueId())).thenReturn(false);
        when(store.memberOf(outsider.getUniqueId())).thenReturn(village);
        attack = hit(guard, outsider);
        protection.damage(attack);
        assertTrue(attack.isCancelled());
    }

    @Test void projectilesTntAndPetsCannotBypassPlayerAttribution() {
        openRaid();
        when(store.raidRestricted(outsider.getUniqueId())).thenReturn(true);
        Arrow arrow = entity(Arrow.class);
        when(arrow.getShooter()).thenReturn(outsider);
        TNTPrimed tnt = entity(TNTPrimed.class);
        when(tnt.getSource()).thenReturn(outsider);
        Wolf pet = entity(Wolf.class);
        doReturn(outsider.getUniqueId()).when(pet).getOwnerUniqueId();
        for (Entity source : List.of(arrow, tnt, pet)) {
            var attack = hit(entity(Cow.class), source);
            protection.damage(attack);
            assertTrue(attack.isCancelled());
        }
        var fire = new EntityCombustByEntityEvent(arrow, entity(Cow.class), 5.0f);
        protection.combust(fire);
        assertTrue(fire.isCancelled());
    }

    @Test void unclaimedNaturalVillagesAreProtectedEvenDuringRaidsAndWildernessIsUnchanged() {
        when(store.protectedClaimAt(block)).thenReturn(null);
        when(natural.reserved(eq(world), any())).thenReturn(true);
        openRaid();
        var attack = hit(entity(Villager.class), outsider);
        protection.damage(attack);
        assertTrue(attack.isCancelled());
        when(natural.reserved(eq(world), any())).thenReturn(false);
        attack = hit(entity(Cow.class), outsider);
        protection.damage(attack);
        assertFalse(attack.isCancelled());
    }

    @Test void farmingFallDamageStillWorksButZombiesAndExplosionsCannotDestroyClaimInhabitants() {
        Cow cow = entity(Cow.class);
        var fall = new EntityDamageEvent(cow, EntityDamageEvent.DamageCause.FALL, mock(DamageSource.class), 5);
        protection.damage(fall);
        assertFalse(fall.isCancelled());
        var zombie = hit(entity(Villager.class), entity(Zombie.class));
        protection.damage(zombie);
        assertTrue(zombie.isCancelled());
        var explosion = new EntityDamageEvent(cow, EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, mock(DamageSource.class), 5);
        protection.damage(explosion);
        assertTrue(explosion.isCancelled());
    }

    @Test void protectedEntitiesCannotBeStolenWithLeadsBucketsFishingOrMounting() {
        Cow cow = entity(Cow.class);
        var leash = new PlayerLeashEntityEvent(cow, outsider, outsider, EquipmentSlot.HAND);
        protection.leash(leash);
        assertTrue(leash.isCancelled());
        var unleash = new PlayerUnleashEntityEvent(cow, outsider, EquipmentSlot.HAND);
        protection.unleash(unleash);
        assertTrue(unleash.isCancelled());
        var bucket = new PlayerBucketEntityEvent(outsider, entity(Axolotl.class), mock(ItemStack.class), mock(ItemStack.class), EquipmentSlot.HAND);
        protection.bucket(bucket);
        assertTrue(bucket.isCancelled());
        var fishing = new PlayerFishEvent(outsider, cow, mock(FishHook.class), EquipmentSlot.HAND, PlayerFishEvent.State.CAUGHT_ENTITY);
        protection.fish(fishing);
        assertTrue(fishing.isCancelled());
        var mount = new EntityMountEvent(outsider, entity(Horse.class));
        protection.mount(mount);
        assertTrue(mount.isCancelled());
        var shear = mock(PlayerShearEntityEvent.class);
        when(shear.getPlayer()).thenReturn(outsider);
        doReturn(entity(Sheep.class)).when(shear).getEntity();
        protection.shear(shear);
        verify(shear).setCancelled(true);
    }

    @Test void decorationsAndVehiclesResistPeacetimeTheftAndDestruction() {
        var armor = new PlayerArmorStandManipulateEvent(outsider, entity(ArmorStand.class),
                mock(ItemStack.class), mock(ItemStack.class), EquipmentSlot.HEAD, EquipmentSlot.HAND);
        protection.armorStand(armor);
        assertTrue(armor.isCancelled());
        var hanging = new HangingBreakByEntityEvent(entity(ItemFrame.class), outsider, mock(DamageSource.class));
        protection.hanging(hanging);
        assertTrue(hanging.isCancelled());
        var physics = new HangingBreakEvent(entity(Painting.class), HangingBreakEvent.RemoveCause.PHYSICS);
        protection.hanging(physics);
        assertTrue(physics.isCancelled());
        Boat boat = entity(Boat.class);
        var damage = new VehicleDamageEvent(boat, mock(DamageSource.class), outsider, 5);
        protection.vehicleDamage(damage);
        assertTrue(damage.isCancelled());
        var destroy = new VehicleDestroyEvent(boat, mock(DamageSource.class), outsider);
        protection.vehicleDestroy(destroy);
        assertTrue(destroy.isCancelled());
    }

    @Test void cancelledDamageCannotBeBypassedWithExplosionKnockback() {
        EntityPushedByEntityAttackEvent knockback = mock(EntityPushedByEntityAttackEvent.class);
        doReturn(entity(Cow.class)).when(knockback).getEntity();
        when(knockback.getPushedBy()).thenReturn(outsider);
        protection.knockback(knockback);
        verify(knockback).setCancelled(true);
        clearInvocations(knockback);
        when(store.memberOf(outsider.getUniqueId())).thenReturn(village);
        protection.knockback(knockback);
        verify(knockback, never()).setCancelled(anyBoolean());
    }

    @Test void visitorsCanStillTradeWithVillagersButCannotInteractWithOtherClaimEntities() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(outsider.getInventory()).thenReturn(inventory);
        ItemStack empty = mock(ItemStack.class);
        when(empty.getType()).thenReturn(Material.AIR);
        when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(empty);
        var trade = new PlayerInteractEntityEvent(outsider, entity(Villager.class), EquipmentSlot.HAND);
        protection.interact(trade);
        assertFalse(trade.isCancelled());
        var cow = new PlayerInteractEntityEvent(outsider, entity(Cow.class), EquipmentSlot.HAND);
        protection.interact(cow);
        assertTrue(cow.isCancelled());
    }
}
