package dev.civmaker;

import dev.civmaker.CivStore.Village;
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
        var window = store.activeWindow(now);
        if (window == null) {
            player.sendMessage("Activate Raid Protection during a raid window.");
            return;
        }
        if (store.shielded(village, window)) {
            player.sendMessage("Your village already has Raid Protection for this window.");
            return;
        }
        String error = cooldownError(village.shieldUsedAt, now, "use");
        if (error != null) {
            player.sendMessage(error);
            return;
        }
        village.shieldUsedAt = now;
        village.shieldWindow = window.start();
        store.dirty = true;
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() == 0 ? null : held);
        for (Player member : player.getServer().getOnlinePlayers()) {
            if (store.registeredVillage(member.getUniqueId()) == village) {
                member.sendMessage("Raid Protection is active for " + village.name + " until this raid window ends.");
            }
        }
    }

    void configure(CommandSender sender, String[] args, boolean admin) {
        if (args.length == 1) {
            sender.sendMessage(String.format(Locale.US, "Raid Protection %s | $%,d | %d-hour buy and use cooldowns",
                    enabled() ? "enabled" : "disabled", price(), hours()));
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
