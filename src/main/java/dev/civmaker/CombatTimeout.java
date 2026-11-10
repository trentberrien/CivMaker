package dev.civmaker;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;

final class CombatTimeout implements Listener {
    private static final long COMBAT_GRACE_MILLIS = 10_000;

    private final CivMaker plugin;
    private final NamespacedKey combatUntil;
    private final NamespacedKey timeoutUntil;
    private final NamespacedKey returnMode;

    CombatTimeout(CivMaker plugin) {
        this.plugin = plugin;
        combatUntil = new NamespacedKey(plugin, "recent_combat_until");
        timeoutUntil = new NamespacedKey(plugin, "combat_timeout_until");
        returnMode = new NamespacedKey(plugin, "combat_timeout_return_mode");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0
                && isCombat(player, event.getDamageSource())) {
            player.getPersistentDataContainer().set(combatUntil, PersistentDataType.LONG,
                    System.currentTimeMillis() + COMBAT_GRACE_MILLIS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void death(PlayerDeathEvent event) {
        Player player = event.getEntity();
        var data = player.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        boolean combat = isCombat(player, event.getDamageSource())
                || data.getOrDefault(combatUntil, PersistentDataType.LONG, 0L) > now;
        data.remove(combatUntil);
        int seconds = timeoutSeconds();
        if (seconds > 0 && combat
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
            data.set(timeoutUntil, PersistentDataType.LONG, now + seconds * 1_000L);
            player.sendMessage("Combat death. You can rejoin the fight in " + seconds + " seconds.");
        }
    }

    private static boolean isCombat(Player player, DamageSource source) {
        Entity attacker = source.getCausingEntity();
        if (!(attacker instanceof LivingEntity)) {
            Entity direct = source.getDirectEntity();
            if (direct instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter) {
                attacker = shooter;
            } else if (direct instanceof TNTPrimed tnt) {
                attacker = tnt.getSource();
            } else {
                attacker = direct;
            }
        }
        return attacker instanceof LivingEntity && !attacker.getUniqueId().equals(player.getUniqueId());
    }

    @EventHandler
    void respawn(PlayerPostRespawnEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    void join(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void move(PlayerMoveEvent event) {
        if (event.hasChangedPosition() && locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    void spectate(PlayerStartSpectatingEntityEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    void teleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE && locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    boolean locked(Player player) {
        return remaining(player) > 0;
    }

    private long remaining(Player player) {
        if (timeoutSeconds() <= 0) {
            return 0;
        }
        return player.getPersistentDataContainer().getOrDefault(timeoutUntil, PersistentDataType.LONG, 0L)
                - System.currentTimeMillis();
    }

    private int timeoutSeconds() {
        return plugin.getConfig().getInt("warp-portal.combat-death-timeout-seconds", 60);
    }

    void refresh() {
        Bukkit.getOnlinePlayers().forEach(this::refresh);
    }

    private void refresh(Player player) {
        if (player.isDead()) {
            return;
        }
        var data = player.getPersistentDataContainer();
        long remaining = remaining(player);
        if (remaining <= 0) {
            data.remove(timeoutUntil);
            restoreMode(player);
            return;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) {
            if (!data.has(returnMode)) {
                data.set(returnMode, PersistentDataType.STRING, mode.name());
            }
            player.setGameMode(GameMode.SPECTATOR);
        }
        player.sendActionBar(Component.text("Combat timeout · " + Math.ceilDiv(remaining, 1_000) + "s remaining"));
    }

    void close() {
        Bukkit.getOnlinePlayers().forEach(this::restoreMode);
    }

    private void restoreMode(Player player) {
        var data = player.getPersistentDataContainer();
        String saved = data.get(returnMode, PersistentDataType.STRING);
        if (saved == null) {
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.valueOf(saved));
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            data.remove(returnMode);
        }
    }
}
