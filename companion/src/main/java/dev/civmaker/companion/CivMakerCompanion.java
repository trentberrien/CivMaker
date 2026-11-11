package dev.civmaker.companion;

import com.google.gson.Gson;
import dev.civmaker.map.protocol.MapProtocol;
import dev.civmaker.map.protocol.MapSnapshot;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.hud.minimap.BuiltInHudModules;

import java.nio.charset.StandardCharsets;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class CivMakerCompanion implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("CivMaker Companion");
    private final MapProtocol.Assembler assembler = new MapProtocol.Assembler();
    private final MapMarkers markers = new MapMarkers();
    private final Gson json = new Gson();
    private ClientLevel level;
    private int helloTicks;
    private long lastInvalidLog;
    private int refreshedRevision = -1;
    private Object refreshedHandler;

    @Override public void onInitializeClient() {
        MapConfig.load();
        PayloadTypeRegistry.clientboundPlay().register(MapPayload.TYPE, MapPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MapPayload.Hello.TYPE, MapPayload.Hello.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(MapPayload.TYPE, (payload, context) -> {
            // Fabric invokes play payload handlers on the client thread.
            try {
                byte[] data = assembler.accept(payload.frame(), System.currentTimeMillis());
                if (data != null) {
                    MapSnapshot snapshot = json.fromJson(new String(data, StandardCharsets.UTF_8), MapSnapshot.class);
                    Identifier.parse(snapshot.dimension());
                    if (context.client().level != null
                            && context.client().level.dimension().identifier().toString().equals(snapshot.dimension())) {
                        MapState.INSTANCE.replace(snapshot);
                    }
                }
            } catch (Exception error) {
                assembler.clear();
                long now = System.currentTimeMillis();
                if (now - lastInvalidLog > 30_000) {
                    lastInvalidLog = now;
                    LOG.warn("Ignored invalid CivMaker map snapshot", error);
                }
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != level) {
                reset();
                level = client.level;
            }
            MapState.INSTANCE.setRadarServer(level != null && ClientPlayNetworking.canSend(MapPayload.Hello.TYPE));
            if (level != null && helloTicks-- <= 0) {
                request();
                helloTicks = MapState.INSTANCE.view().snapshot() == null ? 40 : 600;
            }
            var session = BuiltInHudModules.MINIMAP.getCurrentSession();
            var highlights = session == null ? null : session.getProcessor().getMinimapWriter().getDimensionHighlightHandler();
            int revision = MapState.INSTANCE.view().revision();
            if (highlights != null && (refreshedRevision != revision || refreshedHandler != highlights)) {
                highlights.requestRefresh();
                refreshedRevision = revision;
                refreshedHandler = highlights;
            }
            markers.tick();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                literal("civmap").executes(context -> {
                    MapConfig config = MapConfig.INSTANCE;
                    config.enabled = !config.enabled;
                    MapState.INSTANCE.setEnabled(config.enabled);
                    config.save();
                    context.getSource().sendFeedback(Component.literal("CivMaker map overlays: "
                            + (config.enabled ? "on" : "off")));
                    return 1;
                }).then(literal("status").executes(context -> {
                    var view = MapState.INSTANCE.view();
                    context.getSource().sendFeedback(Component.literal(view.snapshot() == null
                            ? "CivMaker map: waiting for a compatible CivMaker server."
                            : "CivMaker map: " + view.claims().size() + " plots, "
                            + view.snapshot().villages().size() + " villages, " + view.farms().size() + " farms. Raids: " + view.snapshot().raidStatus()
                            + ". Overlays " + (MapState.INSTANCE.enabled() ? "on" : "off") + "."));
                    if (view.snapshot() != null && !MapState.INSTANCE.claimsAvailable()) {
                        context.getSource().sendFeedback(Component.literal(view.snapshot().radar() == null
                                ? "Update the CivMaker server to unlock Radar map access."
                                : "Carry a CivMaker Radar in your inventory or offhand to see claims and enemy players."));
                    }
                    return 1;
                })).then(literal("refresh").executes(context -> {
                    request();
                    context.getSource().sendFeedback(Component.literal("Requested a CivMaker map refresh."));
                    return 1;
                })).then(literal("farms").executes(context -> {
                    MapConfig.INSTANCE.farms = !MapConfig.INSTANCE.farms;
                    MapState.INSTANCE.setFarmsEnabled(MapConfig.INSTANCE.farms);
                    MapConfig.INSTANCE.save();
                    context.getSource().sendFeedback(Component.literal("CivMaker farm highlights and markers: "
                            + (MapConfig.INSTANCE.farms ? "on" : "off")));
                    return 1;
                })).then(literal("markers").executes(context -> {
                    MapConfig.INSTANCE.markers = !MapConfig.INSTANCE.markers;
                    MapConfig.INSTANCE.save();
                    context.getSource().sendFeedback(Component.literal("CivMaker markers: "
                            + (MapConfig.INSTANCE.markers ? "on" : "off")));
                    return 1;
                }))));
        LOG.info("Ready for CivMaker servers (map protocol {})", MapProtocol.VERSION);
    }

    private void request() {
        if (ClientPlayNetworking.canSend(MapPayload.Hello.TYPE)) {
            ClientPlayNetworking.send(new MapPayload.Hello());
        }
    }

    private void reset() {
        markers.clear();
        MapState.INSTANCE.clear();
        MapState.INSTANCE.setRadarServer(false);
        assembler.clear();
        level = null;
        helloTicks = 10;
        refreshedRevision = -1;
        refreshedHandler = null;
    }
}
