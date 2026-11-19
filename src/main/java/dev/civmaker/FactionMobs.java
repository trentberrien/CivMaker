package dev.civmaker;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Village;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Faction ownership travels with the mob, independently of its current claim. */
final class FactionMobs implements Listener {
    private static final NamespacedKey FACTION = new NamespacedKey("civmaker", "mob_faction");
    private static final NamespacedKey NAME = new NamespacedKey("civmaker", "mob_name");
    private final CivStore store;
    private final FactionNames names;
    private final Set<Mob> loaded = new HashSet<>();

    FactionMobs(CivMaker plugin, CivStore store) {
        this.store = store;
        names = new FactionNames(plugin.getConfig().getStringList("name-filter.additional-blocked-terms"),
                plugin.getConfig().getStringList("name-filter.allowed-words"));
    }

    void tame(Player player, String[] args) {
        Village village = store.memberOf(player.getUniqueId());
        require(village != null, "Join a faction before claiming a mob.");
        require(player.getVehicle() instanceof Mob, "Ride a mob in your faction's claimed plot and use /civ tame [name].");
        Mob mob = (Mob) player.getVehicle();
        // Claims apply at every height here, including flying mounts and settlement claims.
        require(store.claimAt(mob.getLocation()) == village && store.claimAt(player.getLocation()) == village,
                "Bring the mob into your own faction's claimed plot before using /civ tame.");
        String name = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip() : null;
        if (name != null) {
            String error = names.error(name);
            require(error == null, error);
        } else {
            name = mob.getPersistentDataContainer().get(NAME, PersistentDataType.STRING);
            if (name == null) name = defaultName(mob);
        }
        mob.getPersistentDataContainer().set(FACTION, PersistentDataType.STRING, village.id.toString());
        mob.getPersistentDataContainer().set(NAME, PersistentDataType.STRING, name);
        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        if (mob instanceof Tameable tameable) tameable.setTamed(true);
        loaded.add(mob);
        label(mob, village);
        player.sendMessage("Claimed " + name + " for " + village.name + ".");
    }

    Village owner(Entity entity) {
        if (!(entity instanceof Mob)) return null;
        String id = entity.getPersistentDataContainer().get(FACTION, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return store.village(UUID.fromString(id));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    boolean canRide(Player player, Mob mob, Village owner) {
        Village faction = store.memberOf(player.getUniqueId());
        if (faction == owner || faction != null && store.claimAt(mob.getLocation()) == faction) return true;
        return store.activeWindow(System.currentTimeMillis()) != null
                && !store.raidRestricted(player.getUniqueId()) && store.raidCovers(Plot.at(mob.getLocation()));
    }

    void track(Entity entity) {
        if (entity instanceof Mob mob && mob.getPersistentDataContainer().has(FACTION, PersistentDataType.STRING)) {
            Village village = owner(mob);
            if (village == null) release(mob);
            else {
                loaded.add(mob);
                label(mob, village);
            }
        }
    }

    void refresh() {
        loaded.removeIf(mob -> {
            if (!mob.isValid()) return true;
            Village village = owner(mob);
            if (village == null) {
                // Disbanding releases the mob and removes its obsolete faction tag.
                release(mob);
                return true;
            }
            label(mob, village);
            return false;
        });
    }

    @EventHandler
    void added(EntityAddToWorldEvent event) {
        track(event.getEntity());
    }

    @EventHandler
    void removed(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Mob mob) loaded.remove(mob);
    }

    private void label(Mob mob, Village village) {
        Component label = Component.text(mobName(mob), NamedTextColor.WHITE)
                .append(Component.text(" [" + village.name + "]", TextColor.color(village.color)));
        if (!label.equals(mob.customName())) mob.customName(label);
        mob.setCustomNameVisible(true);
    }

    private void release(Mob mob) {
        mob.customName(Component.text(mobName(mob), NamedTextColor.WHITE));
        mob.getPersistentDataContainer().remove(FACTION);
        mob.getPersistentDataContainer().remove(NAME);
    }

    private String mobName(Mob mob) {
        return mob.getPersistentDataContainer().getOrDefault(NAME, PersistentDataType.STRING, defaultName(mob));
    }

    private static String defaultName(Mob mob) {
        return Arrays.stream(mob.getType().name().toLowerCase(Locale.ROOT).split("_"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1)).collect(Collectors.joining(" "));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
