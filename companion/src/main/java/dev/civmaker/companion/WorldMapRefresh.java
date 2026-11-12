package dev.civmaker.companion;

import xaero.map.WorldMapSession;
import xaero.map.world.MapWorld;

/** Loaded only when Xaero's optional World Map is installed. */
public final class WorldMapRefresh {
    private static MapWorld refreshedWorld;
    private static int refreshedRevision = -1;

    private WorldMapRefresh() {}

    public static void tick() {
        var session = WorldMapSession.getCurrentSession();
        var world = session == null ? null : session.getMapProcessor().getMapWorld();
        if (world == null) {
            refreshedWorld = null;
            refreshedRevision = -1;
            return;
        }
        int revision = MapState.INSTANCE.view().revision();
        if (world != refreshedWorld || revision != refreshedRevision) {
            // calculateRegionHash() is itself cached by Xaero. Updating our revision alone
            // does not invalidate the uploaded map textures or their highlight-presence cache.
            world.clearAllCachedHighlightHashes();
            refreshedWorld = world;
            refreshedRevision = revision;
        }
    }
}
