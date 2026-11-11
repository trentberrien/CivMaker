package dev.civmaker.companion;

import java.util.UUID;
import net.minecraft.client.Minecraft;

/** Applies only to Xaero map elements. Never changes normal in-world player visibility. */
public final class PlayerVisibility {
    private PlayerVisibility() {}

    public static boolean hidden(UUID id) {
        var viewer = Minecraft.getInstance().player;
        return (viewer == null || !viewer.getUUID().equals(id)) && MapState.INSTANCE.hidePlayer(id);
    }
}
