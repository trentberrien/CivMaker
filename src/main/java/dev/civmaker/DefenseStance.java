package dev.civmaker;

import dev.civmaker.CivStore.Settlement;
import dev.civmaker.CivStore.Village;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class DefenseStance {
    static final class RaidAggro {
        private final PersistentDataContainer data;
        private final NamespacedKey raidKey;
        private final NamespacedKey attackersKey;
        private final NamespacedKey scopeKey;
        private Long raid;
        private String scope;
        private final Set<String> attackers;

        private RaidAggro(Mob defender, NamespacedKey raidKey, NamespacedKey attackersKey, NamespacedKey scopeKey) {
            this.data = defender.getPersistentDataContainer();
            this.raidKey = raidKey;
            this.attackersKey = attackersKey;
            this.scopeKey = scopeKey;
            raid = data.get(raidKey, PersistentDataType.LONG);
            scope = data.get(scopeKey, PersistentDataType.STRING);
            attackers = new HashSet<>(data.getOrDefault(attackersKey, PersistentDataType.LIST.strings(), List.of()));
        }

        // A stance change resets retaliation for the whole settlement, even for unloaded defenders.
        boolean sync(Settlement settlement) {
            String current = settlement == null ? null : settlement.bell() + "/" + settlement.defense().stanceRevision;
            if (Objects.equals(scope, current)) {
                return false;
            }
            clear();
            scope = current;
            if (scope == null) {
                data.remove(scopeKey);
            } else {
                data.set(scopeKey, PersistentDataType.STRING, scope);
            }
            return true;
        }

        void provoke(long raid, Player player, Village village) {
            if (!active(raid)) {
                attackers.clear();
                this.raid = raid;
                data.set(raidKey, PersistentDataType.LONG, raid);
            }
            if (attackers.add(aggressor(player, village))) {
                data.set(attackersKey, PersistentDataType.LIST.strings(), List.copyOf(attackers));
            }
        }

        boolean includes(long raid, Player player, Village village) {
            return active(raid) && attackers.contains(aggressor(player, village));
        }

        boolean active(long raid) {
            return this.raid != null && this.raid == raid;
        }

        void clear() {
            raid = null;
            attackers.clear();
            data.remove(raidKey);
            data.remove(attackersKey);
        }

        private static String aggressor(Player player, Village village) {
            return village == null ? "player:" + player.getUniqueId() : "village:" + village.id;
        }
    }

    private final NamespacedKey key;
    private final NamespacedKey raidKey;
    private final NamespacedKey attackersKey;
    private final NamespacedKey scopeKey;
    private final CivStore store;
    private final boolean defaultHostile;

    DefenseStance(CivMaker plugin, CivStore store, boolean defaultHostile) {
        this.store = store;
        key = new NamespacedKey(plugin, "defense_always_hostile");
        raidKey = new NamespacedKey(plugin, "defense_aggro_raid");
        attackersKey = new NamespacedKey(plugin, "defense_aggro_attackers");
        scopeKey = new NamespacedKey(plugin, "defense_aggro_scope");
        this.defaultHostile = defaultHostile;
    }

    RaidAggro raidAggro(Mob defender) {
        return new RaidAggro(defender, raidKey, attackersKey, scopeKey);
    }

    static boolean wantsToggle(PlayerInteractEntityEvent event) {
        return event.getHand() == EquipmentSlot.HAND && event.getPlayer().isSneaking();
    }

    boolean alwaysHostile(Mob defender, Settlement settlement) {
        return settlement == null
                ? defender.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BOOLEAN, defaultHostile)
                : settlement.defense().alwaysHostile;
    }

    void toggle(PlayerInteractEntityEvent event, Mob defender, String name, Settlement settlement) {
        boolean alwaysHostile = !alwaysHostile(defender, settlement);
        if (settlement == null) {
            defender.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, alwaysHostile);
        } else {
            settlement.defense().alwaysHostile = alwaysHostile;
            settlement.defense().stanceRevision++;
            store.dirty = true;
            name = "Settlement defense";
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(name + " stance set to " + (alwaysHostile ? "Always Hostile." : "Retaliate Only."));
    }
}
