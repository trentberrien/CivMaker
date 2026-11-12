package dev.civmaker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiscordRaidAlertsTest {
    @TempDir Path directory;
    private final UUID faction = UUID.randomUUID();

    private boolean record(DiscordRaidAlerts alerts, UUID id) throws Exception {
        return alerts.record(id, "Ashford", "lockpick", "Raider", "world", -20, 70, 30);
    }

    @Test void alertsPersistAndAreRemovedOnlyByTheirOwnAcknowledgement() throws Exception {
        var alerts = new DiscordRaidAlerts(directory, 300_000);
        assertTrue(record(alerts, faction));
        var saved = alerts.pending().getFirst();
        var reloaded = new DiscordRaidAlerts(directory, 300_000);
        assertEquals(saved, reloaded.pending().getFirst());
        reloaded.acknowledge(Set.of(UUID.randomUUID()));
        assertEquals(1, reloaded.pending().size());
        reloaded.acknowledge(Set.of(saved.id()));
        assertTrue(new DiscordRaidAlerts(directory, 300_000).pending().isEmpty());
        assertFalse(record(reloaded, faction), "Acknowledging must not reset the anti-spam cooldown");
    }

    @Test void cooldownIsPerFactionAndSurvivesRestart() throws Exception {
        var clock = new MutableClock();
        var alerts = new DiscordRaidAlerts(directory, 300_000, clock);
        assertTrue(record(alerts, faction));
        assertFalse(record(alerts, faction));
        assertTrue(record(alerts, UUID.randomUUID()));
        alerts = new DiscordRaidAlerts(directory, 300_000, clock);
        assertFalse(record(alerts, faction));
        clock.now += 300_000;
        assertTrue(record(alerts, faction));
        assertEquals(3, alerts.pending().size());
    }

    @Test void oldAlertsExpireAndInvalidKindsAreRejected() throws Exception {
        var clock = new MutableClock();
        var alerts = new DiscordRaidAlerts(directory, 300_000, clock);
        record(alerts, faction);
        clock.now += DiscordRaidAlerts.LIFETIME;
        assertTrue(alerts.pending().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> alerts.record(faction, "Ashford", "invalid", "Raider", "world", 0, 70, 0));
        record(alerts, faction);
        assertEquals(1, alerts.pending().size());
    }

    @Test void saveFailureDoesNotCreateAnAlertOrConsumeItsCooldown() throws Exception {
        var alerts = new DiscordRaidAlerts(directory, 300_000);
        Path temporary = directory.resolve("discord-raids.json.tmp");
        Files.createDirectory(temporary);
        assertThrows(java.io.IOException.class, () -> record(alerts, faction));
        assertTrue(alerts.pending().isEmpty());
        Files.delete(temporary);
        assertTrue(record(alerts, faction));
    }

    @Test void invalidOutboxFailsClosed() throws Exception {
        Files.writeString(directory.resolve("discord-raids.json"), "null");
        assertThrows(java.io.IOException.class, () -> new DiscordRaidAlerts(directory, 300_000));
    }

    private static final class MutableClock extends Clock {
        long now = 1_000_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
