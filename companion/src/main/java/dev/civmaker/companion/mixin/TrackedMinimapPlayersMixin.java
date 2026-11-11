package dev.civmaker.companion.mixin;

import dev.civmaker.companion.PlayerVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElement;
import xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElementReader;
import xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElementRenderContext;

@Mixin(value = PlayerTrackerMinimapElementReader.class, remap = false)
abstract class TrackedMinimapPlayersMixin {
    @Inject(method = "isHidden(Lxaero/hud/minimap/player/tracker/PlayerTrackerMinimapElement;Lxaero/hud/minimap/player/tracker/PlayerTrackerMinimapElementRenderContext;)Z",
            at = @At("RETURN"), cancellable = true)
    private void civmaker$hideEnemy(PlayerTrackerMinimapElement<?> element, PlayerTrackerMinimapElementRenderContext context,
                                    CallbackInfoReturnable<Boolean> result) {
        if (PlayerVisibility.hidden(element.getPlayerId())) { result.setReturnValue(true); }
    }
}
