package dev.civmaker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable, bounded outbox. Acknowledged alerts are removed only after Discord delivery. */
final class DiscordRaidAlerts {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static final long LIFETIME = 86_400_000;
    static final int MAX_PENDING = 1024;
    static final Set<String> KINDS = Set.of("settlement_capture", "lockpick", "battering_ram", "c4", "grenade", "block_break", "combat");
    record Alert(UUID id, UUID factionId, String factionName, String kind, String attacker,
                 String world, int x, int y, int z, String createdAt) {}
    private record Data(int schemaVersion, List<Alert> alerts, Map<UUID, Long> lastAlerts) {}
    private final Path file;
    private final Clock clock;
    private final long cooldown;
    private List<Alert> alerts = new ArrayList<>();
    private Map<UUID, Long> lastAlerts = new HashMap<>();

    DiscordRaidAlerts(Path directory, long cooldownMillis) throws IOException { this(directory, cooldownMillis, Clock.systemUTC()); }

    DiscordRaidAlerts(Path directory, long cooldownMillis, Clock clock) throws IOException {
        this.file = directory.resolve("discord-raids.json");
        this.clock = clock;
        this.cooldown = Math.max(30_000, Math.min(3_600_000, cooldownMillis));
        Files.createDirectories(directory);
        if (Files.exists(file)) {
            try {
                Data data = JSON.fromJson(Files.readString(file), Data.class);
                if (data == null || data.schemaVersion != 1 || data.alerts == null || data.lastAlerts == null
                        || data.alerts.size() > MAX_PENDING) throw new IllegalArgumentException("Invalid raid outbox");
                Set<UUID> ids = new HashSet<>();
                for (Alert alert : data.alerts) {
                    if (alert == null || alert.id == null || alert.factionId == null || alert.factionName == null
                            || !KINDS.contains(alert.kind) || alert.attacker == null || alert.world == null || !ids.add(alert.id)) {
                        throw new IllegalArgumentException("Invalid raid alert");
                    }
                    Instant.parse(alert.createdAt);
                }
                if (data.lastAlerts.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
                    throw new IllegalArgumentException("Invalid raid cooldown");
                }
                alerts = new ArrayList<>(data.alerts);
                lastAlerts = new HashMap<>(data.lastAlerts);
            } catch (RuntimeException error) { throw new IOException("Invalid discord-raids.json; restore it from a backup.", error); }
        }
    }

    synchronized boolean record(UUID factionId, String factionName, String kind, String attacker, String world,
                                int x, int y, int z) throws IOException {
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unknown raid alert kind");
        long now = clock.millis();
        Long last = lastAlerts.get(factionId);
        if (last != null && now - last < cooldown) return false;
        var updated = new ArrayList<>(pending());
        if (updated.size() >= MAX_PENDING) updated.removeFirst();
        updated.add(new Alert(UUID.randomUUID(), factionId, factionName, kind, attacker == null ? "Unknown" : attacker,
                world, x, y, z, Instant.ofEpochMilli(now).toString()));
        var cooldowns = new HashMap<>(lastAlerts);
        cooldowns.entrySet().removeIf(entry -> now - entry.getValue() > LIFETIME);
        cooldowns.put(factionId, now);
        save(updated, cooldowns);
        alerts = updated;
        lastAlerts = cooldowns;
        return true;
    }

    synchronized List<Alert> pending() {
        long cutoff = clock.millis() - LIFETIME;
        return alerts.stream().filter(alert -> Instant.parse(alert.createdAt).toEpochMilli() > cutoff).toList();
    }

    synchronized void acknowledge(Set<UUID> ids) throws IOException {
        var updated = alerts.stream().filter(alert -> !ids.contains(alert.id)
                && Instant.parse(alert.createdAt).toEpochMilli() > clock.millis() - LIFETIME).toList();
        if (updated.size() == alerts.size()) return;
        save(updated, lastAlerts);
        alerts = new ArrayList<>(updated);
    }

    private void save(List<Alert> updated, Map<UUID, Long> cooldowns) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(new Data(1, updated, cooldowns)));
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
