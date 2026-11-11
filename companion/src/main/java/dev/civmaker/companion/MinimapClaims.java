package dev.civmaker.companion;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.minimap.highlight.ChunkHighlighter;
import xaero.hud.minimap.info.render.compile.InfoDisplayCompiler;

public final class MinimapClaims extends ChunkHighlighter {
    public MinimapClaims() { super(false); }

    @Override public boolean regionHasHighlights(ResourceKey<Level> dimension, int x, int z) {
        return MapState.INSTANCE.region(dimension.identifier().toString(), x, z);
    }
    @Override public boolean chunkIsHighlit(ResourceKey<Level> dimension, int x, int z) {
        return MapState.INSTANCE.highlighted(dimension.identifier().toString(), x, z);
    }
    @Override protected int[] getColors(ResourceKey<Level> dimension, int x, int z) {
        return MapState.INSTANCE.colors(dimension.identifier().toString(), x, z,
                MapConfig.INSTANCE.fillAlpha(), MapConfig.INSTANCE.borderAlpha());
    }
    @Override public void addChunkHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> dimension,
                                                   int x, int z, int width) {
        for (var line : MapTooltips.lines(dimension.identifier().toString(), x, z)) {
            compiler.addLine(line);
        }
    }
}
