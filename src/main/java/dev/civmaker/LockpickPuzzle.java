package dev.civmaker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.random.RandomGenerator;

final class LockpickPuzzle implements InventoryHolder {
    enum Result { IGNORED, ADVANCED, BROKEN, SOLVED }

    // Inventory slots from the upper left of the trail to its right edge.
    private static final int[] PATH = {
            10, 19, 28, 29, 30, 21, 12, 13, 14, 23, 32, 33, 34, 25
    };
    private static final int STEP_TICKS = 4;
    private static final int BREAK_CHANCE_PERCENT = 5;
    static final String BREAK_ODDS = "Each correct click has a " + BREAK_CHANCE_PERCENT + "% break chance.";
    static final String SUCCESS_ODDS = String.format(Locale.ROOT, "Overall success chance is %.2f%%.",
            100 * Math.pow(1 - BREAK_CHANCE_PERCENT / 100.0, PATH.length));

    private final Inventory inventory = Bukkit.createInventory(this, 45, Component.text("Picking the Lock"));
    private final RandomGenerator random;
    private int next;
    private int lastClickTick;
    private boolean broken;

    LockpickPuzzle(RandomGenerator random) {
        this.random = random;
        inventory.setItem(4, icon(Material.TRIPWIRE_HOOK, "Follow the glowing pane", NamedTextColor.GOLD, false));
        for (int slot : PATH) {
            inventory.setItem(slot, icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "Waiting", NamedTextColor.AQUA, false));
        }
        showNext();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    Result click(int slot, int tick) {
        if (broken || next == PATH.length || slot != PATH[next] || (next > 0 && tick - lastClickTick < STEP_TICKS)) {
            return Result.IGNORED;
        }
        if (random.nextInt(100) < BREAK_CHANCE_PERCENT) {
            broken = true;
            inventory.setItem(slot, icon(Material.RED_STAINED_GLASS_PANE, "Lockpick broke", NamedTextColor.RED, false));
            return Result.BROKEN;
        }

        lastClickTick = tick;
        inventory.setItem(slot, icon(Material.LIME_STAINED_GLASS_PANE, "Completed", NamedTextColor.GREEN, false));
        next++;
        if (next == PATH.length) {
            return Result.SOLVED;
        }
        showNext();
        return Result.ADVANCED;
    }

    private void showNext() {
        inventory.setItem(PATH[next], icon(Material.YELLOW_STAINED_GLASS_PANE, "Click next", NamedTextColor.YELLOW, true));
    }

    private static ItemStack icon(Material material, String name, NamedTextColor color, boolean glint) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            if (glint) {
                meta.setEnchantmentGlintOverride(true);
            }
        });
        return item;
    }
}
