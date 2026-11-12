package dev.civmaker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/** Authenticated loopback API. Bukkit state is captured only on the Minecraft server thread. */
final class DiscordBridge implements AutoCloseable {
    private static final Gson JSON = new Gson();
    record Faction(UUID id, String name, int color) {}
    record LinkedPlayer(UUID minecraftId, String minecraftName, String discordId, UUID factionId) {}
    record Snapshot(int schemaVersion, UUID serverId, String generatedAt, List<Faction> factions, List<LinkedPlayer> players,
                    List<DiscordRaidAlerts.Alert> raidAlerts) {}
    private record Memberships(String generatedAt, List<Faction> factions, Map<UUID, UUID> members) {}
    private record LinkRequest(String code, String discordId) {}

    private final CivMaker plugin;
    private final CivStore store;
    private final DiscordLinks links;
    private final DiscordRaidAlerts raidAlerts;
    private final byte[] authorization;
    private final HttpServer server;
    private final ExecutorService executor;
    private BukkitTask refreshTask;
    private volatile Memberships memberships;
    private long nextRaidError;

    static DiscordBridge start(CivMaker plugin, CivStore store) {
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) return null;
        DiscordBridge bridge = null;
        try {
            if (!plugin.getServer().getOnlineMode()) {
                throw new IllegalArgumentException("Discord linking requires online-mode=true to verify Minecraft accounts.");
            }
            String token = plugin.getConfig().getString("discord.bridge-token", "").trim();
            if (token.length() < 32 || token.contains("CHANGE_ME")) {
                throw new IllegalArgumentException("Set discord.bridge-token to a random secret of at least 32 characters.");
            }
            int port = plugin.getConfig().getInt("discord.port", 8790);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid discord.port.");
            bridge = new DiscordBridge(plugin, store, token, port);
            bridge.refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, bridge::refresh, 20, 20);
            plugin.getLogger().info("Discord bridge listening on 127.0.0.1:" + bridge.port());
            return bridge;
        } catch (IOException | RuntimeException error) {
            if (bridge != null) bridge.close();
            plugin.getLogger().log(Level.SEVERE, "Discord bridge could not start. Minecraft gameplay remains available.", error);
            return null;
        }
    }

    DiscordBridge(CivMaker plugin, CivStore store, String token, int port) throws IOException {
        this.plugin = plugin;
        this.store = store;
        links = new DiscordLinks(plugin.getDataFolder().toPath());
        long cooldown = plugin.getConfig() == null ? 300 : plugin.getConfig().getLong("discord.raid-alert-cooldown-seconds", 300);
        raidAlerts = new DiscordRaidAlerts(plugin.getDataFolder().toPath(), Math.max(30, Math.min(3600, cooldown)) * 1000);
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        refresh();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "CivMaker-Discord");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/v1/", this::handle);
        server.start();
    }

    int port() { return server.getAddress().getPort(); }

    void raidAlert(CivStore.Village faction, String attacker, org.bukkit.Location location, String kind) {
        if (faction == null || store.activeWindow(System.currentTimeMillis()) == null
                || store.claimAt(CivStore.Plot.at(location)) != faction || !store.raidCovers(CivStore.Plot.at(location))) return;
        try {
            raidAlerts.record(faction.id, faction.name, kind, attacker, location.getWorld().getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        } catch (IOException error) {
            long now = System.currentTimeMillis();
            if (now >= nextRaidError) {
                plugin.getLogger().log(Level.WARNING, "Could not save a Discord raid alert. Check the plugin data directory.", error);
                nextRaidError = now + 60_000;
            }
        }
    }

    void command(Player player, String[] args) {
        String action = args.length == 1 ? "status" : args[1].toLowerCase(Locale.ROOT);
        if (args.length > 2) throw new IllegalArgumentException("Use /civ discord <link|unlink|status>.");
        try {
            switch (action) {
                case "link" -> {
                    String code = links.issue(player.getUniqueId(), player.getName());
                    player.sendMessage("In our Discord server, use /link code:" + code + ". This private code expires in 10 minutes. Do not share it.");
                }
                case "unlink" -> {
                    links.unlinkMinecraft(player.getUniqueId());
                    player.sendMessage("Discord link and pending code removed. Your faction role will be removed on the bot's next sync.");
                }
                case "status" -> {
                    DiscordLinks.Account account = links.account(player.getUniqueId());
                    player.sendMessage(account == null ? "Not linked. Use /civ discord link to connect your Discord account."
                            : "Linked to Discord account ID " + account.discordId() + ". Use /civ discord unlink to disconnect.");
                }
                default -> throw new IllegalArgumentException("Use /civ discord <link|unlink|status>.");
            }
        } catch (IOException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not save Discord account links.", error);
            player.sendMessage("Could not save your Discord link. Contact a server administrator and try again.");
        }
    }

    void refresh() {
        var factions = new ArrayList<Faction>();
        var members = new HashMap<UUID, UUID>();
        for (CivStore.Village village : store.villages()) {
            factions.add(new Faction(village.id, village.name, village.color));
            // Actual saved membership, including offline players, rather than admin test allegiance.
            for (UUID member : village.members) members.put(member, village.id);
        }
        memberships = new Memberships(Instant.now().toString(), List.copyOf(factions), Map.copyOf(members));
    }

    Snapshot snapshot() {
        Memberships current = memberships;
        var players = links.accounts().stream().map(account -> new LinkedPlayer(account.minecraftId(),
                account.minecraftName(), account.discordId(), current.members.get(account.minecraftId()))).toList();
        return new Snapshot(1, links.serverId(), current.generatedAt, current.factions, players, raidAlerts.pending());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (supplied == null || !MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
                respond(exchange, 401, Map.of("error", "Unauthorized"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/v1/snapshot") && method.equals("GET")) {
                respond(exchange, 200, snapshot());
            } else if ((path.equals("/v1/link") || path.equals("/v1/unlink") || path.equals("/v1/raid-alerts/ack")) && method.equals("POST")) {
                byte[] body = exchange.getRequestBody().readNBytes(4097);
                if (body.length > 4096) {
                    respond(exchange, 413, Map.of("error", "Request too large"));
                    return;
                }
                if (path.equals("/v1/raid-alerts/ack")) {
                    try {
                        var object = JSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                        var array = object.getAsJsonArray("ids");
                        if (array == null || array.size() > 100) throw new IllegalArgumentException();
                        var ids = new java.util.HashSet<UUID>();
                        for (var id : array) ids.add(UUID.fromString(id.getAsString()));
                        raidAlerts.acknowledge(ids);
                        respond(exchange, 200, Map.of("ok", true));
                    } catch (RuntimeException error) { respond(exchange, 400, Map.of("error", "Invalid raid acknowledgement")); }
                    return;
                }
                LinkRequest request;
                try {
                    JsonObject object = JSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                    request = JSON.fromJson(object, LinkRequest.class);
                    if (request == null || request.discordId == null || !request.discordId.matches("[0-9]{17,20}")) {
                        throw new IllegalArgumentException();
                    }
                } catch (RuntimeException error) {
                    respond(exchange, 400, Map.of("error", "Invalid request"));
                    return;
                }
                if (path.equals("/v1/link")) respond(exchange, 200, links.redeem(request.code, request.discordId));
                else {
                    links.unlinkDiscord(request.discordId);
                    respond(exchange, 200, Map.of("ok", true));
                }
            } else respond(exchange, 404, Map.of("error", "Unknown endpoint or method"));
        } catch (IllegalArgumentException error) {
            respond(exchange, 400, Map.of("error", error.getMessage()));
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Discord bridge request failed.", error);
            respond(exchange, 500, Map.of("error", "Minecraft bridge failed to save or read account data. Contact an administrator."));
        } finally { exchange.close(); }
    }

    private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override public void close() {
        if (refreshTask != null) refreshTask.cancel();
        server.stop(0);
        executor.shutdownNow();
    }
}
