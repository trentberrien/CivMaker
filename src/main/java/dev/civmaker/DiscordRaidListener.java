package dev.civmaker;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** Alerts only for completed, uncancelled attacks during an open raid. */
final class DiscordRaidListener implements Listener {
    private final CivMaker plugin;
    private final CivStore store;
    DiscordRaidListener(CivMaker plugin, CivStore store) { this.plugin = plugin; this.store = store; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void blockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        var faction = store.protectedClaimAt(event.getBlock());
        if (faction != null && player.getGameMode() == GameMode.SURVIVAL && store.memberOf(player.getUniqueId()) != faction) {
            plugin.discordRaidAlert(faction, player.getName(), event.getBlock().getLocation(), "block_break");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void combat(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0) return;
        Player attacker = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player ? player : null;
        if (attacker == null || attacker.getGameMode() != GameMode.SURVIVAL) return;
        var faction = store.protectedClaimAt(victim.getLocation().getBlock());
        if (faction != null && store.registeredVillage(victim.getUniqueId()) == faction
                && store.memberOf(attacker.getUniqueId()) != faction) {
            plugin.discordRaidAlert(faction, attacker.getName(), victim.getLocation(), "combat");
        }
    }
}
