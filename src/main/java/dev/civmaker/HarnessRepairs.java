package dev.civmaker;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.meta.Damageable;

final class HarnessRepairs implements Listener {
    private final RaidEquipment equipment;

    HarnessRepairs(RaidEquipment equipment) { this.equipment = equipment; }

    @EventHandler(priority = EventPriority.HIGH)
    void prepare(PrepareAnvilEvent event) {
        var harness = event.getInventory().getItem(0);
        var ingredient = event.getInventory().getItem(1);
        if (equipment.identify(harness) != RaidEquipment.Tool.HAPPY_HARNESS || ingredient == null) return;
        if (ingredient.getType() == Material.PHANTOM_MEMBRANE) {
            event.setResult(null); // Old items may still carry their former repair material until equipped.
            return;
        }
        if (ingredient.getType() != Material.GHAST_TEAR) return;
        if (!(harness.getItemMeta() instanceof Damageable damage) || damage.getDamage() == 0) {
            event.setResult(null);
            event.getView().setRepairCost(0);
            return;
        }
        // Keep vanilla's rename/enchantment result when available; old harnesses can also be repaired before migration.
        var result = event.getResult() == null ? harness.clone() : event.getResult().clone();
        var meta = (Damageable) result.getItemMeta();
        meta.setDamage(0);
        result.setItemMeta(meta);
        event.setResult(result);
        event.getView().setRepairItemCountCost(1);
        event.getView().setRepairCost(1);
    }
}
