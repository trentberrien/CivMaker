package dev.civmaker;

import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import dev.civmaker.RaidEquipment.Tool;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class RaidShields implements Listener {
    private static final String ROOT = "raid-protection.";
    static final long NOTICE_MILLIS = TimeUnit.DAYS.toMillis(1);
    private final CivMaker plugin;
    private final CivStore store;
    private final RaidEquipment equipment;
    private final NamespacedKey binding;

    RaidShields(CivMaker plugin, CivStore store, RaidEquipment equipment) {
        this.plugin = plugin;
        this.store = store;
        this.equipment = equipment;
        binding = new NamespacedKey(plugin, "raid_protection_village");
    }

    long price() {
        return plugin.getConfig().getLong(ROOT + "price", 10000);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean(ROOT + "enabled", true);
    }

    private long hours() {
        return plugin.getConfig().getLong(ROOT + "cooldown-hours", 168);
    }

    private long next(long last) {
        return last == 0 ? 0 : last + TimeUnit.HOURS.toMillis(hours());
    }

    String purchaseError(Village village, long now) {
        if (!enabled()) {
            return "Raid Protection is disabled.";
        }
        if (village == null) {
            return "Join a village before buying Raid Protection.";
        }
        return cooldownError(village.shieldBoughtAt, now, "buy");
    }

    void bind(ItemStack item, Village village) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(binding, PersistentDataType.STRING, village.id.toString()));
    }

    private String cooldownError(long last, long now, String action) {
        long next = next(last);
        return now < next ? "Your village can " + action + " Raid Protection again at " + Instant.ofEpochMilli(next) + "." : null;
    }

    Window reserve(Village village, long now) {
        if (!enabled()) {
            throw new IllegalArgumentException("Raid Protection is disabled.");
        }
        if (store.raidMode() != CivStore.RaidMode.AUTO) {
            throw new IllegalArgumentException("Raid Protection requires scheduled raids in automatic mode.");
        }
        if (store.shielded(village, store.activeWindow(now))) {
            throw new IllegalArgumentException("Your village already has Raid Protection for this window.");
        }
        var upcoming = store.upcomingWindows(now, 8);
        if (village.shieldWindow != null && upcoming.stream().anyMatch(window -> window.start() == village.shieldWindow)) {
            throw new IllegalArgumentException("Your village already has Raid Protection scheduled.");
        }
        Window window = upcoming.stream().filter(next -> next.start() >= now + NOTICE_MILLIS).findFirst().orElse(null);
        if (window == null) {
            throw new IllegalArgumentException("Raid Protection needs a scheduled raid window at least 24 hours away. Your star has not been consumed.");
        }
        String error = cooldownError(village.shieldUsedAt, now, "schedule");
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        village.shieldUsedAt = now;
        village.shieldWindow = window.start();
        store.dirty = true;
        return window;
    }

    @EventHandler
    void use(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()
                || equipment.identify(event.getItem()) != Tool.RAID_SHIELD) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL || !enabled()) {
            player.sendMessage("Raid Protection must be enabled and used in Survival.");
            return;
        }
        Village village = store.registeredVillage(player.getUniqueId());
        ItemStack held = player.getInventory().getItemInMainHand();
        if (village == null || !village.id.toString().equals(held.getPersistentDataContainer().get(binding, PersistentDataType.STRING))) {
            player.sendMessage("Only members of the village that bought this star can activate it.");
            return;
        }
        long now = System.currentTimeMillis();
        Window window;
        try {
            window = reserve(village, now);
        } catch (IllegalArgumentException error) {
            player.sendMessage(error.getMessage());
            return;
        }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() == 0 ? null : held);
        for (Player member : player.getServer().getOnlinePlayers()) {
            if (store.registeredVillage(member.getUniqueId()) == village) {
                member.sendMessage("Raid Protection scheduled for " + village.name + " from "
                        + Instant.ofEpochMilli(window.start()) + " to " + Instant.ofEpochMilli(window.end())
                        + ". Your faction cannot raid during that window.");
            }
        }
    }

    void configure(CommandSender sender, String[] args, boolean admin) {
        if (args.length == 1) {
            sender.sendMessage(String.format(Locale.US, "Raid Protection %s | $%,d | %d-hour buy and scheduling cooldowns | Protects the first scheduled window at least 24 hours away; protected factions cannot raid",
                    enabled() ? "enabled" : "disabled", price(), hours()));
            if (sender instanceof Player player) {
                Village village = store.registeredVillage(player.getUniqueId());
                if (village != null && village.shieldWindow != null) {
                    store.upcomingWindows(System.currentTimeMillis(), 8).stream()
                            .filter(window -> window.start() == village.shieldWindow).findFirst()
                            .ifPresent(window -> sender.sendMessage("Your protected raid window: "
                                    + Instant.ofEpochMilli(window.start()) + " to " + Instant.ofEpochMilli(window.end()) + "."));
                }
            }
            return;
        }
        if (!admin) {
            throw new IllegalArgumentException("Only an admin can configure Raid Protection.");
        }
        String option = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (option.equals("on") || option.equals("off"))) {
            plugin.getConfig().set(ROOT + "enabled", option.equals("on"));
        } else if (args.length == 3 && (option.equals("price") || option.equals("cooldown"))) {
            long value = Long.parseLong(args[2]);
            if (value < 0 || (option.equals("cooldown") && (value == 0 || value > 8760))) {
                throw new IllegalArgumentException("Use a nonnegative price or a cooldown from 1 to 8760 hours.");
            }
            plugin.getConfig().set(ROOT + (option.equals("cooldown") ? "cooldown-hours" : "price"), value);
        } else {
            throw new IllegalArgumentException("Use /civ raidprotection <on|off|price amount|cooldown hours>.");
        }
        plugin.saveConfig();
        configure(sender, new String[] {args[0]}, admin);
    }
}
