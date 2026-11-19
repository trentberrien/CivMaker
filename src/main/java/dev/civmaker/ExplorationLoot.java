package dev.civmaker;

import dev.civmaker.RaidEquipment.Tool;
import dev.civmaker.SettlementResources.Reward;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.data.Brushable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** Independent bonus rolls; never modifies vanilla loot tables or chest generation lists. */
final class ExplorationLoot implements Listener {
    record Rule(double chance, int minimum, int maximum, List<Reward> rewards,
                double gearChance, List<Tool> gear) {}

    private final CivMaker plugin;
    private final SettlementResources resources;
    private final RaidEquipment equipment;
    private final RandomGenerator random;
    private final NamespacedKey archaeologyTable;

    ExplorationLoot(CivMaker plugin, SettlementResources resources, RaidEquipment equipment) {
        this(plugin, resources, equipment, ThreadLocalRandom.current());
    }

    ExplorationLoot(CivMaker plugin, SettlementResources resources, RaidEquipment equipment, RandomGenerator random) {
        this.plugin = plugin;
        this.resources = resources;
        this.equipment = equipment;
        this.random = random;
        archaeologyTable = new NamespacedKey(plugin, "exploration_archaeology_table");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void generate(LootGenerateEvent event) {
        if (event.isCancelled() || event.isPlugin() || event.getInventoryHolder() == null) return;
        String table = vanillaTable(event.getLootTable().getKey());
        Location location = event.getLootContext().getLocation();
        Rule rule = chestRule(table, biomeName(location.getBlock()));
        if (rule == null) return;
        List<ItemStack> bonus = roll(rule);
        if (table.equals("chests/ruined_portal") && random.nextDouble() < .20) {
            bonus.add(resources.stack(Reward.OBSIDIAN).asQuantity(random.nextInt(4, 9)));
        } else if (table.equals("chests/bastion_treasure") && random.nextDouble() < .25) {
            bonus.add(resources.stack(Reward.OBSIDIAN).asQuantity(random.nextInt(8, 17)));
        }
        if (bonus.isEmpty()) return;
        afterGeneration(plugin, event, bonus);
    }

    static void afterGeneration(CivMaker plugin, LootGenerateEvent event, List<ItemStack> bonus) {
        InventoryHolder holder = event.getInventoryHolder();
        Material original = holder instanceof BlockInventoryHolder blockHolder ? blockHolder.getBlock().getType() : null;
        Location location = event.getLootContext().getLocation();
        // LootTable.fill shuffles and discards excess stacks AFTER LootGenerateEvent.
        // Defer insertion so no bonus can displace or change the odds of vanilla loot.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled()) return;
            Inventory inventory = null;
            if (holder instanceof BlockInventoryHolder blockHolder) {
                var block = blockHolder.getBlock();
                if (block.getType() == original && block.getState(false) instanceof InventoryHolder current) {
                    inventory = current.getInventory();
                }
            } else if (holder != null && (!(holder instanceof Entity entity) || entity.isValid())) {
                inventory = holder.getInventory();
            }
            deliver(inventory, location, bonus);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void dispense(BlockDispenseLootEvent event) {
        if (event.isCancelled()) return;
        String table = vanillaTable(event.getLootTable().getKey());
        Rule rule = switch (table) {
            case "chests/trial_chambers/reward" -> rule(.60, 1, 3, .05,
                    List.of(Reward.COPPER), Tool.RADAR, Tool.LOCKPICK);
            case "chests/trial_chambers/reward_ominous" -> rule(1, 2, 4, .08,
                    List.of(Reward.COPPER, Reward.IRON), Tool.RADAR, Tool.LOCKPICK);
            default -> null;
        };
        if (rule == null || !(event.getBlock().getState(false) instanceof org.bukkit.block.Vault)) return;
        List<ItemStack> bonus = roll(rule);
        if (bonus.isEmpty()) return;
        // Vaults eject every stack rather than fitting them into an inventory.
        var combined = new ArrayList<>(event.getDispensedLoot());
        combined.addAll(bonus);
        event.setDispensedLoot(combined);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void rememberArchaeology(EntityChangeBlockEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player)
                || !(event.getBlock().getState() instanceof BrushableBlock block)
                || !(block.getBlockData() instanceof Brushable)) return;
        var table = block.getLootTable();
        // Vanilla consumes the table on the FIRST brush, long before dropping its item.
        // Persist its identity on the block so partial brushing survives reloads/restarts.
        if (table != null && archaeologyRule(vanillaTable(table.getKey()), biomeName(block.getBlock())) != null) {
            block.getPersistentDataContainer().set(archaeologyTable, PersistentDataType.STRING, vanillaTable(table.getKey()));
            block.update();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void brushed(BlockDropItemEvent event) {
        if (event.isCancelled() || !(event.getBlockState() instanceof BrushableBlock block)
                || !(block.getBlockData() instanceof Brushable data) || data.getDusted() != data.getMaximumDusted()
                || event.getItems().isEmpty()) return;
        // Broken/falling suspicious blocks do not get exploration rewards.
        String table = block.getPersistentDataContainer().get(archaeologyTable, PersistentDataType.STRING);
        if (table == null) return;
        Rule rule = archaeologyRule(table, biomeName(event.getBlock()));
        if (rule == null) return;
        var bonus = roll(rule);
        if (bonus.isEmpty()) return;
        Location location = event.getItems().getFirst().getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.isCancelled()) deliver(null, location, bonus);
        });
    }

    static void deliver(Inventory inventory, Location location, List<ItemStack> bonus) {
        for (ItemStack item : bonus) {
            if (inventory == null) {
                location.getWorld().dropItemNaturally(location, item);
            } else {
                for (ItemStack overflow : inventory.addItem(item).values()) {
                    location.getWorld().dropItemNaturally(location, overflow);
                }
            }
        }
    }

    List<ItemStack> roll(Rule rule) {
        var bonus = new ArrayList<ItemStack>();
        if (random.nextDouble() < rule.chance()) {
            Reward reward = rule.rewards().get(random.nextInt(rule.rewards().size()));
            int amount = random.nextInt(rule.minimum(), rule.maximum() + 1);
            bonus.add(resources.stack(reward).asQuantity(amount));
        }
        if (!rule.gear().isEmpty() && random.nextDouble() < rule.gearChance()) {
            Tool tool = rule.gear().get(random.nextInt(rule.gear().size()));
            int amount = switch (tool) {
                case BLAST_ROCKET -> 8;
                case RAID_SCAFFOLDING -> 64;
                default -> 1;
            };
            bonus.add(equipment.item(tool, null).asQuantity(amount));
        }
        return bonus;
    }

    private static String vanillaTable(NamespacedKey key) {
        return key.getNamespace().equals("minecraft") ? key.getKey() : "";
    }

    static Rule chestRule(String table, String biome) {
        if (table.startsWith("chests/village/")) {
            Reward reward = switch (table) {
                case "chests/village/village_desert_house" -> Reward.GUNPOWDER;
                case "chests/village/village_savanna_house" -> Reward.COPPER;
                case "chests/village/village_taiga_house" -> Reward.TOTEM;
                case "chests/village/village_snowy_house" -> Reward.OBSIDIAN;
                default -> biomeReward(biome);
            };
            if (reward == null) reward = Reward.IRON;
            return switch (reward) {
                case TOTEM -> rule(.01, 1, 1, 0, List.of(reward));
                case OBSIDIAN -> rule(.20, 4, 8, 0, List.of(reward));
                case AMETHYST -> rule(.15, 1, 1, .01, List.of(reward), Tool.SUPER_SADDLE);
                default -> rule(.15, 1, 1, 0, List.of(reward));
            };
        }
        Rule base = switch (table) {
            case "chests/igloo_chest" -> rule(.50, 8, 16, 0, List.of(Reward.OBSIDIAN));
            case "chests/simple_dungeon" -> rule(.25, 1, 2, .03, List.of(Reward.COPPER, Reward.GUNPOWDER), Tool.LOCKPICK);
            case "chests/abandoned_mineshaft" -> rule(.20, 1, 2, .02, List.of(Reward.COPPER, Reward.IRON), Tool.LOCKPICK);
            case "chests/abandoned_camp_common_chest", "barrels/abandoned_camp_barrel" -> rule(.20, 1, 1, .02, List.of(Reward.COPPER, Reward.AMETHYST), Tool.SUPER_SADDLE);
            case "chests/abandoned_camp_secret_chest" -> rule(.45, 2, 3, .05, List.of(Reward.AMETHYST), Tool.RADAR, Tool.SUPER_SADDLE);
            case "chests/shipwreck_supply", "chests/shipwreck_map" -> rule(.15, 1, 1, .01, List.of(Reward.COPPER), Tool.RADAR);
            case "chests/shipwreck_treasure" -> rule(.40, 1, 3, .03, List.of(Reward.COPPER, Reward.IRON), Tool.RADAR);
            case "chests/underwater_ruin_big", "chests/underwater_ruin_small" -> rule(.25, 1, 2, .02, List.of(Reward.COPPER, Reward.AMETHYST), Tool.RADAR);
            case "chests/buried_treasure" -> rule(.75, 2, 4, .05, List.of(Reward.COPPER, Reward.IRON, Reward.GUNPOWDER, Reward.AMETHYST), Tool.RADAR, Tool.SUPER_TOTEM);
            case "chests/desert_pyramid" -> rule(.35, 1, 3, .03, List.of(Reward.GUNPOWDER), Tool.C4, Tool.GRENADE);
            case "chests/jungle_temple" -> rule(.40, 1, 3, .04, List.of(Reward.AMETHYST, Reward.COPPER), Tool.SUPER_SADDLE);
            case "chests/ruined_portal" -> rule(.30, 1, 2, .03, List.of(Reward.AMETHYST, Reward.GUNPOWDER), Tool.BLAST_ROCKET);
            case "chests/pillager_outpost" -> rule(.50, 1, 3, .05, List.of(Reward.GUNPOWDER, Reward.AMETHYST), Tool.PILLAGER_GUARD);
            case "chests/stronghold_corridor", "chests/stronghold_crossing", "chests/stronghold_library" -> rule(.30, 1, 2, .03, List.of(Reward.IRON, Reward.AMETHYST), Tool.RADAR, Tool.LOCKPICK);
            case "chests/nether_bridge" -> rule(.40, 1, 3, .04, List.of(Reward.GUNPOWDER, Reward.IRON), Tool.GRENADE, Tool.BLAST_ROCKET);
            case "chests/bastion_bridge", "chests/bastion_hoglin_stable", "chests/bastion_other" -> rule(.35, 1, 3, .03, List.of(Reward.IRON, Reward.GUNPOWDER), Tool.C4, Tool.BLAST_ROCKET);
            case "chests/bastion_treasure" -> rule(.75, 3, 5, .05, List.of(Reward.IRON, Reward.GUNPOWDER), Tool.C4, Tool.GRENADE, Tool.BLAST_ROCKET);
            case "chests/woodland_mansion" -> rule(.50, 2, 4, .05, List.of(Reward.AMETHYST, Reward.IRON), Tool.HAPPY_HARNESS, Tool.SUPER_TOTEM);
            case "chests/ancient_city" -> rule(.30, 2, 4, .03, List.of(Reward.AMETHYST, Reward.IRON), Tool.RADAR, Tool.SUPER_TOTEM);
            case "chests/ancient_city_ice_box" -> rule(.20, 4, 8, 0, List.of(Reward.OBSIDIAN));
            case "chests/end_city_treasure" -> rule(.45, 2, 4, .04, List.of(Reward.AMETHYST), Tool.HAPPY_HARNESS, Tool.SUPER_SADDLE);
            case "chests/trial_chambers/supply", "chests/trial_chambers/corridor", "chests/trial_chambers/intersection",
                    "chests/trial_chambers/intersection_barrel", "chests/trial_chambers/entrance", "pots/trial_chambers/corridor" ->
                    rule(.15, 1, 1, .01, List.of(Reward.COPPER), Tool.LOCKPICK);
            default -> null;
        };
        if (base == null) return null;
        return switch (table) {
            case "chests/simple_dungeon", "chests/abandoned_mineshaft", "chests/abandoned_camp_common_chest", "barrels/abandoned_camp_barrel",
                    "chests/abandoned_camp_secret_chest", "chests/ruined_portal", "chests/pillager_outpost",
                    "chests/stronghold_corridor", "chests/stronghold_crossing", "chests/stronghold_library" -> themed(base, biome);
            default -> base;
        };
    }

    static Rule archaeologyRule(String table, String biome) {
        return switch (table) {
            case "archaeology/desert_well" -> rule(.15, 1, 1, 0, List.of(Reward.GUNPOWDER));
            case "archaeology/desert_pyramid" -> rule(.10, 1, 1, 0, List.of(Reward.GUNPOWDER));
            case "archaeology/ocean_ruin_warm", "archaeology/ocean_ruin_cold" -> rule(.08, 1, 1, 0, List.of(Reward.COPPER));
            case "archaeology/trail_ruins_common" -> biomeReward(biome) == Reward.TOTEM
                    ? rule(0, 1, 1, 0, List.of(Reward.TOTEM))
                    : themed(rule(.05, 1, 1, 0, List.of(Reward.COPPER, Reward.AMETHYST)), biome);
            case "archaeology/trail_ruins_rare" -> biomeReward(biome) == Reward.TOTEM
                    ? rule(.01, 1, 1, 0, List.of(Reward.TOTEM))
                    : themed(rule(.20, 1, 2, 0, List.of(Reward.AMETHYST)), biome);
            default -> null;
        };
    }

    private static Rule themed(Rule base, String biome) {
        Reward reward = biomeReward(biome);
        if (reward == null) return base;
        if (reward == Reward.TOTEM) return rule(base.chance() >= .35 ? .02 : .01, 1, 1, 0, List.of(reward));
        if (reward == Reward.OBSIDIAN) return rule(base.chance(), base.chance() >= .40 ? 8 : 4,
                base.chance() >= .40 ? 16 : 8, 0, List.of(reward));
        Tool[] gear = switch (reward) {
            case GUNPOWDER -> new Tool[]{Tool.C4, Tool.GRENADE, Tool.BLAST_ROCKET};
            case IRON -> new Tool[]{Tool.VAULT, Tool.VAULT_DOOR, Tool.DEFENDER_GOLEM};
            case COPPER -> new Tool[]{Tool.LOCKPICK, Tool.RAM, Tool.RADAR, Tool.RAID_SCAFFOLDING};
            case AMETHYST -> new Tool[]{Tool.PILLAGER_GUARD, Tool.HAPPY_HARNESS, Tool.SUPER_SADDLE};
            default -> new Tool[0];
        };
        return rule(base.chance(), base.minimum(), base.maximum(), base.gearChance(), List.of(reward), gear);
    }

    private static String biomeName(Block block) {
        String biome = block.getBiome().getKey().getKey();
        // Cave biomes should not hide the settlement theme of the surrounding region.
        return switch (biome) {
            case "lush_caves", "dripstone_caves", "deep_dark", "sulfur_caves" -> block.getWorld()
                    .getBiome(block.getX(), block.getWorld().getHighestBlockYAt(block.getX(), block.getZ()) + 1,
                            block.getZ()).getKey().getKey();
            default -> biome;
        };
    }

    static Reward biomeReward(String biome) {
        return switch (biome) {
            case "meadow" -> Reward.AMETHYST;
            case "desert", "badlands", "eroded_badlands", "wooded_badlands" -> Reward.GUNPOWDER;
            case "savanna", "savanna_plateau", "windswept_savanna" -> Reward.COPPER;
            case "taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga" -> Reward.TOTEM;
            case "snowy_plains", "ice_spikes", "snowy_slopes", "frozen_peaks", "grove" -> Reward.OBSIDIAN;
            case "plains", "sunflower_plains" -> Reward.IRON;
            default -> null;
        };
    }

    private static Rule rule(double chance, int minimum, int maximum, double gearChance, List<Reward> rewards, Tool... gear) {
        return new Rule(chance, minimum, maximum, rewards, gearChance, List.of(gear));
    }
}
