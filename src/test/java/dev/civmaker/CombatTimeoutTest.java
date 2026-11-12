package dev.civmaker;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CombatTimeoutTest {
    private CivMaker plugin;
    private CombatTimeout combat;
    private Player victim;
    private Player attacker;
    private YamlConfiguration config;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Map<NamespacedKey, Long> saved = new HashMap<>();
    private final NamespacedKey hitKey = new NamespacedKey("civmaker", "combat_last_player_damage");
    private final NamespacedKey timeoutKey = new NamespacedKey("civmaker", "combat_timeout_until");

    @BeforeEach void setup() {
        plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        combat = new CombatTimeout(plugin, now::get);
        victim = mock(Player.class);
        attacker = mock(Player.class);
        when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());
        when(victim.getGameMode()).thenReturn(GameMode.SURVIVAL);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(victim.getPersistentDataContainer()).thenReturn(data);
        doAnswer(call -> {
            saved.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(data).set(any(NamespacedKey.class), eq(PersistentDataType.LONG), anyLong());
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.LONG)))
                .thenAnswer(call -> saved.get(call.getArgument(0)));
        when(data.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.LONG), anyLong()))
                .thenAnswer(call -> saved.getOrDefault(call.getArgument(0), call.getArgument(2)));
        doAnswer(call -> saved.remove(call.getArgument(0))).when(data).remove(any(NamespacedKey.class));
    }

    private DamageSource source(Entity causing, Entity direct) {
        DamageSource source = mock(DamageSource.class);
        when(source.getCausingEntity()).thenReturn(causing);
        when(source.getDirectEntity()).thenReturn(direct);
        return source;
    }

    private EntityDamageEvent hit(DamageSource source) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamageSource()).thenReturn(source);
        when(event.getFinalDamage()).thenReturn(5.0);
        return event;
    }

    private PlayerDeathEvent death(DamageSource source) {
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamageSource()).thenReturn(source);
        return event;
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 59_999, 60_000})
    void environmentalDeathWithinOneMinuteOfPlayerDamageTriggersTimeout(long elapsed) {
        combat.damage(hit(source(attacker, attacker)));
        now.addAndGet(elapsed);
        combat.death(death(source(null, null)));
        assertEquals(now.get() + 60_000, saved.get(timeoutKey));
        assertTrue(combat.locked(victim));
        assertFalse(saved.containsKey(hitKey));
    }

    @Test void olderPlayerDamageDoesNotCountAndMobHitsDoNotRefreshIt() {
        combat.damage(hit(source(attacker, attacker)));
        now.addAndGet(40_000);
        combat.damage(hit(source(mock(Zombie.class), null)));
        now.addAndGet(20_001);
        combat.death(death(source(null, null)));
        assertFalse(combat.locked(victim));
        assertFalse(saved.containsKey(timeoutKey));
        assertFalse(saved.containsKey(hitKey));
    }

    @Test void everySuccessfulPlayerHitRefreshesTheMinute() {
        combat.damage(hit(source(attacker, attacker)));
        now.addAndGet(50_000);
        combat.damage(hit(source(attacker, attacker)));
        now.addAndGet(50_000);
        combat.death(death(source(mock(Zombie.class), null)));
        assertTrue(combat.locked(victim));
    }

    @Test void suicideAfterPlayerCombatStillTriggersTimeout() {
        combat.damage(hit(source(attacker, attacker)));
        now.addAndGet(30_000);
        combat.death(death(source(victim, victim)));
        assertTrue(combat.locked(victim));
    }

    @Test void playerArrowsAndPlayerSourcedTntAreRecorded() {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(attacker);
        combat.damage(hit(source(null, arrow)));
        assertEquals(now.get(), saved.get(hitKey));
        saved.clear();
        TNTPrimed tnt = mock(TNTPrimed.class);
        when(tnt.getSource()).thenReturn(attacker);
        combat.damage(hit(source(tnt, tnt)));
        assertEquals(now.get(), saved.get(hitKey));
    }

    @Test void directPlayerKillStillCountsWithoutARecordedHit() {
        combat.death(death(source(attacker, attacker)));
        assertTrue(combat.locked(victim));
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void damageAbsorbedByExtraHeartsStillCountsAsCombat() {
        EntityDamageEvent absorbed = hit(source(attacker, attacker));
        when(absorbed.getFinalDamage()).thenReturn(0.0);
        when(absorbed.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)).thenReturn(true);
        when(absorbed.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION)).thenReturn(-5.0);
        combat.damage(absorbed);
        now.addAndGet(30_000);
        combat.death(death(source(null, null)));
        assertTrue(combat.locked(victim));
    }

    @Test void cancelledOrZeroDamageAndSelfDamageDoNotCreateOrRefreshTags() {
        EntityDamageEvent cancelled = hit(source(attacker, attacker));
        when(cancelled.isCancelled()).thenReturn(true);
        EntityDamageEvent zero = hit(source(attacker, attacker));
        when(zero.getFinalDamage()).thenReturn(0.0);
        EntityDamageEvent self = hit(source(victim, victim));
        combat.damage(cancelled);
        combat.damage(zero);
        combat.damage(self);
        assertFalse(saved.containsKey(hitKey));
        combat.damage(hit(source(attacker, attacker)));
        long original = now.get();
        now.addAndGet(45_000);
        combat.damage(cancelled);
        combat.damage(zero);
        combat.damage(self);
        assertEquals(original, saved.get(hitKey));
    }

    @Test void tagIsClearedAtDeathAndCannotCarryIntoTheNextLife() {
        combat.damage(hit(source(attacker, attacker)));
        combat.death(death(source(null, null)));
        long timeout = saved.get(timeoutKey);
        now.addAndGet(10_000);
        combat.death(death(source(null, null)));
        assertEquals(timeout, saved.get(timeoutKey));
        assertFalse(saved.containsKey(hitKey));
    }

    @Test void reconnectAndListenerRestartKeepTheOriginalTagTimestamp() {
        combat.damage(hit(source(attacker, attacker)));
        long hitAt = now.get();
        now.addAndGet(59_000);
        combat = new CombatTimeout(plugin, now::get);
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(victim);
        combat.join(join);
        assertEquals(hitAt, saved.get(hitKey));
        combat.death(death(source(null, null)));
        assertTrue(combat.locked(victim));
    }

    @Test void timeoutDurationSettingDoesNotChangeTheOneMinuteLookback() {
        config.set("warp-portal.combat-death-timeout-seconds", 15);
        combat.damage(hit(source(attacker, attacker)));
        now.addAndGet(59_000);
        combat.death(death(source(null, null)));
        assertEquals(now.get() + 15_000, saved.get(timeoutKey));
    }

    @Test void disabledTimeoutStillClearsTheTagAndCancelledDeathsLeaveItIntact() {
        config.set("warp-portal.combat-death-timeout-seconds", 0);
        combat.damage(hit(source(attacker, attacker)));
        PlayerDeathEvent cancelled = death(source(null, null));
        when(cancelled.isCancelled()).thenReturn(true);
        combat.death(cancelled);
        assertTrue(saved.containsKey(hitKey));
        combat.death(death(source(null, null)));
        assertFalse(saved.containsKey(hitKey));
        assertFalse(saved.containsKey(timeoutKey));
    }

    @Test void unrelatedDeathsAndNonSurvivalPlayersDoNotReceiveTimeouts() {
        combat.death(death(source(null, null)));
        assertFalse(combat.locked(victim));
        when(victim.getGameMode()).thenReturn(GameMode.CREATIVE);
        combat.damage(hit(source(attacker, attacker)));
        assertFalse(saved.containsKey(hitKey));
        combat.death(death(source(attacker, attacker)));
        assertFalse(combat.locked(victim));
    }
}
