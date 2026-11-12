package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Settlement;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SettlementCapture implements Listener {
    static final int UPDATE_TICKS = 10;
    private static final int CAPTURE_STEPS = 60 * 20 / UPDATE_TICKS;
    private static final int CAPTURE_FIREWORK_FUSE_TICKS = 20;
    static final int RADIUS = 16;
    private static final double PARTICLE_VIEW_RANGE = 32;
    private static final long RECENT_CONTEST_MILLIS = 5 * 60 * 1_000L;
    private final CivStore store;
    private final DefenderGolems defenders;
    private final Plugin plugin;
    private final NamespacedKey captureFireworkKey;
    private final Map<Position, Fight> fights = new HashMap<>();
    private final Map<Position, ContestAlert> contestAlerts = new HashMap<>();
    private Window window;

    private record ContestAlert(Village defender, long until) {}

    private static final class Fight {
        final Village defender;
        final BossBar bar = BossBar.bossBar(Component.empty(), 0, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        final Set<Player> viewers = new HashSet<>();
        Village claimant;
        int progress;
        boolean announced;

        Fight(Village defender) {
            this.defender = defender;
        }

        void show(Set<Player> nearby, Village acting, String status) {
            bar.name(Component.text(status, TextColor.color(acting.color)));
            bar.color(VillageColors.bar(acting.color));
            bar.progress((float) progress / CAPTURE_STEPS);
            for (Player viewer : viewers) {
                if (!nearby.contains(viewer)) {
                    viewer.hideBossBar(bar);
                }
            }
            for (Player viewer : nearby) {
                if (!viewers.contains(viewer)) {
                    viewer.showBossBar(bar);
                }
            }
            viewers.clear();
            viewers.addAll(nearby);
        }

        void hide() {
            viewers.forEach(player -> player.hideBossBar(bar));
            viewers.clear();
        }
    }

    SettlementCapture(Plugin plugin, CivStore store, DefenderGolems defenders) {
        this.plugin = plugin;
        this.store = store;
        this.defenders = defenders;
        captureFireworkKey = new NamespacedKey(plugin, "capture_firework");
    }

    void refresh() {
        long now = System.currentTimeMillis();
        contestAlerts.values().removeIf(alert -> alert.until() <= now);
        Window active = store.activeWindow(now);
        if (!java.util.Objects.equals(window, active)) {
            clear();
            window = active;
        }
        if (active == null) {
            return;
        }

        // Snapshot ownership before any settlement can move to another village.
        Map<Settlement, Village> settlements = new HashMap<>();
        for (Village village : store.villages()) {
            village.settlements.forEach(settlement -> settlements.put(settlement, village));
        }
        Set<Position> available = new HashSet<>();
        for (var entry : settlements.entrySet()) {
            Settlement settlement = entry.getKey();
            if (store.raidCovers(settlement.bell().plot())) {
                available.add(settlement.bell());
                update(settlement, entry.getValue());
            }
        }
        fights.entrySet().removeIf(entry -> {
            if (available.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().hide();
            return true;
        });
    }

    boolean recentlyContested(Position bell, Village defender) {
        ContestAlert alert = contestAlerts.get(bell);
        return alert != null && alert.defender() == defender && alert.until() > System.currentTimeMillis()
                && store.claimAt(bell.plot()) == defender;
    }

    private void update(Settlement settlement, Village defender) {
        Position bell = settlement.bell();
        var world = Bukkit.getWorld(bell.world());
        if (world == null) {
            Fight fight = fights.get(bell);
            if (fight != null) {
                fight.hide();
            }
            return;
        }
        Location center = new Location(world, bell.x() + 0.5, bell.y() + 0.5, bell.z() + 0.5);
        List<Player> viewers = List.copyOf(world.getNearbyPlayers(center, PARTICLE_VIEW_RANGE));
        updateFight(settlement, defender, center, viewers);
        if (!viewers.isEmpty()) {
            Village owner = store.claimAt(bell.plot());
            drawRing(center, viewers, RADIUS, owner.color, 1.0f);
            Fight fight = fights.get(bell);
            if (fight != null && fight.progress > 0) {
                double remaining = 1 - (double) fight.progress / CAPTURE_STEPS;
                drawRing(center, viewers, RADIUS * remaining, fight.claimant.color, 1.7f);
            }
        }
    }

    private void updateFight(Settlement settlement, Village defender, Location center, List<Player> particleViewers) {
        Position bell = settlement.bell();
        var fight = fights.get(bell);
        Set<Player> barViewers = new HashSet<>();
        Set<Village> factions = new HashSet<>();
        for (Player player : particleViewers) {
            if (player.getLocation().distanceSquared(center) > RADIUS * RADIUS || player.isDead()
                    || (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)) {
                continue;
            }
            barViewers.add(player);
            Village faction = store.memberOf(player.getUniqueId());
            if (faction != null && (faction == defender || !store.raidRestricted(player.getUniqueId()))) {
                factions.add(faction);
            }
        }

        boolean enemies = factions.stream().anyMatch(faction -> faction != defender);
        if (enemies) {
            contestAlerts.put(bell, new ContestAlert(defender, System.currentTimeMillis() + RECENT_CONTEST_MILLIS));
        }
        if (fight == null && !enemies) {
            return;
        }
        for (Player player : center.getWorld().getPlayers()) {
            if (settlement.plots().contains(Plot.at(player.getLocation()))) {
                barViewers.add(player);
            }
        }
        if (fight == null || fight.defender != defender) {
            if (fight != null) {
                fight.hide();
            }
            fight = new Fight(defender);
            fights.put(bell, fight);
        }

        String point = settlement.displayName(center.getBlock()) + " (" + bell.x() + ", " + bell.y() + ", " + bell.z() + ")";
        if (enemies && plugin instanceof CivMaker civ) {
            String attackers = factions.stream().filter(faction -> faction != defender).map(faction -> faction.name)
                    .sorted().collect(java.util.stream.Collectors.joining(", "));
            civ.discordRaidAlert(defender, attackers, center, "settlement_capture");
        }
        if (enemies && !fight.announced) {
            Bukkit.broadcast(Component.text(defender.name + "'s " + point + " is under raid!", TextColor.color(defender.color)));
            fight.announced = true;
        }

        if (fight.claimant != null && !store.hasVillage(fight.claimant.id)) {
            fight.claimant = null;
            fight.progress = 0;
        }
        boolean golemContests = enemies && defenders.contests(settlement, defender);
        if (factions.size() > 1 || golemContests) {
            Village color = fight.claimant == null ? defender : fight.claimant;
            fight.show(barViewers, color, point + (golemContests ? " | Contested by Defender Golem" : " | Contested"));
        } else if (factions.isEmpty()) {
            if (fight.progress > 0) {
                fight.show(barViewers, fight.claimant, point + " | Capture paused");
            } else {
                fight.hide();
            }
        } else {
            Village acting = factions.iterator().next();
            if (acting == defender || (fight.claimant != null && fight.claimant != acting)) {
                fight.progress = Math.max(0, fight.progress - (acting == defender ? 2 : 1));
                if (fight.progress == 0) {
                    fight.claimant = null;
                }
                if (acting == defender && fight.progress == 0) {
                    fight.hide();
                } else {
                    fight.show(barViewers, acting, point + " | " + acting.name + " clearing capture");
                }
            } else {
                fight.claimant = acting;
                fight.progress++;
                if (fight.progress == CAPTURE_STEPS) {
                    store.transferSettlement(settlement, defender, acting);
                    closeCapturedContainers(settlement, acting);
                    fight.hide();
                    fights.remove(bell);
                    Bukkit.broadcast(Component.text(acting.name + " captured " + defender.name + "'s " + point + "!",
                            TextColor.color(acting.color)));
                    launchFirework(center, acting.color);
                } else {
                    fight.show(barViewers, acting, point + " | " + acting.name + " capturing");
                }
            }
        }
    }

    private void launchFirework(Location center, int color) {
        var item = new ItemStack(Material.FIREWORK_ROCKET);
        item.editMeta(FireworkMeta.class, meta -> {
            meta.setPower(1);
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.fromRGB(color)).build());
        });
        center.getWorld().spawn(center.clone().add(0, 1, 0), Firework.class, rocket -> {
            rocket.setItem(item);
            rocket.setTicksToDetonate(CAPTURE_FIREWORK_FUSE_TICKS);
            rocket.setPersistent(false);
            rocket.getPersistentDataContainer().set(captureFireworkKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    @EventHandler(ignoreCancelled = true)
    void captureFireworkDamage(EntityDamageEvent event) {
        if (event.getDamageSource().getDirectEntity() instanceof Firework rocket
                && rocket.getPersistentDataContainer().has(captureFireworkKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    private static void drawRing(Location center, List<Player> viewers, double radius, int color, float size) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(color), size);
        int points = Math.max(8, (int) Math.ceil(2 * Math.PI * radius / 1.5));
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            center.getWorld().spawnParticle(Particle.DUST, viewers, null, x, center.getY(), z,
                    1, 0, 0, 0, 0, dust, true);
        }
    }

    private void closeCapturedContainers(Settlement settlement, Village owner) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (store.memberOf(player.getUniqueId()) != owner
                    && Protection.inventoryBlocks(player.getOpenInventory().getTopInventory()).stream()
                    .anyMatch(block -> settlement.plots().contains(Plot.at(block)))) {
                player.closeInventory();
            }
        }
    }

    void clear() {
        fights.values().forEach(Fight::hide);
        fights.clear();
    }
}
