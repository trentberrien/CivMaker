package dev.civmaker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Changes the displayed XP bar in the horse jump meter's position, leaving real XP untouched. */
final class HarnessDurabilityBar implements Listener {
    private final RaidEquipment equipment;
    private final Set<UUID> viewers = new HashSet<>();
    private boolean heartbeat;

    HarnessDurabilityBar(RaidEquipment equipment) { this.equipment = equipment; }

    void refresh() {
        heartbeat = !heartbeat;
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            if (!player.isDead() && (player.getGameMode() == GameMode.SURVIVAL
                    || player.getGameMode() == GameMode.ADVENTURE)
                    && player.getVehicle() instanceof HappyGhast ghast) {
                int remaining = equipment.harnessRemaining(ghast.getEquipment().getItem(EquipmentSlot.BODY));
                if (remaining >= 0) {
                    if (viewers.add(player.getUniqueId())) {
                        player.sendActionBar(Component.text("Harness durability", NamedTextColor.AQUA));
                    }
                    player.sendExperienceChange(displayProgress(remaining, heartbeat), player.getLevel());
                    continue;
                }
            }
            restore(player);
        }
        viewers.retainAll(online);
    }

    static float displayProgress(int remaining, boolean heartbeat) {
        float progress = Math.clamp((float) remaining / (RaidEquipment.HARNESS_DURABILITY - 1), 0, 1);
        // A subpixel change keeps modern clients from replacing an idle XP bar with the locator bar.
        if (heartbeat) return progress == 1 ? Math.nextDown(1f) : Math.nextUp(progress);
        return progress;
    }

    private void restore(Player player) {
        if (viewers.remove(player.getUniqueId())) {
            // Read the current values: XP may have changed while the durability display was active.
            player.sendExperienceChange(player.getExp(), player.getLevel());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void dismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) restore(player);
    }

    @EventHandler
    void changedWorld(PlayerChangedWorldEvent event) { restore(event.getPlayer()); }

    @EventHandler
    void quit(PlayerQuitEvent event) { viewers.remove(event.getPlayer().getUniqueId()); }

    void clear() {
        Bukkit.getOnlinePlayers().forEach(this::restore);
        viewers.clear();
    }
}
