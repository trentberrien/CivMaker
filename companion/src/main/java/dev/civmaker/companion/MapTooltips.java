package dev.civmaker.companion;

import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.ArrayList;
import java.util.List;

public final class MapTooltips {
    private MapTooltips() {}

    public static Component title(MapState.OwnedClaim claim) {
        return Component.literal(claim.village().name()).withColor(claim.village().color());
    }

    public static Component details(MapState.OwnedClaim claim) {
        var snapshot = MapState.INSTANCE.view().snapshot();
        return title(claim).copy().append(Component.literal("\nOwner: " + claim.village().ownerName()
                + "\n" + claim.claim().kind() + " | Village points: " + claim.village().points()
                + "\nRaids: " + (snapshot == null ? "Unknown" : snapshot.raidStatus()))
                .withStyle(ChatFormatting.WHITE));
    }

    public static Component title(String dimension, int x, int z) {
        var farm = MapState.INSTANCE.farm(dimension, x, z);
        if (farm != null) {
            return Component.literal(farm.name()).withColor(MapState.FARM_BORDER_COLOR);
        }
        var claim = MapState.INSTANCE.claim(dimension, x, z);
        return claim == null ? null : title(claim);
    }

    public static List<Component> lines(String dimension, int x, int z) {
        List<Component> lines = new ArrayList<>();
        var farm = MapState.INSTANCE.farm(dimension, x, z);
        var claim = MapState.INSTANCE.claim(dimension, x, z);
        if (farm != null) {
            lines.add(Component.literal(farm.name()).withColor(MapState.FARM_BORDER_COLOR));
            lines.add(Component.literal("Farm owner: " + farm.ownerName()));
            lines.add(Component.literal("Farm faction: " + farm.faction()).withColor(farm.color()));
        }
        if (claim != null) {
            lines.add(title(claim));
            lines.add(Component.literal((farm == null ? "Owner: " : "Land owner: ") + claim.village().ownerName()));
        } else if (farm != null) {
            lines.add(Component.literal("Territory: Wilderness").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    public static Component details(String dimension, int x, int z) {
        if (MapState.INSTANCE.farm(dimension, x, z) == null) {
            var claim = MapState.INSTANCE.claim(dimension, x, z);
            return claim == null ? null : details(claim);
        }
        var result = Component.empty();
        for (Component line : lines(dimension, x, z)) {
            if (!result.getSiblings().isEmpty()) { result.append("\n"); }
            result.append(line);
        }
        var claim = MapState.INSTANCE.claim(dimension, x, z);
        if (claim != null) {
            result.append("\n" + claim.claim().kind() + " | Village points: " + claim.village().points());
        }
        var snapshot = MapState.INSTANCE.view().snapshot();
        if (snapshot != null) { result.append("\nRaids: " + snapshot.raidStatus()); }
        return result;
    }
}
