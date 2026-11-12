package dev.civmaker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiscordLinksTest {
    @TempDir Path directory;
    private static final String DISCORD = "123456789012345678";
    private static final String OTHER_DISCORD = "223456789012345678";

    @Test void codeProvesOwnershipIsSingleUseAndLinksSurviveRestart() throws Exception {
        DiscordLinks links = new DiscordLinks(directory);
        UUID player = UUID.randomUUID();
        String code = links.issue(player, "Alex");
        assertEquals(code, links.issue(player, "Alex"));
        assertEquals(19, code.length());
        assertTrue(links.accounts().isEmpty());
        var account = links.redeem(code.toLowerCase(), DISCORD);
        assertEquals(player, account.minecraftId());
        assertThrows(IllegalArgumentException.class, () -> links.redeem(code, OTHER_DISCORD));
        assertThrows(IllegalArgumentException.class, () -> links.issue(player, "Alex"));
        DiscordLinks reloaded = new DiscordLinks(directory);
        assertEquals(links.serverId(), reloaded.serverId());
        assertEquals(account, reloaded.account(player));
        assertFalse(Files.readString(directory.resolve("discord-links.json")).contains(code));
    }

    @Test void neitherAccountCanBeLinkedTwiceAndUnlinkAllowsRelinking() throws Exception {
        DiscordLinks links = new DiscordLinks(directory);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        links.redeem(links.issue(first, "Alex"), DISCORD);
        String secondCode = links.issue(second, "Steve");
        assertThrows(IllegalArgumentException.class, () -> links.redeem(secondCode, DISCORD));
        links.unlinkDiscord(DISCORD);
        assertNull(new DiscordLinks(directory).account(first));
        assertEquals(second, links.redeem(secondCode, DISCORD).minecraftId());
        links.unlinkMinecraft(second);
        assertTrue(new DiscordLinks(directory).accounts().isEmpty());
    }

    @Test void expiredCodesAndCodesFromBeforeRestartAreRejected() throws Exception {
        var clock = new MutableClock();
        DiscordLinks links = new DiscordLinks(directory, clock);
        UUID player = UUID.randomUUID();
        String code = links.issue(player, "Alex");
        clock.now += 600_000;
        assertThrows(IllegalArgumentException.class, () -> links.redeem(code, DISCORD));
        String fresh = links.issue(player, "Alex");
        assertNotEquals(code, fresh);
        assertThrows(IllegalArgumentException.class, () -> new DiscordLinks(directory, clock).redeem(fresh, DISCORD));
        links.unlinkMinecraft(player);
        assertThrows(IllegalArgumentException.class, () -> links.redeem(fresh, DISCORD));
    }

    @Test void failedSaveDoesNotConsumeCodeOrChangeLiveLinks() throws Exception {
        DiscordLinks links = new DiscordLinks(directory);
        UUID player = UUID.randomUUID();
        String code = links.issue(player, "Alex");
        Path temporary = directory.resolve("discord-links.json.tmp");
        Files.createDirectory(temporary);
        assertThrows(java.io.IOException.class, () -> links.redeem(code, DISCORD));
        assertTrue(links.accounts().isEmpty());
        Files.delete(temporary);
        assertEquals(player, links.redeem(code, DISCORD).minecraftId());
    }

    @Test void bruteForceIsThrottledAndRecoversAfterOneMinute() throws Exception {
        var clock = new MutableClock();
        DiscordLinks links = new DiscordLinks(directory, clock);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(IllegalArgumentException.class, () -> links.redeem("wrong", DISCORD));
        }
        String code = links.issue(UUID.randomUUID(), "Alex");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> links.redeem(code, DISCORD)).getMessage().contains("Too many"));
        clock.now += 60_000;
        assertNotNull(links.redeem(code, DISCORD));
    }

    @Test void corruptOrDuplicateSavedLinksFailClosed() throws Exception {
        Files.writeString(directory.resolve("discord-links.json"), "null");
        assertThrows(java.io.IOException.class, () -> new DiscordLinks(directory));
        Files.delete(directory.resolve("discord-links.json"));
        DiscordLinks links = new DiscordLinks(directory);
        links.redeem(links.issue(UUID.randomUUID(), "Alex"), DISCORD);
        Path file = directory.resolve("discord-links.json");
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonArray("links").add(json.getAsJsonArray("links").get(0).deepCopy());
        Files.writeString(file, json.toString());
        assertThrows(java.io.IOException.class, () -> new DiscordLinks(directory));
    }

    private static final class MutableClock extends Clock {
        long now = 1_000_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
