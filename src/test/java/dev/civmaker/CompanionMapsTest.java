package dev.civmaker;

import com.google.gson.Gson;
import dev.civmaker.map.protocol.MapProtocol;
import dev.civmaker.map.protocol.MapSnapshot;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompanionMapsTest {
    @TempDir Path directory;
    private final UUID worldId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private CivStore store;
    private CompanionMaps maps;
    private CivMaker plugin;
    private Player player;
    private World world;
    private World other;
    private YamlConfiguration config;
    private final List<byte[]> packets = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CompanionMapsTest"));
        when(server.getMessenger()).thenReturn(mock(Messenger.class));
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        world = world(worldId, "overworld");
        other = world(otherId, "the_nether");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        OfflinePlayer owner = mock(OfflinePlayer.class);
        when(owner.getName()).thenReturn("Alice");
        when(server.getOfflinePlayer(any(UUID.class))).thenReturn(owner);
        doAnswer(invocation -> { packets.add(invocation.getArgument(2)); return null; })
                .when(player).sendPluginMessage(eq(plugin), eq(MapProtocol.DATA), any(byte[].class));
        maps = new CompanionMaps(plugin, store);
    }

    private World world(UUID id, String name) {
        World result = mock(World.class);
        when(result.getUID()).thenReturn(id);
        when(result.getKey()).thenReturn(NamespacedKey.minecraft(name));
        return result;
    }

    private CivStore.Village village(UUID world, int x) {
        var village = new CivStore.Village("Test " + x, UUID.randomUUID(), new CivStore.Position(world, x, 80, 0));
        store.addVillage(village);
        return village;
    }

    private void refresh() { for (int i = 0; i < 61; i++) { maps.tick(); } }

    private MapSnapshot latest() throws Exception {
        var assembler = new MapProtocol.Assembler();
        MapSnapshot latest = null;
        for (byte[] packet : packets) {
            byte[] data = assembler.accept(packet, 0);
            if (data != null) { latest = new Gson().fromJson(new String(data, StandardCharsets.UTF_8), MapSnapshot.class); }
        }
        return latest;
    }

    @Test void onlyOptedInClientsReceivePublicCurrentWorldData() throws Exception {
        var village = village(worldId, -16);
        village(otherId, 0);
        var bell = new CivStore.Position(worldId, 48, 70, 0);
        store.addSettlement(village, new CivStore.Settlement("minecraft:village_desert", bell, Set.of(bell.plot())));
        refresh();
        assertTrue(packets.isEmpty(), "Unmodified clients must not receive custom payloads");
        maps.onPluginMessageReceived(MapProtocol.HELLO, player, new byte[]{1});
        refresh();
        assertTrue(packets.isEmpty());
        maps.onPluginMessageReceived(MapProtocol.HELLO, player, MapProtocol.hello());
        refresh();
        var snapshot = latest();
        assertEquals("minecraft:overworld", snapshot.dimension());
        assertEquals(1, snapshot.villages().size());
        assertEquals("Alice", snapshot.villages().getFirst().ownerName());
        assertEquals(2, snapshot.claims().size());
        assertEquals(2, snapshot.markers().size());
        assertEquals(-1, snapshot.claims().getFirst().x());
        int count = packets.size();
        refresh();
        assertEquals(count, packets.size(), "Unchanged map snapshots should not be resent");
    }

    @Test void ownershipDisbandWorldChangesAndDisableReplaceOldData() throws Exception {
        var village = village(worldId, 0);
        village(otherId, 16);
        maps.onPluginMessageReceived(MapProtocol.HELLO, player, MapProtocol.hello());
        refresh();
        assertEquals(1, latest().claims().size());
        store.disband(village);
        refresh();
        assertTrue(latest().claims().isEmpty());
        when(player.getWorld()).thenReturn(other);
        maps.changeWorld(new PlayerChangedWorldEvent(player, world));
        refresh();
        assertEquals("minecraft:the_nether", latest().dimension());
        assertEquals(1, latest().claims().size());
        config.set("companion-map.enabled", false);
        refresh();
        assertTrue(latest().claims().isEmpty());
        assertTrue(latest().markers().isEmpty());
    }

    @Test void farmsSyncOutsideClaimsWithNamesFactionChangesAndRemoval() throws Exception {
        var faction = village(worldId, 0);
        var position = new CivStore.Position(worldId, -17, 70, 48);
        store.indexFarmStation(new CivStore.FarmStation(position, faction.owner, "Alice", "Wheat Farm"));
        store.indexFarmStation(new CivStore.FarmStation(new CivStore.Position(otherId, 16, 70, 0),
                UUID.randomUUID(), "Other", "Other world farm"));
        maps.onPluginMessageReceived(MapProtocol.HELLO, player, MapProtocol.hello());
        refresh();
        var farm = latest().farms().getFirst();
        assertEquals(1, latest().farms().size());
        assertEquals(-17, farm.x());
        assertEquals("Wheat Farm", farm.name());
        assertEquals("Alice", farm.ownerName());
        assertEquals(faction.name, farm.faction());
        assertEquals(faction.color, farm.color());
        assertNull(store.claimAt(position.plot()), "Farms do not need village territory");

        store.indexFarmStation(new CivStore.FarmStation(position, faction.owner, "Alice", "Carrot Farm"));
        refresh();
        assertEquals("Carrot Farm", latest().farms().getFirst().name());
        store.disband(faction);
        refresh();
        assertEquals("Unaffiliated", latest().farms().getFirst().faction());
        assertEquals(0xAAAAAA, latest().farms().getFirst().color());
        assertTrue(latest().claims().isEmpty());

        config.set("companion-map.enabled", false);
        refresh();
        assertTrue(latest().farms().isEmpty());
        config.set("companion-map.enabled", true);
        refresh();
        assertEquals(1, latest().farms().size());
        store.removeFarmStation(position.plot());
        refresh();
        assertTrue(latest().farms().isEmpty());
    }
}
