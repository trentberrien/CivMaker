package dev.civmaker;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RaidEquipment implements Listener {
    private static final String HARNESS_HEALTH_LORE = "Increases max health to five times normal.";
    private static final double MAX_HORSE_SPEED = 0.3375;
    private static final double MAX_HORSE_JUMP = 1.0;
    private static final double SADDLED_HORSE_MAX_HEALTH = 96.0;

    enum Tool {
        PILLAGER_GUARD("pillager_guard", "Pillager Guard", Material.EMERALD, NamedTextColor.GREEN,
                "Right-click the ground in your village to post a guard.",
                "Fires rockets at outsiders in or near your claims during raids.",
                "Sneak-right-click your guard to toggle its defense stance."),
        DEFENDER_GOLEM("defender_golem", "Defender Golem", Material.IRON_BLOCK, NamedTextColor.GOLD,
                "Right-click your faction's settlement bell to deploy.",
                "A giant iron golem with a boss health bar and boulder attacks.",
                "Sneak-right-click to toggle the stance for the whole settlement."),
        LOCKPICK("lockpick", "Lockpick", Material.STICK, NamedTextColor.AQUA,
                "Follow a glowing trail to pick a lock.", LockpickPuzzle.BREAK_ODDS, LockpickPuzzle.SUCCESS_ODDS),
        C4("c4", "C4", Material.TNT, NamedTextColor.RED,
                "TNT-strength blast that spares secured storage.", "Use in unclaimed land or during a raid."),
        RAM("ram", "Battering Ram", Material.BLAZE_ROD, NamedTextColor.GOLD,
                "Break down a protected door.", "Use during an active raid."),
        VAULT("vault", "Secure Vault Kit", Material.IRON_INGOT, NamedTextColor.GREEN,
                "Limit lockpick loot from storage in your village.", "Right-click a chest, barrel, or shulker box."),
        VAULT_DOOR("vault_door", "Vault Door", Material.IRON_DOOR, NamedTextColor.GREEN,
                "Only village members can operate it by hand.", "Place inside your own village."),
        GRENADE("grenade", "Grenade", Material.TNT, NamedTextColor.RED,
                "Right-click to throw lit TNT with a five-second fuse.", "Can be thrown from a Happy Ghast."),
        HAPPY_HARNESS("happy_harness", "Happy Harness", Material.YELLOW_HARNESS, NamedTextColor.GOLD,
                "Doubles a Happy Ghast's flying speed under a player's control.", HARNESS_HEALTH_LORE),
        BLAST_ROCKET("blast_rocket", "Blast Rockets", Material.FIREWORK_ROCKET, NamedTextColor.LIGHT_PURPLE,
                "Crossbow ammo with a TNT-sized blast.", "A close hit can take out an unarmored player."),
        SUPER_TOTEM("super_totem", "Super Totem", Material.TOTEM_OF_UNDYING, NamedTextColor.GOLD,
                "Consumed on death to keep your items.", "You still drop 10% of carried money, rounded down to $1,000."),
        RADAR("radar", "Radar", Material.COMPASS, NamedTextColor.AQUA,
                "Hold in either hand to scan nearby chunks in chat.",
                "Shows village borders, exposed enemies, and enemy guards.",
                "Carry in your inventory to reveal enemy claims, farms, and map markers."),
        WARP_PORTAL("warp_portal", "Warp Portal", Material.ENDER_EYE, NamedTextColor.LIGHT_PURPLE,
                "Right-click the floor beside a lit Nether portal in your claim.",
                "Faction members can use the trader to travel between their claims."),
        SUPER_SADDLE("super_saddle", "Super Saddle", Material.SADDLE, NamedTextColor.GOLD,
                "Camels gain triple max health and top horse speed while ridden.",
                "Horses gain the highest natural speed and jump strength.",
                "Horses gain 96 max health (48 hearts)."),
        RAID_SHIELD("raid_shield", "Raid Protection", Material.NETHER_STAR, NamedTextColor.AQUA,
                "Right-click during a raid to protect your village for the rest of that window.",
                "Only your village can activate this star.");

        final String id;
        final String label;
        final Material material;
        final NamedTextColor color;
        final List<String> description;

        Tool(String id, String label, Material material, NamedTextColor color, String... description) {
            this.id = id;
            this.label = label;
            this.material = material;
            this.color = color;
            this.description = List.of(description);
        }
    }

    private final NamespacedKey itemKey;
    private final NamespacedKey vaultKey;
    private final NamespacedKey legacyHarnessSpeedKey;
    private final AttributeModifier harnessSpeed;
    private final AttributeModifier harnessHealth;
    private final NamespacedKey saddleSpeedKey;
    private final NamespacedKey saddleJumpKey;
    private final AttributeModifier saddleHealth;
    private final Set<LivingEntity> riddenSpeedBoosts = new HashSet<>();
    private final Set<Camel> camelDashReleases = new HashSet<>();

    RaidEquipment(CivMaker plugin) {
        itemKey = new NamespacedKey(plugin, "raid_item_v1");
        vaultKey = new NamespacedKey(plugin, "secure_vault_v1");
        legacyHarnessSpeedKey = new NamespacedKey(plugin, "happy_harness_speed");
        harnessSpeed = new AttributeModifier(new NamespacedKey(plugin, "happy_harness_ridden_speed"), 1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        harnessHealth = new AttributeModifier(new NamespacedKey(plugin, "happy_harness_health"), 4.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        saddleSpeedKey = new NamespacedKey(plugin, "super_saddle_speed");
        saddleJumpKey = new NamespacedKey(plugin, "super_saddle_jump");
        saddleHealth = new AttributeModifier(new NamespacedKey(plugin, "super_saddle_health"), 2.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    ItemStack item(Tool tool, String priceLabel) {
        ItemStack item = new ItemStack(tool.material, tool == Tool.BLAST_ROCKET ? 64 : 1);
        item.editMeta(meta -> {
            if (tool == Tool.BLAST_ROCKET) {
                FireworkMeta rocket = (FireworkMeta) meta;
                rocket.setPower(2);
                rocket.addEffect(FireworkEffect.builder()
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(Color.fromRGB(11743532), Color.fromRGB(15435844))
                        .withFade(Color.fromRGB(14602026))
                        .build());
            }
            meta.displayName(Component.text(tool.label, tool.color).decoration(TextDecoration.ITALIC, false));
            meta.setEnchantmentGlintOverride(true);
            var lore = new ArrayList<Component>();
            for (String line : tool.description) {
                lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            if (priceLabel != null) {
                lore.add(Component.text(priceLabel, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            } else {
                meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, tool.id);
            }
            meta.lore(lore);
        });
        if (tool == Tool.SUPER_TOTEM) {
            item.unsetData(DataComponentTypes.DEATH_PROTECTION);
        }
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void keepInventoryWithSuperTotem(PlayerDeathEvent event) {
        var inventory = event.getEntity().getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (identify(item) == Tool.SUPER_TOTEM) {
                inventory.setItem(slot, item.getAmount() == 1 ? null : item.clone().subtract());
                event.setKeepInventory(true);
                event.getDrops().clear();
                return;
            }
        }
    }

    @EventHandler
    void mountEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (event.getEntity() instanceof HappyGhast ghast && event.getEquipmentChanges().containsKey(EquipmentSlot.BODY)) {
            syncHarness(ghast);
        } else if (event.getEntity() instanceof AbstractHorse mount
                && event.getEquipmentChanges().containsKey(EquipmentSlot.SADDLE)) {
            syncSaddle(mount);
        }
    }

    @EventHandler
    void mountLoaded(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof HappyGhast ghast) {
            syncHarness(ghast);
        } else if (event.getEntity() instanceof AbstractHorse mount) {
            syncSaddle(mount);
        }
    }

    void syncHarness(HappyGhast ghast) {
        var equipment = ghast.getEquipment();
        var item = equipment.getItem(EquipmentSlot.BODY);
        boolean equipped = identify(item) == Tool.HAPPY_HARNESS;
        if (equipped) {
            // Older harnesses applied their speed modifier directly from the item.
            var meta = item.getItemMeta();
            var modifiers = meta.getAttributeModifiers(Attribute.FLYING_SPEED);
            if (modifiers != null) {
                for (var modifier : modifiers) {
                    if (modifier.getKey().equals(legacyHarnessSpeedKey)) {
                        meta.removeAttributeModifier(Attribute.FLYING_SPEED, modifier);
                        item.setItemMeta(meta);
                        equipment.setItem(EquipmentSlot.BODY, item);
                        break;
                    }
                }
            }
            var speed = ghast.getAttribute(Attribute.FLYING_SPEED);
            if (speed.getModifier(legacyHarnessSpeedKey) != null) {
                speed.removeModifier(legacyHarnessSpeedKey);
            }
        } else {
            clearRiddenSpeed(ghast);
        }
        syncMountHealth(ghast, harnessHealth, equipped);
    }

    void syncSaddle(AbstractHorse mount) {
        if (!(mount instanceof Camel || mount instanceof Horse)) {
            return;
        }
        boolean equipped = wears(mount, EquipmentSlot.SADDLE, Tool.SUPER_SADDLE);
        if (mount instanceof Camel) {
            syncMountHealth(mount, saddleHealth, equipped);
            if (!equipped || !riddenSpeedBoosts.contains(mount)) {
                riddenSpeedBoosts.remove(mount);
                syncSaddleStat(mount.getAttribute(Attribute.MOVEMENT_SPEED), saddleSpeedKey, MAX_HORSE_SPEED, false);
            }
        } else {
            var health = mount.getAttribute(Attribute.MAX_HEALTH);
            syncMountHealth(mount, new AttributeModifier(saddleHealth.getKey(),
                    SADDLED_HORSE_MAX_HEALTH - health.getBaseValue(), AttributeModifier.Operation.ADD_NUMBER), equipped);
            syncSaddleStat(mount.getAttribute(Attribute.MOVEMENT_SPEED), saddleSpeedKey, MAX_HORSE_SPEED, equipped);
            syncSaddleStat(mount.getAttribute(Attribute.JUMP_STRENGTH), saddleJumpKey, MAX_HORSE_JUMP, equipped);
        }
    }

    @EventHandler
    void camelJumpInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        if (!(player.getVehicle() instanceof Camel camel) || camel.getPassengers().indexOf(player) != 0) {
            return;
        }
        if (event.getInput().isJump()) {
            clearRiddenSpeed(camel);
        } else if (player.getCurrentInput().isJump()) {
            // Jump release and the dash command arrive in separate packets.
            camelDashReleases.add(camel);
        }
    }

    void refreshRiddenSpeeds() {
        Set<LivingEntity> boostedNow = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Entity vehicle = player.getVehicle();
            if (!(vehicle instanceof Camel || vehicle instanceof HappyGhast) || vehicle.getPassengers().indexOf(player) != 0) {
                continue;
            }
            if (vehicle instanceof Camel camel && !camel.isDashing() && !player.getCurrentInput().isJump()
                    && !camelDashReleases.contains(camel)
                    && wears(camel, EquipmentSlot.SADDLE, Tool.SUPER_SADDLE)) {
                syncSaddleStat(camel.getAttribute(Attribute.MOVEMENT_SPEED), saddleSpeedKey, MAX_HORSE_SPEED, true);
                boostedNow.add(camel);
            } else if (vehicle instanceof HappyGhast ghast && wears(ghast, EquipmentSlot.BODY, Tool.HAPPY_HARNESS)) {
                var speed = ghast.getAttribute(Attribute.FLYING_SPEED);
                if (speed.getModifier(harnessSpeed.getKey()) == null) {
                    speed.addTransientModifier(harnessSpeed);
                }
                boostedNow.add(ghast);
            }
        }
        riddenSpeedBoosts.removeIf(mount -> {
            if (boostedNow.contains(mount)) {
                return false;
            }
            removeRiddenSpeedModifier(mount);
            return true;
        });
        riddenSpeedBoosts.addAll(boostedNow);
        camelDashReleases.clear();
    }

    void clearRiddenSpeeds() {
        riddenSpeedBoosts.forEach(this::removeRiddenSpeedModifier);
        riddenSpeedBoosts.clear();
        camelDashReleases.clear();
    }

    private boolean wears(LivingEntity mount, EquipmentSlot slot, Tool tool) {
        return identify(mount.getEquipment().getItem(slot)) == tool;
    }

    private void clearRiddenSpeed(LivingEntity mount) {
        if (riddenSpeedBoosts.remove(mount)) {
            removeRiddenSpeedModifier(mount);
        }
    }

    private void removeRiddenSpeedModifier(LivingEntity mount) {
        if (mount instanceof Camel camel) {
            camel.getAttribute(Attribute.MOVEMENT_SPEED).removeModifier(saddleSpeedKey);
        } else {
            mount.getAttribute(Attribute.FLYING_SPEED).removeModifier(harnessSpeed.getKey());
        }
    }

    private void syncSaddleStat(AttributeInstance stat, NamespacedKey key, double target, boolean equipped) {
        boolean boosted = stat.getModifier(key) != null;
        if (equipped && !boosted) {
            stat.addTransientModifier(new AttributeModifier(key, target - stat.getBaseValue(),
                    AttributeModifier.Operation.ADD_NUMBER));
        } else if (!equipped && boosted) {
            stat.removeModifier(key);
        }
    }

    private void syncMountHealth(LivingEntity mount, AttributeModifier modifier, boolean equipped) {
        var maxHealth = mount.getAttribute(Attribute.MAX_HEALTH);
        boolean boosted = maxHealth.getModifier(modifier.getKey()) != null;
        if (equipped == boosted) {
            return;
        }
        // Preserve the health fraction and save the modifier so loading does not clamp health to the base maximum.
        double healthFraction = mount.getHealth() / maxHealth.getValue();
        if (equipped) {
            maxHealth.addModifier(modifier);
        } else {
            maxHealth.removeModifier(modifier.getKey());
        }
        mount.setHealth(maxHealth.getValue() * healthFraction);
    }

    boolean hasRadar(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (identify(item) == Tool.RADAR) {
                return true;
            }
        }
        return identify(player.getInventory().getItemInOffHand()) == Tool.RADAR;
    }

    Tool identify(ItemStack item) {
        if (item == null || item.getAmount() == 0) {
            return null;
        }
        String id = item.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        for (Tool tool : Tool.values()) {
            if (item.getType() == tool.material && tool.id.equals(id)) {
                return tool;
            }
        }
        return null;
    }

    boolean isVault(Block block) {
        Container container = storageAt(block);
        return container != null && container.getPersistentDataContainer().has(vaultKey, PersistentDataType.BYTE);
    }

    Container storageAt(Block block) {
        Material type = block.getType();
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || Tag.SHULKER_BOXES.isTagged(type)) {
            return block.getState(false) instanceof Container container ? container : null;
        }
        return null;
    }

    boolean reinforce(Container container) {
        container.customName(Component.text("Secure Vault", NamedTextColor.GREEN));
        container.getPersistentDataContainer().set(vaultKey, PersistentDataType.BYTE, (byte) 1);
        return container.update(false, false);
    }
}
