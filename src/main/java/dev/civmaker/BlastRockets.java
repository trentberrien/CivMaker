package dev.civmaker;

import dev.civmaker.RaidEquipment.Tool;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;

final class BlastRockets implements Listener {
    private static final int MULTISHOT_ROCKETS = 3;
    private static final float BLAST_POWER = 4.0f;
    private static final double DAMAGE_SCALE = 0.6;
    private static final double MAX_DAMAGE = 22.0;
    private static final byte ARMED = 1;
    private static final byte BURST = 2;

    private final RaidEquipment equipment;
    private final NamespacedKey rocketKey;

    BlastRockets(CivMaker plugin, RaidEquipment equipment) {
        this.equipment = equipment;
        rocketKey = new NamespacedKey(plugin, "blast_rocket");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void loadMultishot(EntityLoadCrossbowEvent event) {
        var crossbow = event.getCrossbow();
        if (!(event.getEntity() instanceof Player player) || !crossbow.containsEnchantment(Enchantment.MULTISHOT)) {
            return;
        }

        var inventory = player.getInventory();
        var ammoHand = event.getHand() == EquipmentSlot.HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        var held = inventory.getItem(ammoHand);
        if (held.getType() != Material.FIREWORK_ROCKET || held.isEmpty()) {
            return;
        }

        var rockets = new ArrayList<ItemStack>(MULTISHOT_ROCKETS);
        var remainingStacks = new HashMap<Integer, ItemStack>();
        int fromHand = Math.min(held.getAmount(), MULTISHOT_ROCKETS);
        for (int i = 0; i < fromHand; i++) {
            rockets.add(held.asOne());
        }
        int ammoSlot = ammoHand == EquipmentSlot.HAND ? inventory.getHeldItemSlot() : -1;
        var storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length && rockets.size() < MULTISHOT_ROCKETS; slot++) {
            var stack = storage[slot];
            if (slot == ammoSlot || stack == null || stack.getType() != Material.FIREWORK_ROCKET || stack.isEmpty()) {
                continue;
            }
            int count = Math.min(stack.getAmount(), MULTISHOT_ROCKETS - rockets.size());
            for (int i = 0; i < count; i++) {
                rockets.add(stack.asOne());
            }
            remainingStacks.put(slot, stack.asQuantity(stack.getAmount() - count));
        }

        // Replace the vanilla load, which copies the selected rocket twice for free.
        event.setCancelled(true);
        if (rockets.size() < MULTISHOT_ROCKETS) {
            player.clearActiveItem();
            player.sendActionBar(Component.text("Multishot needs 3 rockets."));
            return;
        }
        if (crossbow.editMeta(CrossbowMeta.class, meta -> meta.setChargedProjectiles(rockets))) {
            inventory.setItem(ammoHand, held.asQuantity(held.getAmount() - fromHand));
            remainingStacks.forEach(inventory::setItem);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, SoundCategory.PLAYERS, 1, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void arm(EntityShootBowEvent event) {
        var bow = event.getBow();
        if (bow != null && bow.getType() == Material.CROSSBOW && event.getProjectile() instanceof Firework firework
                && equipment.identify(event.getConsumable()) == Tool.BLAST_ROCKET) {
            firework.getPersistentDataContainer().set(rocketKey, PersistentDataType.BYTE, ARMED);
        }
    }

    @EventHandler(ignoreCancelled = true)
    void reduceBurstParticles(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Firework firework)) {
            return;
        }
        var item = firework.getItem();
        if (equipment.identify(item) != Tool.BLAST_ROCKET) {
            return;
        }
        // Update old ammo on the launched rocket, preserving its fuse and the player's items.
        var meta = (FireworkMeta) item.getItemMeta();
        var effects = meta.getEffects();
        if (effects.stream().noneMatch(FireworkEffect::hasTrail)) {
            return;
        }
        meta.clearEffects();
        for (var effect : effects) {
            meta.addEffect(effect.hasTrail() ? FireworkEffect.builder()
                    .with(effect.getType())
                    .withColor(effect.getColors())
                    .withFade(effect.getFadeColors())
                    .flicker(effect.hasFlicker())
                    .build() : effect);
        }
        item.setItemMeta(meta);
        firework.setItem(item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void explode(FireworkExplodeEvent event) {
        Firework firework = event.getEntity();
        var data = firework.getPersistentDataContainer();
        if (data.getOrDefault(rocketKey, PersistentDataType.BYTE, (byte) 0) == ARMED) {
            data.set(rocketKey, PersistentDataType.BYTE, BURST);
            firework.getWorld().createExplosion(firework, firework.getLocation(), BLAST_POWER, false, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void damage(EntityDamageEvent event) {
        var source = event.getDamageSource();
        if (!(source.getDirectEntity() instanceof Firework firework)
                || !firework.getPersistentDataContainer().has(rocketKey, PersistentDataType.BYTE)) {
            return;
        }
        DamageType type = source.getDamageType();
        if (type == DamageType.FIREWORKS) {
            // Leave the vanilla firework burst visible while replacing its damage.
            event.setCancelled(true);
        } else if (type == DamageType.EXPLOSION || type == DamageType.PLAYER_EXPLOSION) {
            event.setDamage(Math.min(MAX_DAMAGE, event.getDamage() * DAMAGE_SCALE));
        }
    }
}
