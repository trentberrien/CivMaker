package dev.civmaker;

import dev.civmaker.CivStore.Village;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.banner.Pattern;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ShieldMeta;

import java.util.ArrayList;
import java.util.List;

record FactionBanner(DyeColor base, List<Layer> layers) {
    record Layer(String pattern, DyeColor color) {}

    static FactionBanner of(Village village) {
        return village.banner != null ? village.banner
                : new FactionBanner(VillageColors.banner(village.color), List.of());
    }

    static FactionBanner copyOf(ItemStack item) {
        if (!isBanner(item)) {
            throw new IllegalArgumentException("Hold the banner you want to copy in your main hand.");
        }
        var meta = (BannerMeta) item.getItemMeta();
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN);
        var layers = meta.getPatterns().stream()
                .map(layer -> new Layer(registry.getKeyOrThrow(layer.getPattern()).asString(), layer.getColor()))
                .toList();
        return new FactionBanner(DyeColor.valueOf(item.getType().name().replace("_BANNER", "")), layers);
    }

    static boolean isBanner(ItemStack item) {
        return item != null && !item.isEmpty() && Tag.ITEMS_BANNERS.isTagged(item.getType());
    }

    ItemStack applyTo(ItemStack item) {
        var patterns = new ArrayList<Pattern>(layers.size());
        if (!layers.isEmpty()) {
            var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN);
            for (Layer layer : layers) {
                var key = NamespacedKey.fromString(layer.pattern());
                var pattern = key == null ? null : registry.get(key);
                if (pattern == null) {
                    throw new IllegalArgumentException("A saved faction banner pattern is unavailable. Ask your faction owner to set a new banner.");
                }
                patterns.add(new Pattern(layer.color(), pattern));
            }
        }
        boolean shield = item.getType() == Material.SHIELD;
        ItemStack result = shield ? item.clone() : item.withType(Material.valueOf(base.name() + "_BANNER"));
        result.editMeta(BannerMeta.class, meta -> {
            meta.setPatterns(patterns);
            if (meta instanceof ShieldMeta shieldMeta) {
                shieldMeta.setBaseColor(base);
            }
        });
        return result;
    }
}
