package dev.civmaker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-thread-only ledger. Every monetary change and its recovery record commit together. */
final class BeaconEscrowLedger {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final BigInteger FOUR = BigInteger.valueOf(4);

    record Holding(UUID village, CivStore.Position beacon, BigInteger amount) {}
    record Snapshot(String inventory, String cursor, String data, String enderChest) {
        Snapshot(String inventory, String cursor, String data) { this(inventory, cursor, data, null); }
    }
    record Transfer(String id, long walletAfter, Snapshot snapshot) {}
    record Offline(UUID village, CivStore.Position beacon, String name, Transfer contents) {}
    enum Option { MONEY, ENDER_CHEST, INVENTORY }

    private static final class State {
        int version = 1;
        Map<UUID, Holding> holdings = new LinkedHashMap<>();
        Map<UUID, BigInteger> credits = new LinkedHashMap<>();
        Map<UUID, Transfer> pending = new LinkedHashMap<>();
        Map<UUID, Transfer> receipts = new LinkedHashMap<>();
        Map<String, Set<Long>> looted = new LinkedHashMap<>();
        Map<UUID, Offline> offline = new LinkedHashMap<>();
        Map<String, Set<Long>> itemRaids = new LinkedHashMap<>();

        State() {}
        State(State source) {
            holdings.putAll(source.holdings);
            credits.putAll(source.credits);
            pending.putAll(source.pending);
            receipts.putAll(source.receipts);
            source.looted.forEach((beacon, windows) -> looted.put(beacon, new LinkedHashSet<>(windows)));
            offline.putAll(source.offline);
            source.itemRaids.forEach((key, windows) -> itemRaids.put(key, new LinkedHashSet<>(windows)));
        }
    }

    private final Path file;
    private State state;

    BeaconEscrowLedger(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("beacon-escrow.json");
        try {
            state = Files.exists(file) ? JSON.fromJson(Files.readString(file), State.class) : new State();
            if (state == null || state.version != 1 || state.holdings == null || state.credits == null
                    || state.pending == null || state.receipts == null || state.looted == null
                    || state.offline == null || state.itemRaids == null)
                throw new IllegalArgumentException("Invalid ledger");
            state.holdings.forEach((id, holding) -> {
                if (holding.village == null || holding.beacon == null || holding.amount.signum() < 0)
                    throw new IllegalArgumentException("Invalid holding for " + id);
            });
            state.credits.forEach((id, amount) -> {
                if (amount.signum() < 0) throw new IllegalArgumentException("Invalid credit for " + id);
            });
            state.receipts.forEach((id, transfer) -> {
                if (transfer.id == null || transfer.walletAfter < 0 || transfer.snapshot == null
                        || transfer.snapshot.inventory == null || transfer.snapshot.cursor == null || transfer.snapshot.data == null)
                    throw new IllegalArgumentException("Invalid transfer for " + id);
            });
            state.pending.forEach((id, transfer) -> {
                if (!transfer.equals(state.receipts.get(id))) throw new IllegalArgumentException("Missing receipt for " + id);
            });
            state.looted.forEach((beacon, windows) -> {
                if (beacon == null || windows == null || windows.contains(null))
                    throw new IllegalArgumentException("Invalid beacon raid history");
            });
            state.offline.forEach((id, offline) -> {
                if (offline.village == null || offline.beacon == null || offline.contents == null
                        || offline.contents.id == null || offline.contents.walletAfter < 0
                        || offline.contents.snapshot == null || offline.contents.snapshot.enderChest == null
                        || offline.contents.snapshot.inventory == null || offline.contents.snapshot.cursor == null
                        || offline.contents.snapshot.data == null)
                    throw new IllegalArgumentException("Invalid offline inventory for " + id);
            });
            state.itemRaids.forEach((key, windows) -> {
                if (key == null || windows == null || windows.contains(null))
                    throw new IllegalArgumentException("Invalid item raid history");
            });
        } catch (RuntimeException error) {
            throw new IOException("Invalid beacon-escrow.json; refusing to discard balances", error);
        }
    }

    Transfer pending(UUID player) { return state.pending.get(player); }
    Transfer lastTransfer(UUID player) { return state.receipts.get(player); }
    Holding holding(UUID player) { return state.holdings.get(player); }
    Offline offline(UUID player) { return state.offline.get(player); }
    Map<UUID, Offline> offlinePlayers() { return Map.copyOf(state.offline); }

    void departing(UUID player, Offline offline) throws IOException {
        requireIdle(player);
        if (state.offline.containsKey(player)) return;
        State next = new State(state);
        next.offline.put(player, offline);
        commit(next);
    }

    void returning(UUID player) throws IOException {
        requireIdle(player);
        if (!state.offline.containsKey(player)) return;
        State next = new State(state);
        next.offline.remove(player);
        commit(next);
    }

    boolean looted(CivStore.Position beacon, long window, Option option) {
        return option == Option.MONEY ? looted(beacon, window)
                : state.itemRaids.getOrDefault(key(beacon) + ":" + option, Set.of()).contains(window);
    }

    void claimItems(CivStore.Village village, long window, Option option) throws IOException {
        if (option == Option.MONEY || looted(village.beacon, window, option))
            throw new IllegalStateException("Option already picked or invalid");
        State next = new State(state);
        next.itemRaids.computeIfAbsent(key(village.beacon) + ":" + option, ignored -> new LinkedHashSet<>()).add(window);
        commit(next);
    }

    /** Victim removal and attacker delivery are a single durable commit, with absolute recovery snapshots. */
    void takeItems(UUID victim, Offline expected, Transfer victimAfter, UUID raider, Transfer raiderAfter) throws IOException {
        requireIdle(raider);
        if (victim.equals(raider) || !expected.equals(state.offline.get(victim)))
            throw new IllegalStateException("Offline inventory changed");
        State next = new State(state);
        next.offline.put(victim, new Offline(expected.village, expected.beacon, expected.name, victimAfter));
        // A later stack replaces the victim's pending snapshot with its cumulative remainder.
        next.pending.put(victim, victimAfter);
        next.receipts.put(victim, victimAfter);
        next.pending.put(raider, raiderAfter);
        next.receipts.put(raider, raiderAfter);
        commit(next);
    }

    BigInteger refundable(UUID player) {
        Holding holding = holding(player);
        return state.credits.getOrDefault(player, BigInteger.ZERO)
                .add(holding == null ? BigInteger.ZERO : holding.amount);
    }

    BigInteger total(CivStore.Village village) {
        return state.holdings.values().stream().filter(h -> belongs(h, village))
                .map(Holding::amount).reduce(BigInteger.ZERO, BigInteger::add);
    }

    boolean looted(CivStore.Position beacon, long window) {
        return state.looted.getOrDefault(key(beacon), Set.of()).contains(window);
    }

    BigInteger lootAmount(CivStore.Village village) { return total(village).divide(FOUR); }

    void deposit(UUID player, Holding holding, Transfer transfer) throws IOException {
        requireIdle(player);
        if (state.holdings.containsKey(player) || holding.amount.signum() <= 0)
            throw new IllegalStateException("Duplicate or empty deposit");
        State next = new State(state);
        next.holdings.put(player, holding);
        next.pending.put(player, transfer);
        next.receipts.put(player, transfer);
        commit(next);
    }

    /** Any amount beyond wallet capacity becomes a private, non-raidable refund claim. */
    void refund(UUID player, long amount, Transfer transfer) throws IOException {
        requireIdle(player);
        BigInteger remaining = refundable(player).subtract(BigInteger.valueOf(amount));
        if (amount < 0 || remaining.signum() < 0) throw new IllegalArgumentException("Invalid refund");
        State next = new State(state);
        next.holdings.remove(player);
        if (remaining.signum() == 0) next.credits.remove(player);
        else next.credits.put(player, remaining);
        next.pending.put(player, transfer);
        next.receipts.put(player, transfer);
        commit(next);
    }

    void loot(UUID player, CivStore.Village village, long window, Transfer transfer) throws IOException {
        requireIdle(player);
        if (looted(village.beacon, window)) throw new IllegalStateException("Beacon already looted");
        BigInteger total = total(village);
        BigInteger loot = total.divide(FOUR);
        if (loot.signum() == 0) throw new IllegalStateException("No loot");
        State next = new State(state);
        record Share(UUID player, Holding holding, BigInteger loss, BigInteger remainder) {}
        var shares = new ArrayList<Share>();
        BigInteger assigned = BigInteger.ZERO;
        for (var entry : state.holdings.entrySet()) {
            Holding holding = entry.getValue();
            if (!belongs(holding, village)) continue;
            BigInteger[] share = holding.amount.multiply(loot).divideAndRemainder(total);
            assigned = assigned.add(share[0]);
            shares.add(new Share(entry.getKey(), holding, share[0], share[1]));
        }
        // Largest remainders conserve every dollar while keeping each loss proportional.
        shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparing(s -> s.player.toString()));
        int extras = loot.subtract(assigned).intValueExact();
        for (int index = 0; index < shares.size(); index++) {
            Share share = shares.get(index);
            BigInteger loss = share.loss.add(index < extras ? BigInteger.ONE : BigInteger.ZERO);
            next.holdings.put(share.player,
                    new Holding(share.holding.village, share.holding.beacon, share.holding.amount.subtract(loss)));
        }
        next.looted.computeIfAbsent(key(village.beacon), ignored -> new LinkedHashSet<>()).add(window);
        next.pending.put(player, transfer);
        next.receipts.put(player, transfer);
        commit(next);
    }

    void finish(UUID player, String transferId) throws IOException {
        Transfer transfer = pending(player);
        if (transfer == null || !transfer.id.equals(transferId)) throw new IllegalStateException("Wrong transfer");
        State next = new State(state);
        next.pending.remove(player);
        commit(next);
    }

    private void requireIdle(UUID player) {
        if (pending(player) != null) throw new IllegalStateException("Unfinished player transfer");
    }

    private static boolean belongs(Holding holding, CivStore.Village village) {
        return holding.village.equals(village.id) && holding.beacon.equals(village.beacon);
    }

    private static String key(CivStore.Position position) {
        return position.world() + ":" + position.x() + ":" + position.y() + ":" + position.z();
    }

    private void commit(State next) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // Fail closed on filesystems without atomic replacement; never publish a partial ledger.
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        state = next;
    }
}
