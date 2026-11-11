package dev.civmaker.companion.mixin;

import dev.civmaker.companion.WorldMapClaims;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Xaero's World Map is optional; this target is skipped when it is absent. */
@Pseudo
@Mixin(targets = "xaero.map.highlight.HighlighterRegistry", remap = false)
abstract class WorldMapRegistryMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void civmaker$register(CallbackInfo ci) {
        ((xaero.map.highlight.HighlighterRegistry) (Object) this).register(new WorldMapClaims());
    }
}
