package dev.civmaker.companion.mixin;

import dev.civmaker.companion.PlayerVisibility;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.radar.render.element.RadarElementReader;
import xaero.hud.minimap.radar.render.element.RadarRenderContext;

@Mixin(value = RadarElementReader.class, remap = false)
abstract class RadarPlayersMixin {
    @Inject(method = "isHidden(Lnet/minecraft/world/entity/Entity;Lxaero/hud/minimap/radar/render/element/RadarRenderContext;)Z",
            at = @At("RETURN"), cancellable = true)
    private void civmaker$hideEnemy(Entity entity, RadarRenderContext context, CallbackInfoReturnable<Boolean> result) {
        if (entity instanceof Player && PlayerVisibility.hidden(entity.getUUID())) {
            result.setReturnValue(true);
        }
    }
}
