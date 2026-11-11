package dev.villagerrework;

import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.civmaker.CivMaker;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.event.player.PlayerTradeEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Level;

public final class VillagerRework extends JavaPlugin implements Listener {
    private static final String SETTLEMENT_SETTING = "require-owned-settlement";
    private static final String CIVMAKER_REQUIRED = "The settlement requirement needs CivMaker 0.2.2 or newer. "
            + "Villager trades are blocked.";
    private static final BiPredicate<UUID, Location> CIVMAKER_UNAVAILABLE = (player, location) -> false;

    private NamespacedKey tradeRevisionKey;
    private BiPredicate<UUID, Location> ownsSettlement;
    private Function<Location, String> settlementTypeAt;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getServer().getDatapackManager().getEnabledPacks().stream()
                .noneMatch(pack -> pack.getName().equals(TradeBootstrap.PACK_ID))) {
            getLogger().severe("The biome trade pack is missing. Villager trading will be blocked until it loads.");
        }
        tradeRevisionKey = new NamespacedKey(this, "trade_revision");
        applySettlementRequirement(getConfig().getBoolean(SETTLEMENT_SETTING));
        settlementTypeAt = connectSettlementTypes();
        getServer().getPluginManager().registerEvents(this, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(Commands.literal("villagerrework")
                        .requires(source -> source.getSender().hasPermission("villagerrework.admin"))
                        .then(Commands.literal("settlement")
                                .executes(context -> showSettlementRequirement(context.getSource().getSender()))
                                .then(Commands.argument("required", BoolArgumentType.bool())
                                        .executes(context -> setSettlementRequirement(context.getSource().getSender(),
                                                BoolArgumentType.getBool(context, "required")))))
                        .build(), "View or change the villager settlement requirement"));
    }

    private int showSettlementRequirement(CommandSender sender) {
        boolean required = getConfig().getBoolean(SETTLEMENT_SETTING);
        sender.sendMessage(Component.text("Villager settlement requirement is " + (required ? "on." : "off.")));
        if (ownsSettlement == CIVMAKER_UNAVAILABLE) {
            sender.sendMessage(Component.text(CIVMAKER_REQUIRED, NamedTextColor.RED));
        }
        return 1;
    }

    private int setSettlementRequirement(CommandSender sender, boolean required) {
        boolean previous = getConfig().getBoolean(SETTLEMENT_SETTING);
        if (required != previous) {
            getConfig().set(SETTLEMENT_SETTING, required);
            try {
                var configPath = getDataFolder().toPath().resolve("config.yml");
                var pending = Files.createTempFile(configPath.getParent(), "config-", ".tmp");
                try {
                    getConfig().save(pending.toFile());
                    Files.move(pending, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(pending);
                }
            } catch (IOException error) {
                getConfig().set(SETTLEMENT_SETTING, previous);
                getLogger().log(Level.SEVERE, "Could not save the settlement requirement.", error);
                sender.sendMessage(Component.text("Could not save the settlement requirement.", NamedTextColor.RED));
                return 0;
            }
        }
        applySettlementRequirement(required);
        return showSettlementRequirement(sender);
    }

    private void applySettlementRequirement(boolean required) {
        ownsSettlement = required ? connectCivMaker() : (player, location) -> true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)
                || !villager.isAdult() || villager.isSleeping() || villager.isTrading()
                || villager.getProfession().equals(Villager.Profession.NONE)
                || villager.getProfession().equals(Villager.Profession.NITWIT)) {
            return;
        }
        Material held = event.getPlayer().getInventory().getItem(event.getHand()).getType();
        if (held == Material.NAME_TAG || held == Material.LEAD || held == Material.VILLAGER_SPAWN_EGG) {
            return;
        }

        String denial = tradeDenial(event.getPlayer(), villager);
        if (denial != null) {
            event.setCancelled(true);
            tell(event.getPlayer(), denial);
            return;
        }
        try {
            refreshOffers(villager, tradeRegion(villager));
        } catch (RuntimeException error) {
            event.setCancelled(true);
            getLogger().log(Level.SEVERE, "Could not update offers for villager " + villager.getUniqueId(), error);
            tell(event.getPlayer(), "This villager's trade offers could not be updated.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        if (!(event.getVillager() instanceof Villager villager)) {
            return;
        }
        String denial = tradeDenial(event.getPlayer(), villager);
        if (denial == null && needsRefresh(villager, tradeRegion(villager))) {
            denial = "Close this menu and interact with the villager to update its offers.";
        }
        if (denial != null) {
            event.setCancelled(true);
            tell(event.getPlayer(), denial);
        }
    }

    private String tradeDenial(Player player, Villager villager) {
        var location = villager.getLocation();
        var home = villager.getVillagerType().getKey();
        String specialistSettlement = switch (home.getKey()) {
            case "jungle" -> "minecraft:village_savanna";
            case "swamp" -> "minecraft:village_meadow";
            default -> null;
        };
        if (settlementTypeAt != null && specialistSettlement != null) {
            if (!specialistSettlement.equals(settlementTypeAt.apply(location))) {
                return "This villager trades only inside a "
                        + (home.getKey().equals("jungle") ? "Savanna" : "Meadow") + " settlement.";
            }
        } else if (!home.equals(localVariant(location))) {
            return "This villager trades only in its home biome group (" + home.getKey() + ").";
        }
        if (!ownsSettlement.test(player.getUniqueId(), location)) {
            return "You can only trade with villagers inside a CivMaker settlement you own.";
        }
        var pack = getServer().getDatapackManager().getPack(TradeBootstrap.PACK_ID);
        return pack == null || !pack.isEnabled() ? "Villager trades are unavailable because the trade pack is disabled."
                : null;
    }

    private void refreshOffers(Villager villager, String region) {
        if (!needsRefresh(villager, region)) {
            return;
        }
        for (String market : new String[]{"plains", "savanna", "meadow"}) {
            String tag = "villagerrework." + market;
            if (market.equals(region)) {
                villager.addScoreboardTag(tag);
            } else {
                villager.removeScoreboardTag(tag);
            }
        }
        int level = villager.getVillagerLevel();
        try {
            villager.setVillagerLevel(1);
            villager.resetOffers();
            if (level > 1) {
                villager.increaseLevel(level - 1);
            }
        } finally {
            villager.setVillagerLevel(level);
        }
        villager.getPersistentDataContainer().set(tradeRevisionKey, PersistentDataType.STRING, revision(villager, region));
    }

    private boolean needsRefresh(Villager villager, String region) {
        var profession = villager.getProfession();
        return (profession.equals(Villager.Profession.LIBRARIAN) || profession.equals(Villager.Profession.ARMORER)
                || profession.equals(Villager.Profession.CARTOGRAPHER))
                && !revision(villager, region).equals(villager.getPersistentDataContainer()
                        .get(tradeRevisionKey, PersistentDataType.STRING));
    }

    private static String revision(Villager villager, String region) {
        return "3/" + villager.getProfession().getKey() + "/" + villager.getVillagerType().getKey() + "/" + region;
    }

    private String tradeRegion(Villager villager) {
        var location = villager.getLocation();
        String home = villager.getVillagerType().getKey().getKey();
        if (!home.equals("plains") && !home.equals("swamp") && !home.equals("savanna") && !home.equals("jungle")) {
            return "";
        }
        String site = settlementTypeAt == null
                ? location.getBlock().getBiome().getKey().toString()
                : settlementTypeAt.apply(location);
        return switch (site) {
            case "minecraft:village_plains", "minecraft:plains" -> home.equals("plains") ? "plains" : "";
            case "minecraft:village_savanna", "minecraft:savanna" ->
                    home.equals("savanna") || home.equals("jungle") ? "savanna" : "";
            case "minecraft:village_meadow", "minecraft:meadow" ->
                    home.equals("plains") || home.equals("swamp") ? "meadow" : "";
            case null, default -> "";
        };
    }

    private BiPredicate<UUID, Location> connectCivMaker() {
        var plugin = getServer().getPluginManager().getPlugin("CivMaker");
        if (plugin != null && plugin.isEnabled()) {
            try {
                plugin.getClass().getMethod("ownsSettlementAt", UUID.class, Location.class);
                if (plugin instanceof CivMaker civMaker) {
                    return civMaker::ownsSettlementAt;
                }
            } catch (NoSuchMethodException ignored) {
                // An older CivMaker JAR can be installed alongside this optional plugin.
            }
        }
        getLogger().severe(CIVMAKER_REQUIRED);
        return CIVMAKER_UNAVAILABLE;
    }

    private Function<Location, String> connectSettlementTypes() {
        var plugin = getServer().getPluginManager().getPlugin("CivMaker");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            plugin.getClass().getMethod("tradeSettlementTypeAt", Location.class);
            if (plugin instanceof CivMaker civMaker) {
                return civMaker::tradeSettlementTypeAt;
            }
        } catch (NoSuchMethodException ignored) {
            // CivMaker 0.2.9 can still identify purchased settlements.
        }
        try {
            plugin.getClass().getMethod("settlementTypeAt", Location.class);
            if (plugin instanceof CivMaker civMaker) {
                return civMaker::settlementTypeAt;
            }
        } catch (NoSuchMethodException ignored) {
            // Older CivMaker releases can still use the original biome rules.
        }
        return null;
    }

    private static NamespacedKey localVariant(Location location) {
        World world = location.getWorld();
        if (world.getEnvironment() == World.Environment.NETHER || world.getEnvironment() == World.Environment.THE_END) {
            return null;
        }
        var biome = location.getBlock().getBiome().getKey();
        // Match the biome groups vanilla uses when choosing a villager's variant.
        String type = switch (biome.toString()) {
            case "minecraft:desert", "minecraft:badlands", "minecraft:eroded_badlands",
                 "minecraft:wooded_badlands" -> "desert";
            case "minecraft:jungle", "minecraft:bamboo_jungle", "minecraft:sparse_jungle" -> "jungle";
            case "minecraft:savanna", "minecraft:savanna_plateau", "minecraft:windswept_savanna" -> "savanna";
            case "minecraft:snowy_plains", "minecraft:ice_spikes", "minecraft:snowy_beach", "minecraft:snowy_taiga",
                 "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", "minecraft:frozen_river",
                 "minecraft:grove", "minecraft:snowy_slopes", "minecraft:frozen_peaks", "minecraft:jagged_peaks" -> "snow";
            case "minecraft:swamp", "minecraft:mangrove_swamp" -> "swamp";
            case "minecraft:taiga", "minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga",
                 "minecraft:windswept_gravelly_hills", "minecraft:windswept_hills", "minecraft:windswept_forest" -> "taiga";
            default -> "plains";
        };
        return NamespacedKey.minecraft(type);
    }

    private static void tell(Player player, String text) {
        player.sendActionBar(Component.text(text, NamedTextColor.RED));
    }
}
