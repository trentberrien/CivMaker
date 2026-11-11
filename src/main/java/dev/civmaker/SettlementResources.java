package dev.civmaker;

import dev.civmaker.RaidEquipment.Tool;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

final class SettlementResources {
    static final long ITEM_VALUE = 2_000;
    private static final int DAILY_CURRENCY_AMOUNT = 5;

    enum Reward {
        GUNPOWDER("Settlement Gunpowder", Material.GUNPOWDER),
        IRON("Settlement Iron", Material.RAW_IRON),
        OBSIDIAN("Obsidian", Material.OBSIDIAN),
        COPPER("Settlement Copper", Material.COPPER_INGOT),
        TOTEM("Super Totem", Material.TOTEM_OF_UNDYING),
        AMETHYST("Muster Amethyst", Material.AMETHYST_SHARD);

        final String label;
        final Material model;

        Reward(String label, Material model) {
            this.label = label;
            this.model = model;
        }

        static Reward at(String structure, Block bell) {
            return switch (structure) {
                case "minecraft:village_desert" -> GUNPOWDER;
                case "minecraft:village_savanna" -> COPPER;
                case "minecraft:village_taiga" -> TOTEM;
                case "minecraft:village_snowy" -> OBSIDIAN;
                // Meadows generate the same village structure as plains.
                case "minecraft:village_plains" -> bell.getBiome() == Biome.MEADOW ? AMETHYST : IRON;
                default -> IRON;
            };
        }

        static Reward paymentFor(Tool tool) {
            return switch (tool) {
                case GRENADE, C4, BLAST_ROCKET -> GUNPOWDER;
                case VAULT, VAULT_DOOR -> IRON;
                case LOCKPICK, RAM -> COPPER;
                case PILLAGER_GUARD, HAPPY_HARNESS, SUPER_SADDLE -> AMETHYST;
                case SUPER_TOTEM, RADAR, RAID_SHIELD, DEFENDER_GOLEM, WARP_PORTAL -> null;
            };
        }
    }

    private final NamespacedKey currencyKey;
    private final RaidEquipment equipment;

    SettlementResources(CivMaker plugin, RaidEquipment equipment) {
        currencyKey = new NamespacedKey(plugin, "settlement_currency");
        this.equipment = equipment;
    }

    ItemStack stack(Reward reward) {
        if (reward == Reward.TOTEM) {
            return equipment.item(Tool.SUPER_TOTEM, null);
        }
        if (reward == Reward.OBSIDIAN) {
            return new ItemStack(Material.OBSIDIAN, Material.OBSIDIAN.getMaxStackSize());
        }
        // Use the vanilla appearance without inheriting its recipes, brewing or villager trades.
        ItemStack item = new ItemStack(Material.STRUCTURE_VOID, DAILY_CURRENCY_AMOUNT);
        item.editMeta(meta -> {
            meta.setItemModel(NamespacedKey.minecraft(reward.model.name().toLowerCase(Locale.ROOT)));
            meta.displayName(Component.text(reward.label, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(currencyKey, PersistentDataType.STRING, reward.name());
        });
        return item;
    }

    boolean isCurrency(ItemStack item) {
        return item != null && item.getType() == Material.STRUCTURE_VOID
                && item.getPersistentDataContainer().has(currencyKey, PersistentDataType.STRING);
    }

    boolean debit(ItemStack[] contents, Reward reward, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (isCurrency(item) && reward.name().equals(item.getPersistentDataContainer()
                    .get(currencyKey, PersistentDataType.STRING))) {
                int spent = Math.min(item.getAmount(), remaining);
                contents[slot] = spent == item.getAmount() ? null : item.asQuantity(item.getAmount() - spent);
                remaining -= spent;
            }
        }
        return remaining == 0;
    }
}
