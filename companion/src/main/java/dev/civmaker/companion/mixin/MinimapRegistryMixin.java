package dev.civmaker.companion.mixin;

import dev.civmaker.companion.MinimapClaims;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.highlight.HighlighterRegistry;

@Mixin(value = HighlighterRegistry.class, remap = false)
abstract class MinimapRegistryMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void civmaker$register(CallbackInfo ci) {
        ((HighlighterRegistry) (Object) this).register(new MinimapClaims());
    }
}
