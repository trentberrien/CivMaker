package dev.civmaker;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.entity.Player;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordBridgeTest {
    @TempDir Path directory;

    @Test void loopbackApiAuthenticatesAndRedeemsOnlyAnInGameCode() throws Exception {
        CivStore store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Alex");
        String token = "a".repeat(32);
        try (DiscordBridge bridge = new DiscordBridge(plugin, store, token, 0);
             HttpClient client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + bridge.port() + "/v1/";
            var unauthorized = HttpRequest.newBuilder(URI.create(base + "snapshot")).timeout(Duration.ofSeconds(5)).build();
            assertEquals(401, client.send(unauthorized, HttpResponse.BodyHandlers.ofString()).statusCode());
            var snapshot = request(client, base + "snapshot", token, null);
            assertEquals(200, snapshot.statusCode());
            assertTrue(snapshot.body().contains("serverId"));
            assertEquals("no-store", snapshot.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(400, request(client, base + "link", token, "{broken").statusCode());
            assertEquals(413, request(client, base + "link", token, "x".repeat(4097)).statusCode());
            bridge.command(player, new String[]{"discord", "link"});
            var message = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(message.capture());
            var matcher = java.util.regex.Pattern.compile("[0-9A-F]{4}(?:-[0-9A-F]{4}){3}").matcher(message.getValue());
            assertTrue(matcher.find());
            String body = "{\"code\":\"" + matcher.group() + "\",\"discordId\":\"123456789012345678\"}";
            assertEquals(200, request(client, base + "link", token, body).statusCode());
            assertEquals(400, request(client, base + "link", token, body).statusCode());
            assertEquals(playerId, bridge.snapshot().players().getFirst().minecraftId());
            assertEquals(200, request(client, base + "unlink", token, "{\"discordId\":\"123456789012345678\"}").statusCode());
            assertTrue(bridge.snapshot().players().isEmpty());
            assertTrue(new DiscordLinks(directory).accounts().isEmpty());
        }
    }

    private static HttpResponse<String> request(HttpClient client, String url, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5));
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void snapshotUsesRealOfflineMembershipAndContainsOnlyLinkedPlayers() throws Exception {
        CivStore store = new CivStore(directory);
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), unlinked = UUID.randomUUID();
        var faction = new CivStore.Village("Ashford", owner, new CivStore.Position(UUID.randomUUID(), 0, 80, 0));
        faction.color = 0x5599ff;
        store.addVillage(faction);
        store.addMember(faction, member);
        store.addMember(faction, unlinked);
        DiscordLinks links = new DiscordLinks(directory);
        links.redeem(links.issue(member, "Alex"), "123456789012345678");
        store.setAllegiance(member, null);
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        try (DiscordBridge bridge = new DiscordBridge(plugin, store, "a".repeat(32), 0)) {
            var snapshot = bridge.snapshot();
            assertEquals(1, snapshot.players().size());
            assertEquals(faction.id, snapshot.players().getFirst().factionId());
            assertEquals("Ashford", snapshot.factions().getFirst().name());
            String json = new Gson().toJson(snapshot);
            assertFalse(json.contains(unlinked.toString()));
            assertFalse(json.contains("vault"));
            store.removeMember(faction, member);
            bridge.refresh();
            assertNull(bridge.snapshot().players().getFirst().factionId());
            store.disband(faction);
            bridge.refresh();
            assertTrue(bridge.snapshot().factions().isEmpty());
        }
    }
}
