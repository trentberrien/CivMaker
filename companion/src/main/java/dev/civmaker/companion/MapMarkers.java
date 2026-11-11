package dev.civmaker.companion;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;

/** Session-only waypoints; never write to the player's personal waypoint sets. */
final class MapMarkers {
    private static final Identifier ORIGIN = Identifier.parse("civmaker:claims");
    private ThirdPartyWaypoints installed;
    private int lastRevision = -1;
    private boolean shown;

    void clear() {
        if (installed != null) { installed.clear(); }
        installed = null;
        lastRevision = -1;
    }

    void tick() {
        var session = BuiltInHudModules.MINIMAP.getCurrentSession();
        var world = session == null ? null : session.getWorldManager().getCurrentWorld();
        var view = MapState.INSTANCE.view();
        boolean visible = MapConfig.INSTANCE.markers && MapState.INSTANCE.enabled() && view.snapshot() != null
                && world != null && world.getDimId().identifier().toString().equals(view.snapshot().dimension());
        if (!visible) {
            if (installed != null) { clear(); }
            return;
        }
        var waypoints = world.getContainer().getThirdPartyWaypointManager().get(ORIGIN);
        if (installed == waypoints && lastRevision == view.revision() && shown == visible) { return; }
        clear();
        installed = waypoints;
        lastRevision = view.revision();
        shown = visible;
        Map<String, Integer> colors = new HashMap<>();
        view.snapshot().villages().forEach(village -> colors.put(village.id(), village.color()));
        for (var marker : view.snapshot().markers()) {
            Waypoint waypoint = new Waypoint(marker.x(), marker.y(), marker.z(), marker.name(), marker.symbol(),
                    nearestColor(colors.getOrDefault(marker.village(), 0xffffff)));
            installed.add(marker.id(), waypoint);
        }
        if (MapState.INSTANCE.farmsEnabled()) {
            for (var farm : view.farms().values()) {
                String label = farm.name() + " - " + farm.ownerName() + " (" + farm.faction() + ")";
                installed.add("farm:" + farm.x() + ":" + farm.y() + ":" + farm.z(),
                        new Waypoint(farm.x(), farm.y(), farm.z(), label, "F", nearestColor(farm.color())));
            }
        }
    }

    private static WaypointColor nearestColor(int rgb) {
        WaypointColor best = WaypointColor.values()[0];
        int distance = Integer.MAX_VALUE;
        for (var candidate : WaypointColor.values()) {
            int color = candidate.getHex();
            int r = (rgb >> 16 & 255) - (color >> 16 & 255);
            int g = (rgb >> 8 & 255) - (color >> 8 & 255);
            int b = (rgb & 255) - (color & 255);
            int next = r * r + g * g + b * b;
            if (next < distance) { distance = next; best = candidate; }
        }
        return best;
    }
}
