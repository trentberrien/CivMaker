package dev.civmaker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Private account links; never included in the World Atlas export. All operations are synchronized. */
final class DiscordLinks {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long CODE_LIFETIME = 600_000;
    record Account(UUID minecraftId, String minecraftName, String discordId) {}
    private record Data(UUID serverId, List<Account> links) {}
    private record Pending(UUID minecraftId, String name, long expires) {}
    private record Attempts(long start, int count) {}

    private final Path file;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final UUID serverId;
    private Map<UUID, Account> links = new LinkedHashMap<>();
    private final Map<String, Pending> codes = new HashMap<>();
    private final Map<String, Attempts> attempts = new HashMap<>();

    DiscordLinks(Path directory) throws IOException { this(directory, Clock.systemUTC()); }

    DiscordLinks(Path directory, Clock clock) throws IOException {
        this.file = directory.resolve("discord-links.json");
        this.clock = clock;
        Files.createDirectories(directory);
        if (Files.exists(file)) {
            try {
                Data data = JSON.fromJson(Files.readString(file), Data.class);
                if (data == null || data.serverId == null || data.links == null) {
                    throw new IllegalArgumentException("Missing account link data");
                }
                serverId = data.serverId;
                var discordIds = new java.util.HashSet<String>();
                for (Account account : data.links) {
                    if (account == null || account.minecraftId == null || account.minecraftName == null
                            || !validDiscordId(account.discordId) || !discordIds.add(account.discordId)
                            || links.putIfAbsent(account.minecraftId, account) != null) {
                        throw new IllegalArgumentException("Invalid or duplicate account link");
                    }
                }
            } catch (RuntimeException error) {
                throw new IOException("Invalid discord-links.json; restore it from a backup.", error);
            }
        } else {
            serverId = UUID.randomUUID();
            save(links);
        }
    }

    UUID serverId() { return serverId; }
    synchronized List<Account> accounts() { return List.copyOf(links.values()); }
    synchronized Account account(UUID player) { return links.get(player); }

    synchronized String issue(UUID player, String name) {
        if (links.containsKey(player)) throw new IllegalArgumentException("Already linked. Use /civ discord unlink first.");
        long now = clock.millis();
        codes.entrySet().removeIf(entry -> entry.getValue().expires <= now);
        for (var entry : codes.entrySet()) {
            if (entry.getValue().minecraftId.equals(player)) return display(entry.getKey());
        }
        if (codes.size() >= 10_000) throw new IllegalArgumentException("Too many pending links. Try again later.");
        String code;
        do { code = java.util.HexFormat.of().formatHex(random.generateSeed(8)).toUpperCase(Locale.ROOT); }
        while (codes.containsKey(code));
        codes.put(code, new Pending(player, name, now + CODE_LIFETIME));
        return display(code);
    }

    synchronized Account redeem(String entered, String discordId) throws IOException {
        if (!validDiscordId(discordId)) throw new IllegalArgumentException("Invalid Discord account ID.");
        long now = clock.millis();
        attempts.entrySet().removeIf(entry -> entry.getValue().start + 60_000 <= now);
        Attempts previous = attempts.get(discordId);
        if (previous != null && previous.count >= 5 || previous == null && attempts.size() >= 10_000) {
            throw new IllegalArgumentException("Too many linking attempts. Wait one minute.");
        }
        attempts.put(discordId, new Attempts(previous == null ? now : previous.start,
                previous == null ? 1 : previous.count + 1));
        String code = entered == null ? "" : entered.replace("-", "").trim().toUpperCase(Locale.ROOT);
        Pending pending = codes.get(code);
        if (pending == null || pending.expires <= now) {
            codes.remove(code);
            throw new IllegalArgumentException("Invalid or expired code. In Minecraft, use /civ discord link.");
        }
        if (links.values().stream().anyMatch(account -> account.discordId.equals(discordId))) {
            throw new IllegalArgumentException("Your Discord account is already linked. Use /unlink first.");
        }
        if (links.containsKey(pending.minecraftId)) throw new IllegalArgumentException("Minecraft account is already linked.");
        Account account = new Account(pending.minecraftId, pending.name, discordId);
        var updated = new LinkedHashMap<>(links);
        updated.put(account.minecraftId, account);
        save(updated);
        links = updated;
        codes.remove(code);
        return account;
    }

    synchronized void unlinkMinecraft(UUID player) throws IOException {
        var updated = new LinkedHashMap<>(links);
        if (updated.remove(player) != null) {
            save(updated);
            links = updated;
        }
        codes.values().removeIf(pending -> pending.minecraftId.equals(player));
    }

    synchronized void unlinkDiscord(String discordId) throws IOException {
        for (Account account : new ArrayList<>(links.values())) {
            if (account.discordId.equals(discordId)) unlinkMinecraft(account.minecraftId);
        }
    }

    private void save(Map<UUID, Account> updated) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(new Data(serverId, List.copyOf(updated.values()))));
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static boolean validDiscordId(String id) { return id != null && id.matches("[0-9]{17,20}"); }
    private static String display(String code) {
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12) + "-" + code.substring(12);
    }
}
