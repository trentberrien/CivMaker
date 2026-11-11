package dev.civmaker;

import com.google.gson.Gson;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.map.protocol.MapProtocol;
import dev.civmaker.map.protocol.MapSnapshot;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Optional, read-only map bridge. All access to CivStore stays on the server thread. */
final class CompanionMaps implements Listener, PluginMessageListener {
    private static final Gson JSON = new Gson();
    private static final int FRAMES_PER_TICK = 4;
    private final CivMaker plugin;
    private final CivStore store;
    private final RaidEquipment equipment;
    private final Map<UUID, Client> clients = new HashMap<>();
    private int ticks;
    private int generation;

    private static final class Client {
        UUID world;
        byte[] previous;
        List<byte[]> pending = List.of();
        int next;
        long lastHello;
        MapSnapshot.RadarAccess access;
    }

    CompanionMaps(CivMaker plugin, CivStore store, RaidEquipment equipment) {
        this.plugin = plugin;
        this.store = store;
        this.equipment = equipment;
        var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, MapProtocol.HELLO, this);
        messenger.registerOutgoingPluginChannel(plugin, MapProtocol.DATA);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!MapProtocol.HELLO.equals(channel) || !MapProtocol.isHello(message)) {
            return;
        }
        Client client = clients.computeIfAbsent(player.getUniqueId(), ignored -> new Client());
        long now = System.currentTimeMillis();
        if (now - client.lastHello < 2_000) {
            return;
        }
        client.lastHello = now;
        client.previous = null;
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        clients.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void changeWorld(PlayerChangedWorldEvent event) {
        Client client = clients.get(event.getPlayer().getUniqueId());
        if (client != null) {
            client.world = null;
            client.previous = null;
            client.pending = List.of();
            client.next = 0;
            client.access = null;
        }
    }

    void tick() {
        boolean refresh = ticks++ % 60 == 0;
        Map<UUID, MapSnapshot> snapshots = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Client client = clients.get(player.getUniqueId());
            if (client == null) {
                continue;
            }
            UUID world = player.getWorld().getUID();
            if (!world.equals(client.world)) {
                client.previous = null;
                client.pending = List.of();
                client.next = 0;
            }
            boolean radar = !player.isDead() && equipment.hasRadar(player);
            if (client.access == null || client.access.allowed() != radar || refresh) {
                MapSnapshot.RadarAccess access = access(player, radar);
                if (!access.equals(client.access)) {
                    client.access = access;
                    client.previous = null;
                    client.pending = List.of();
                    client.next = 0;
                }
            }
            if ((refresh || client.previous == null) && client.next >= client.pending.size()) {
                MapSnapshot publicMap = snapshots.computeIfAbsent(world, ignored -> snapshot(player.getWorld()));
                MapSnapshot viewerMap = new MapSnapshot(publicMap.world(), publicMap.dimension(), publicMap.raidStatus(),
                        radar ? publicMap.villages() : List.of(), radar ? publicMap.claims() : List.of(),
                        radar ? publicMap.markers() : List.of(), publicMap.farms(), client.access);
                byte[] data = JSON.toJson(viewerMap).getBytes(StandardCharsets.UTF_8);
                if (!Arrays.equals(data, client.previous)) {
                    try {
                        client.pending = MapProtocol.frames(data, ++generation);
                        client.next = 0;
                        client.previous = data;
                        client.world = world;
                    } catch (IOException error) {
                        plugin.getLogger().warning("Cannot sync companion map: " + error.getMessage());
                        client.previous = data;
                        client.world = world;
                    }
                }
            }
            for (int sent = 0; sent < FRAMES_PER_TICK && client.next < client.pending.size(); sent++) {
                player.sendPluginMessage(plugin, MapProtocol.DATA, client.pending.get(client.next++));
            }
            if (client.next == client.pending.size()) {
                client.pending = List.of();
                client.next = 0;
            }
        }
    }

    private MapSnapshot.RadarAccess access(Player viewer, boolean radar) {
        var faction = store.memberOf(viewer.getUniqueId());
        List<String> friendly = new ArrayList<>();
        friendly.add(viewer.getUniqueId().toString());
        if (faction != null) {
            for (Player target : plugin.getServer().getOnlinePlayers()) {
                if (!target.getUniqueId().equals(viewer.getUniqueId()) && store.memberOf(target.getUniqueId()) == faction) {
                    friendly.add(target.getUniqueId().toString());
                }
            }
        }
        friendly.sort(String::compareTo);
        return new MapSnapshot.RadarAccess(radar, friendly);
    }

    private MapSnapshot snapshot(World world) {
        List<MapSnapshot.Village> villages = new ArrayList<>();
        Map<Plot, MapSnapshot.Claim> claims = new LinkedHashMap<>();
        List<MapSnapshot.Marker> markers = new ArrayList<>();
        List<MapSnapshot.Farm> farms = new ArrayList<>();
        if (plugin.getConfig().getBoolean("companion-map.enabled", true)) {
            for (var village : store.villages()) {
                String id = village.id.toString();
                int before = claims.size();
                // Settlement claims are included explicitly; preserve the store's actual ownership.
                for (Plot plot : village.plots) {
                    addClaim(claims, world, village, plot);
                }
                for (var settlement : village.settlements) {
                    for (Plot plot : settlement.plots()) {
                        addClaim(claims, world, village, plot);
                    }
                    if (settlement.bell().world().equals(world.getUID())) {
                        var bell = settlement.bell();
                        markers.add(new MapSnapshot.Marker(id + ":settlement:" + bell.x() + ":" + bell.z(), id,
                                village.name + " - " + settlement.displayName(null), "S", bell.x(), bell.y(), bell.z()));
                    }
                }
                if (claims.size() == before) {
                    continue;
                }
                var owner = plugin.getServer().getOfflinePlayer(village.owner).getName();
                villages.add(new MapSnapshot.Village(id, village.name, village.owner.toString(),
                        owner == null ? village.owner.toString() : owner, village.color, village.points()));
                var beacon = village.beacon;
                if (beacon.world().equals(world.getUID())) {
                    markers.add(new MapSnapshot.Marker(id + ":capital", id, village.name + " - Capital", "C",
                            beacon.x(), beacon.y(), beacon.z()));
                }
            }
            for (var farm : store.farmStations()) {
                var position = farm.position();
                if (!position.world().equals(world.getUID())) {
                    continue;
                }
                var faction = store.registeredVillage(farm.owner());
                String ownerName = farm.ownerName();
                if (ownerName == null) {
                    ownerName = plugin.getServer().getOfflinePlayer(farm.owner()).getName();
                }
                farms.add(new MapSnapshot.Farm(farm.displayName(), farm.owner().toString(),
                        ownerName == null ? farm.owner().toString() : ownerName,
                        faction == null ? "Unaffiliated" : faction.name, faction == null ? 0xAAAAAA : faction.color,
                        position.x(), position.y(), position.z()));
            }
        }
        String raids = store.raidMode() == CivStore.RaidMode.SETTLEMENTS ? "Active (settlements)"
                : store.activeWindow(System.currentTimeMillis()) != null ? "Active" : "Inactive";
        return new MapSnapshot(world.getUID().toString(), world.getKey().toString(), raids,
                villages, new ArrayList<>(claims.values()), markers, farms);
    }

    private void addClaim(Map<Plot, MapSnapshot.Claim> claims, World world, CivStore.Village village, Plot plot) {
        if (plot.world().equals(world.getUID()) && store.claimAt(plot) == village) {
            claims.put(plot, new MapSnapshot.Claim(plot.x(), plot.z(), village.id.toString(),
                    store.settlementAt(plot) == null ? "Capital territory" : "Settlement"));
        }
    }
}
