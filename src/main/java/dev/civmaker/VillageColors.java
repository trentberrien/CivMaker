package dev.civmaker;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.DyeColor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class VillageColors {
    private record Swatch(TextColor text, BossBar.Color bar, DyeColor dye) {
        Swatch(int rgb, BossBar.Color bar, DyeColor dye) {
            this(TextColor.color(rgb), bar, dye);
        }
    }

    private static final List<Swatch> DEFAULTS = List.of(
            new Swatch(0x5599FF, BossBar.Color.BLUE, DyeColor.BLUE),
            new Swatch(0x55DD55, BossBar.Color.GREEN, DyeColor.GREEN),
            new Swatch(0xFF5555, BossBar.Color.RED, DyeColor.RED),
            new Swatch(0xFFDD55, BossBar.Color.YELLOW, DyeColor.YELLOW),
            new Swatch(0xAA55FF, BossBar.Color.PURPLE, DyeColor.PURPLE),
            new Swatch(0xFF77BB, BossBar.Color.PINK, DyeColor.PINK),
            new Swatch(0xEEEEEE, BossBar.Color.WHITE, DyeColor.WHITE));
    private static final List<TextColor> BAR_SWATCHES = DEFAULTS.stream().map(Swatch::text).toList();
    private static final List<TextColor> DYE_SWATCHES = Arrays.stream(DyeColor.values())
            .map(dye -> TextColor.color(dye.getColor().asRGB())).toList();

    private VillageColors() {}

    static int next(Set<Integer> used) {
        for (Swatch swatch : DEFAULTS) {
            if (!used.contains(swatch.text().value())) {
                return swatch.text().value();
            }
        }
        // Step through RGB space and keep bright, saturated shades after the native bar colors are in use.
        for (int rgb = 0x9E3779; ; rgb = (rgb + 0x9E3779) & 0xFFFFFF) {
            var hsv = TextColor.color(rgb).asHSV();
            if (hsv.v() >= 0.75f && hsv.s() >= 0.45f && !used.contains(rgb)) {
                return rgb;
            }
        }
    }

    static BossBar.Color bar(int rgb) {
        TextColor nearest = TextColor.nearestColorTo(BAR_SWATCHES, TextColor.color(rgb));
        return DEFAULTS.get(BAR_SWATCHES.indexOf(nearest)).bar();
    }

    static DyeColor banner(int rgb) {
        for (Swatch swatch : DEFAULTS) {
            if (swatch.text().value() == rgb) {
                return swatch.dye();
            }
        }
        TextColor nearest = TextColor.nearestColorTo(DYE_SWATCHES, TextColor.color(rgb));
        return DyeColor.values()[DYE_SWATCHES.indexOf(nearest)];
    }
}
