package dev.civmaker.companion.mixin;

import dev.civmaker.companion.PlayerVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.radar.tracker.PlayerTrackerMapElement;
import xaero.map.radar.tracker.PlayerTrackerMapElementRenderContext;

@Pseudo
@Mixin(targets = "xaero.map.radar.tracker.PlayerTrackerMapElementReader", remap = false)
abstract class TrackedWorldMapPlayersMixin {
    @Inject(method = "isHidden(Lxaero/map/radar/tracker/PlayerTrackerMapElement;Lxaero/map/radar/tracker/PlayerTrackerMapElementRenderContext;)Z",
            at = @At("RETURN"), cancellable = true)
    private void civmaker$hideEnemy(PlayerTrackerMapElement<?> element, PlayerTrackerMapElementRenderContext context,
                                    CallbackInfoReturnable<Boolean> result) {
        if (PlayerVisibility.hidden(element.getPlayerId())) { result.setReturnValue(true); }
    }
}
