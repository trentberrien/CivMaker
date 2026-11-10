package dev.civmaker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Vault;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class FarmStationLoot implements Listener {
    private static final double DROP_CHANCE = 0.05;
    private static final Set<NamespacedKey> CHEST_TABLES = Set.of(
            LootTables.ANCIENT_CITY.getKey(),
            LootTables.BASTION_BRIDGE.getKey(),
            LootTables.BASTION_HOGLIN_STABLE.getKey(),
            LootTables.BASTION_OTHER.getKey(),
            LootTables.BASTION_TREASURE.getKey());

    private final NamespacedKey stationKey;
    final NamespacedKey nameKey;

    FarmStationLoot(CivMaker plugin) {
        stationKey = new NamespacedKey(plugin, "farm_station");
        nameKey = new NamespacedKey(plugin, "farm_station_name");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void generateChestLoot(LootGenerateEvent event) {
        var table = event.getLootTable();
        if (!event.isPlugin() && table != null && CHEST_TABLES.contains(table.getKey())
                && ThreadLocalRandom.current().nextDouble() < DROP_CHANCE) {
            event.getLoot().add(createStation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void generateVaultLoot(BlockDispenseLootEvent event) {
        var table = event.getLootTable();
        if (table == null || !LootTables.TRIAL_CHAMBERS_REWARD_OMINOUS.getKey().equals(table.getKey())
                || !(event.getBlock().getBlockData() instanceof org.bukkit.block.data.type.Vault data)
                || !data.isOminous() || !(event.getBlock().getState(false) instanceof Vault vault)
                || vault.getKeyItem().getType() != Material.OMINOUS_TRIAL_KEY
                || ThreadLocalRandom.current().nextDouble() >= DROP_CHANCE) {
            return;
        }

        var loot = new ArrayList<>(event.getDispensedLoot());
        loot.add(createStation());
        event.setDispensedLoot(loot);
    }

    boolean isStation(ItemStack item) {
        return item.getType() == Material.LODESTONE
                && item.getPersistentDataContainer().has(stationKey, PersistentDataType.BYTE);
    }

    ItemStack createStation() {
        return createStation(null);
    }

    ItemStack createStation(String name) {
        ItemStack station = ItemStack.of(Material.LODESTONE);
        station.editMeta(meta -> {
            meta.itemName(Component.text(name == null ? "Farm Station" : name, NamedTextColor.GOLD));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(stationKey, PersistentDataType.BYTE, (byte) 1);
            if (name != null) {
                meta.getPersistentDataContainer().set(nameKey, PersistentDataType.STRING, name);
            }
        });
        return station;
    }
}
