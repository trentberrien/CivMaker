package dev.civmaker.companion;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.highlight.ChunkHighlighter;

public final class WorldMapClaims extends ChunkHighlighter {
    public WorldMapClaims() { super(false); }

    @Override public int calculateRegionHash(ResourceKey<Level> dimension, int x, int z) {
        return MapState.INSTANCE.view().revision();
    }
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
    @Override public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int x, int z) {
        return MapTooltips.title(dimension.identifier().toString(), x, z);
    }
    @Override public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int x, int z) {
        return MapTooltips.details(dimension.identifier().toString(), x, z);
    }
    @Override public void addMinimapBlockHighlightTooltips(List<Component> lines, ResourceKey<Level> dimension,
                                                         int x, int z, int width) {
        lines.addAll(MapTooltips.lines(dimension.identifier().toString(), x >> 4, z >> 4));
    }
}
