package dev.civmaker;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Firework;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Mob;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefenderGolemsTest {
    private DefenderGolems defenders;
    private IronGolem golem;
    private CivStore store;
    private PillagerGuards guards;

    @BeforeEach void setup() throws Exception {
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        store = mock(CivStore.class);
        guards = mock(PillagerGuards.class);
        defenders = new DefenderGolems(plugin, store, guards);
        golem = mock(IronGolem.class);
        var loaded = DefenderGolems.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        @SuppressWarnings("unchecked")
        var tracked = (Map<IronGolem, Object>) loaded.get(defenders);
        tracked.put(golem, mock(Class.forName("dev.civmaker.DefenderGolems$Defender")));
    }

    @Test void hostileMobsCannotAcquireDefenderTargets() {
        var event = new EntityTargetLivingEntityEvent(mock(Zombie.class), golem,
                EntityTargetEvent.TargetReason.CLOSEST_ENTITY);
        defenders.targeted(event);
        assertTrue(event.isCancelled());
    }

    @Test void defendersCannotAcquireHostileMobTargetsButOrdinaryGolemsStillCan() {
        Zombie zombie = mock(Zombie.class);
        var defenderTarget = new EntityTargetLivingEntityEvent(golem, zombie, EntityTargetEvent.TargetReason.CLOSEST_ENTITY);
        defenders.targeted(defenderTarget);
        assertTrue(defenderTarget.isCancelled());
        var ordinaryTarget = new EntityTargetLivingEntityEvent(mock(IronGolem.class), zombie, EntityTargetEvent.TargetReason.CLOSEST_ENTITY);
        defenders.targeted(ordinaryTarget);
        assertFalse(ordinaryTarget.isCancelled());
    }

    private record Combat(Object defender, World world, CivStore.Village village) {}

    private Combat combat() throws Exception {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(golem.getWorld()).thenReturn(world);
        var village = new CivStore.Village("Defenders", UUID.randomUUID(), new CivStore.Position(world.getUID(), 0, 80, 0));
        Class<?> type = Class.forName("dev.civmaker.DefenderGolems$Defender");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object defender;
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class)))
                    .thenReturn(mock(BossBar.class));
            defender = constructor.newInstance(golem, new int[]{0, 80, 0, 0, 80, 0}, village.id.toString(), mock(DefenseStance.RaidAggro.class));
        }
        return new Combat(defender, world, village);
    }

    private boolean hostile(LivingEntity target, Combat combat, boolean alwaysHostile) throws Exception {
        var method = DefenderGolems.class.getDeclaredMethod("isHostile", LivingEntity.class, combat.defender().getClass(),
                CivStore.Village.class, boolean.class, CivStore.Window.class);
        method.setAccessible(true);
        return (boolean) method.invoke(defenders, target, combat.defender(), combat.village(), alwaysHostile,
                new CivStore.Window(1, Long.MAX_VALUE));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void hostileMobsAreIgnoredEvenIfPreviouslyRecordedAsAttackers(boolean alwaysHostile) throws Exception {
        Combat combat = combat();
        for (LivingEntity mob : List.of(mock(Zombie.class), mock(Creeper.class), mock(Pillager.class))) {
            when(mob.getWorld()).thenReturn(combat.world());
            when(mob.isValid()).thenReturn(true);
            var field = combat.defender().getClass().getDeclaredField("attackers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") Set<LivingEntity> attackers = (Set<LivingEntity>) field.get(combat.defender());
            attackers.add(mob);
            assertFalse(hostile(mob, combat, alwaysHostile));
        }
    }

    @Test void eligibleRaidersAndEnemyFactionGuardsRemainCombatTargets() throws Exception {
        Combat combat = combat();
        when(store.activeWindow(anyLong())).thenReturn(new CivStore.Window(1, Long.MAX_VALUE));
        when(store.raidCovers(any())).thenReturn(true);
        Player raider = mock(Player.class);
        when(raider.getUniqueId()).thenReturn(UUID.randomUUID());
        when(raider.getGameMode()).thenReturn(GameMode.SURVIVAL);
        Pillager guard = mock(Pillager.class);
        var attackers = new CivStore.Village("Attackers", UUID.randomUUID(),
                new CivStore.Position(combat.world().getUID(), 32, 80, 0));
        when(guards.owner(guard)).thenReturn(attackers);
        for (LivingEntity enemy : List.of(raider, guard)) {
            when(enemy.getWorld()).thenReturn(combat.world());
            when(enemy.isValid()).thenReturn(true);
            when(enemy.getLocation()).thenReturn(new Location(combat.world(), 1, 80, 1));
            assertTrue(hostile(enemy, combat, true));
        }
        when(store.memberOf(raider.getUniqueId())).thenReturn(combat.village());
        assertFalse(hostile(raider, combat, true));
        when(store.raidCovers(any())).thenReturn(false);
        assertFalse(hostile(guard, combat, true));
    }

    private Combat mountedCombat() throws Exception {
        Combat combat = combat();
        var loaded = DefenderGolems.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        @SuppressWarnings("unchecked") var tracked = (Map<IronGolem, Object>) loaded.get(defenders);
        tracked.put(golem, combat.defender());
        when(store.claimAt(combat.village().beacon.plot())).thenReturn(combat.village());
        when(store.activeWindow(anyLong())).thenReturn(new CivStore.Window(1, Long.MAX_VALUE));
        when(store.raidCovers(any())).thenReturn(true);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(golem.getPersistentDataContainer()).thenReturn(data);
        when(data.getOrDefault(any(), eq(PersistentDataType.BOOLEAN), eq(false))).thenReturn(true);
        return combat;
    }

    private Player rider(Combat combat) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(combat.world());
        when(player.getLocation()).thenReturn(new Location(combat.world(), 18, 90, 0));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isValid()).thenReturn(true);
        return player;
    }

    private HappyGhast mount(Combat combat, Player... riders) {
        HappyGhast ghast = mock(HappyGhast.class);
        when(ghast.getWorld()).thenReturn(combat.world());
        when(ghast.getLocation()).thenReturn(new Location(combat.world(), 18, 86, 0));
        when(ghast.isValid()).thenReturn(true);
        when(ghast.getPassengers()).thenReturn(List.of(riders));
        for (Player rider : riders) when(rider.getVehicle()).thenReturn(ghast);
        return ghast;
    }

    @Test void happyGhastTargetingFollowsItsRidersAndRetaliationStance() throws Exception {
        Combat combat = mountedCombat();
        Player enemy = rider(combat);
        HappyGhast ghast = mount(combat, enemy);
        assertTrue(hostile(ghast, combat, true));
        assertFalse(hostile(ghast, combat, false));
        var field = combat.defender().getClass().getDeclaredField("raidAggro");
        field.setAccessible(true);
        DefenseStance.RaidAggro aggro = (DefenseStance.RaidAggro) field.get(combat.defender());
        when(aggro.includes(1, enemy, null)).thenReturn(true);
        assertTrue(hostile(ghast, combat, false));
        when(store.memberOf(enemy.getUniqueId())).thenReturn(combat.village());
        assertFalse(hostile(ghast, combat, true));
        when(ghast.getPassengers()).thenReturn(List.of());
        assertFalse(hostile(ghast, combat, true));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void boulderHitsDamageBothHappyGhastAndEnemyRiders(boolean hitRider) throws Exception {
        Combat combat = mountedCombat();
        Player enemy = rider(combat);
        Player friendly = rider(combat);
        when(store.memberOf(friendly.getUniqueId())).thenReturn(combat.village());
        Player spectator = rider(combat);
        when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);
        HappyGhast ghast = mount(combat, enemy, friendly, spectator);
        DamageSource.Builder builder = mock(DamageSource.Builder.class);
        DamageSource source = mock(DamageSource.class);
        when(builder.withDamageLocation(any())).thenReturn(builder);
        when(builder.build()).thenReturn(source);
        defenders.damageBoulder(golem, hitRider ? enemy : ghast, 15, builder, new Vector(1, 0, 0));
        verify(ghast).damage(15, source);
        verify(enemy).damage(15, source);
        verify(friendly, never()).damage(anyDouble(), any(DamageSource.class));
        verify(spectator, never()).damage(anyDouble(), any(DamageSource.class));
        verify(builder).withDamageLocation(new Location(combat.world(), 17, 86, 0));
        verify(builder).withDamageLocation(new Location(combat.world(), 17, 90, 0));
    }

    @Test void mountedCombatRemainsSubjectToRaidCoverageAndPlayerModes() throws Exception {
        Combat combat = mountedCombat();
        Player enemy = rider(combat);
        HappyGhast ghast = mount(combat, enemy);
        assertTrue(defenders.canDamageHappyGhast(golem, ghast));
        when(enemy.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertFalse(defenders.canDamageHappyGhast(golem, ghast));
        when(enemy.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(store.raidCovers(CivStore.Plot.at(ghast.getLocation()))).thenReturn(false);
        assertFalse(defenders.canDamageHappyGhast(golem, ghast));
        when(store.raidCovers(any())).thenReturn(true);
        when(store.raidCovers(combat.village().beacon.plot())).thenReturn(false);
        assertFalse(defenders.canDamageHappyGhast(golem, ghast));
        when(store.raidCovers(any())).thenReturn(true);
        when(store.activeWindow(anyLong())).thenReturn(null);
        assertFalse(defenders.canDamageHappyGhast(golem, ghast));
    }

    @Test void protectionAndGolemDamageHandlersAllowBouldersToHitGhastsAndRiders() throws Exception {
        Combat combat = mountedCombat();
        Player enemy = rider(combat);
        HappyGhast ghast = mount(combat, enemy);
        EntityProtection protection = new EntityProtection(store, mock(NaturalVillages.class), guards, defenders);
        for (LivingEntity target : List.of(ghast, enemy)) {
            DamageSource source = mock(DamageSource.class);
            when(source.getCausingEntity()).thenReturn(golem);
            when(source.getDirectEntity()).thenReturn(mock(org.bukkit.entity.BlockDisplay.class));
            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getEntity()).thenReturn(target);
            when(event.getDamageSource()).thenReturn(source);
            protection.damage(event);
            defenders.damage(event);
            verify(event, never()).setCancelled(true);
        }
    }

    @Test void ordinaryGolemsAndPlayerTargetsUseNormalTargeting() {
        Zombie zombie = mock(Zombie.class);
        var ordinary = new EntityTargetLivingEntityEvent(zombie, mock(IronGolem.class),
                EntityTargetEvent.TargetReason.CLOSEST_ENTITY);
        var player = new EntityTargetLivingEntityEvent(zombie, mock(Player.class),
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        defenders.targeted(ordinary);
        defenders.targeted(player);
        assertFalse(ordinary.isCancelled());
        assertFalse(player.isCancelled());
    }

    @Test void loadingHostileMobDropsSavedDefenderTarget() {
        Zombie zombie = mock(Zombie.class);
        when(zombie.getTarget()).thenReturn(golem);
        defenders.added(new EntityAddToWorldEvent(zombie, mock(World.class)));
        verify(zombie).setTarget(null);
    }

    @Test void loadingDefenderClearsAlreadyLoadedHostileTargets() throws Exception {
        var pending = DefenderGolems.class.getDeclaredField("clearHostileTargets");
        pending.setAccessible(true);
        pending.setBoolean(defenders, true);
        World world = mock(World.class);
        Zombie zombie = mock(Zombie.class);
        Zombie other = mock(Zombie.class);
        when(zombie.getTarget()).thenReturn(golem);
        when(other.getTarget()).thenReturn(mock(Player.class));
        when(world.getEntitiesByClass(Mob.class)).thenReturn(List.of(zombie, other));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            defenders.refresh();
            defenders.refresh();
        }
        verify(zombie).setTarget(null);
        verify(other, never()).setTarget(any());
        verify(world).getEntitiesByClass(Mob.class);
    }

    @Test void hostileMeleeAndIncidentalExplosionsCannotDamageDefender() {
        for (var hostile : List.of(mock(Zombie.class), mock(Creeper.class))) {
            DamageSource source = mock(DamageSource.class);
            when(source.getCausingEntity()).thenReturn(hostile);
            when(source.getDirectEntity()).thenReturn(hostile);
            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getEntity()).thenReturn(golem);
            when(event.getDamageSource()).thenReturn(source);
            defenders.damage(event);
            verify(event).setCancelled(true);
        }
    }

    @Test void ordinaryGolemsCanStillTakeHostileMobDamage() {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        DamageSource source = mock(DamageSource.class);
        when(source.getCausingEntity()).thenReturn(mock(Zombie.class));
        when(event.getEntity()).thenReturn(mock(IronGolem.class));
        when(event.getDamageSource()).thenReturn(source);
        defenders.damage(event);
        verify(event, never()).setCancelled(anyBoolean());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rocketsCannotDamageDefendersAsDirectOrCausingEntities(boolean direct) {
        Firework rocket = mock(Firework.class);
        DamageSource source = mock(DamageSource.class);
        when(source.getDirectEntity()).thenReturn(direct ? rocket : null);
        when(source.getCausingEntity()).thenReturn(direct ? mock(Player.class) : rocket);
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(golem);
        when(event.getDamageSource()).thenReturn(source);
        defenders.damage(event);
        verify(event).setCancelled(true);
    }

    @Test void rocketImmunityDoesNotApplyToOrdinaryGolemsOrPlayers() {
        DamageSource source = mock(DamageSource.class);
        when(source.getDirectEntity()).thenReturn(mock(Firework.class));
        when(source.getCausingEntity()).thenReturn(mock(Player.class));
        for (LivingEntity target : List.of(mock(IronGolem.class), mock(Player.class))) {
            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getEntity()).thenReturn(target);
            when(event.getDamageSource()).thenReturn(source);
            defenders.damage(event);
            verify(event, never()).setCancelled(anyBoolean());
        }
    }

    @Test void rocketImmunityDoesNotBlockTntDamage() {
        DamageSource source = mock(DamageSource.class);
        when(source.getDirectEntity()).thenReturn(mock(TNTPrimed.class));
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(golem);
        when(event.getDamageSource()).thenReturn(source);
        defenders.damage(event);
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test void savedDefendersGainHealthWhileKeepingTheirWoundsAndDoNotHealOnReload() {
        AttributeInstance maximum = mock(AttributeInstance.class);
        AtomicReference<Double> base = new AtomicReference<>(100.0);
        AtomicReference<Double> health = new AtomicReference<>(40.0);
        when(maximum.getBaseValue()).thenAnswer(call -> base.get());
        when(maximum.getValue()).thenAnswer(call -> base.get());
        when(golem.getHealth()).thenAnswer(call -> health.get());
        doAnswer(call -> { base.set(call.getArgument(0)); return null; }).when(maximum).setBaseValue(anyDouble());
        doAnswer(call -> { health.set(call.getArgument(0)); return null; }).when(golem).setHealth(anyDouble());
        DefenderGolems.syncHealth(golem, maximum);
        assertEquals(200.0, base.get());
        assertEquals(80.0, health.get());
        health.set(60.0);
        DefenderGolems.syncHealth(golem, maximum);
        assertEquals(60.0, health.get());
        verify(golem, times(1)).setHealth(anyDouble());
    }

    @Test void healthySavedDefendersReceiveTheFullHealthUpgrade() {
        AttributeInstance maximum = mock(AttributeInstance.class);
        when(maximum.getBaseValue()).thenReturn(100.0);
        when(maximum.getValue()).thenReturn(100.0, 200.0);
        when(golem.getHealth()).thenReturn(100.0);
        DefenderGolems.syncHealth(golem, maximum);
        verify(maximum).setBaseValue(200.0);
        verify(golem).setHealth(200.0);
    }

    @Test void loadingOrdinaryGolemsDoesNotChangeTheirHealth() {
        IronGolem ordinary = mock(IronGolem.class);
        when(ordinary.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        defenders.track(ordinary);
        verify(ordinary, never()).getAttribute(any());
        verify(ordinary, never()).setHealth(anyDouble());
    }
}
