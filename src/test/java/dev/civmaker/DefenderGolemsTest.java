package dev.civmaker;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefenderGolemsTest {
    private DefenderGolems defenders;
    private IronGolem golem;

    @BeforeEach void setup() throws Exception {
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        defenders = new DefenderGolems(plugin, mock(CivStore.class), mock(PillagerGuards.class));
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
}
