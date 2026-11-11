package dev.civmaker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class CivStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static final int CAPITAL_POINTS = 100;
    static final int CAPITAL_EXPANSION_LIMIT = 100;
    static final int SETTLEMENT_POINTS = 100;

    record Plot(UUID world, int x, int z) {
        static Plot at(Block block) {
            return new Plot(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
        }

        static Plot at(Location location) {
            return new Plot(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }

        List<Plot> neighbors() {
            return List.of(new Plot(world, x - 1, z), new Plot(world, x + 1, z),
                    new Plot(world, x, z - 1), new Plot(world, x, z + 1));
        }
    }

    record Position(UUID world, int x, int y, int z) {
        static Position at(Block block) {
            return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        Plot plot() {
            return new Plot(world, x >> 4, z >> 4);
        }
    }

    record Window(long start, long end) {
        boolean contains(long time) {
            return start <= time && time < end;
        }
    }

    record ClaimPurchase(Plot plot, long price) {}
    record WarpPortal(Position spot, Position portal) {
        boolean touches(Plot plot) {
            return spot.plot().equals(plot) || portal.plot().equals(plot);
        }
    }

    record Settlement(String structure, Position bell, Set<Plot> plots, SettlementVault vault,
                      SettlementDefense defense) {
        Settlement {
            if (vault == null) {
                vault = new SettlementVault();
            }
            if (defense == null) {
                defense = new SettlementDefense();
            }
        }

        Settlement(String structure, Position bell, Set<Plot> plots) {
            this(structure, bell, plots, null, null);
        }

        String displayName(Block bellBlock) {
            if (structure == null) {
                return "Settlement";
            }
            SettlementResources.Reward reward = bellBlock == null ? vault.reward
                    : SettlementResources.Reward.at(structure, bellBlock);
            if (reward == SettlementResources.Reward.AMETHYST) {
                return "Meadow Settlement";
            }
            String type = structure.substring(structure.indexOf(':') + 1);
            if (type.startsWith("village_")) {
                type = type.substring("village_".length());
            }
            return type.isEmpty() ? "Settlement" : type.substring(0, 1).toUpperCase(Locale.ROOT)
                    + type.substring(1).replace('_', ' ') + " Settlement";
        }
    }

    static final class SettlementVault {
        Position position;
        SettlementResources.Reward reward;
    }

    static final class SettlementDefense {
        UUID golem;
        boolean alwaysHostile;
        long stanceRevision;
    }
    private record VaultFunds(Plot plot, long balance) {}

    record FarmStation(Position position, UUID owner, String ownerName, String name) {
        String displayName() {
            return name == null ? "Farm Station" : name;
        }
    }

    enum RaidMode { AUTO, ON, OFF, SETTLEMENTS }

    static final class Village {
        final UUID id = UUID.randomUUID();
        final String name;
        final UUID owner;
        final Position beacon;
        int color;
        FactionBanner banner;
        final Set<UUID> members = new LinkedHashSet<>();
        final Set<Plot> plots = new LinkedHashSet<>();
        List<Settlement> settlements = new ArrayList<>();
        List<WarpPortal> warpPortals = new ArrayList<>();
        final Set<Position> treasuries = new LinkedHashSet<>();
        Set<Position> vaultDoors;
        ClaimPurchase lastPurchase;
        long timesRaided;
        long shieldBoughtAt;
        long shieldUsedAt;
        Long shieldWindow;
        boolean endUnlocked;

        Village(String name, UUID owner, Position beacon) {
            this.name = name;
            this.owner = owner;
            this.beacon = beacon;
            members.add(owner);
            plots.add(beacon.plot());
        }

        int capitalExpansionCount() {
            int settlementPlots = settlements.stream().mapToInt(settlement -> settlement.plots().size()).sum();
            return plots.size() - 1 - settlementPlots;
        }

        long points() {
            return CAPITAL_POINTS + (long) capitalExpansionCount() + (long) SETTLEMENT_POINTS * settlements.size();
        }

    }

    private static final class Data {
        final Map<UUID, Village> villages = new LinkedHashMap<>();
        final List<Window> windows = new ArrayList<>();
        final Set<UUID> admins = new LinkedHashSet<>();
        final Set<Position> naturalBells = new LinkedHashSet<>();
        List<VaultFunds> vaultFunds = List.of();
        List<FarmStation> farmStations = List.of();
        RaidMode raidMode = RaidMode.AUTO;
        long manualRaidId;
        NightlyRaids nightly;
    }

    private final Path directory;
    private final Data data;
    private final Map<Plot, Village> claims = new HashMap<>();
    private final Map<Plot, Settlement> settlementClaims = new HashMap<>();
    private final Map<Plot, Set<Position>> naturalBells = new HashMap<>();
    private final Map<Plot, Long> vaultBalances = new HashMap<>();
    private final Map<Plot, FarmStation> farmStations = new HashMap<>();
    private final Map<UUID, Village> memberships = new HashMap<>();
    private final Map<UUID, Village> allegiances = new HashMap<>();
    boolean dirty = true;

    CivStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        Path file = directory.resolve("state.json");
        data = Files.exists(file) ? JSON.fromJson(Files.readString(file), Data.class) : new Data();
        if (data == null) {
            throw new IOException("CivMaker state.json is empty.");
        }
        for (Position bell : data.naturalBells) {
            naturalBells.computeIfAbsent(bell.plot(), ignored -> new LinkedHashSet<>()).add(bell);
        }
        Set<Integer> usedColors = usedColors();
        for (Village village : data.villages.values()) {
            if (village.color == 0) {
                village.color = VillageColors.next(usedColors);
                usedColors.add(village.color);
            }
            if (village.settlements == null) {
                village.settlements = new ArrayList<>();
            }
            if (village.warpPortals == null) {
                village.warpPortals = new ArrayList<>();
            }
            for (Settlement settlement : village.settlements) {
                // Refresh saved rewards when upgrading existing Taiga and Snowy vaults.
                if ("minecraft:village_taiga".equals(settlement.structure())
                        || "minecraft:village_snowy".equals(settlement.structure())) {
                    settlement.vault().reward = SettlementResources.Reward.at(settlement.structure(), null);
                }
                village.plots.addAll(settlement.plots());
                settlement.plots().forEach(plot -> settlementClaims.put(plot, settlement));
            }
            village.plots.forEach(plot -> claims.put(plot, village));
            village.members.forEach(member -> memberships.put(member, village));
        }
        for (VaultFunds entry : data.vaultFunds) {
            if (claims.containsKey(entry.plot())) {
                vaultBalances.put(entry.plot(), entry.balance());
            }
        }
        for (FarmStation station : data.farmStations) {
            farmStations.put(station.position().plot(), station);
        }
    }

    Village claimAt(Block block) {
        return claims.get(Plot.at(block));
    }

    Village claimAt(Location location) {
        return claimAt(Plot.at(location));
    }

    Village claimAt(Plot plot) {
        return claims.get(plot);
    }

    Village protectedClaimAt(Block block) {
        int y = block.getY();
        Plot plot = Plot.at(block);
        Village village = claimAt(plot);
        return village != null && ((y > 60 && y <= (isCapitalClaim(village, plot) ? 200 : 99)) || village.beacon.equals(Position.at(block))
                || isSettlementFixture(block)) ? village : null;
    }

    Settlement settlementVaultAt(Block block) {
        Position position = Position.at(block);
        Settlement settlement = settlementAt(position.plot());
        return settlement != null && position.equals(settlement.vault().position) ? settlement : null;
    }

    boolean isSettlementFixture(Block block) {
        Position position = Position.at(block);
        Settlement settlement = settlementAt(position.plot());
        return settlement != null && (position.equals(settlement.bell()) || position.equals(settlement.vault().position));
    }

    boolean isCapitalClaim(Village village, Plot plot) {
        return claims.get(plot) == village && !settlementClaims.containsKey(plot);
    }

    Settlement settlementAt(Plot plot) {
        return settlementClaims.get(plot);
    }

    Position warpSite(Village village, Plot plot) {
        if (village == null || claimAt(plot) != village) {
            return null;
        }
        Settlement settlement = settlementAt(plot);
        return settlement == null ? village.beacon : settlement.bell();
    }

    Set<Position> naturalBellsAt(Plot plot) {
        return naturalBells.getOrDefault(plot, Set.of());
    }

    void updateNaturalBells(Plot plot, Set<Position> bells) {
        Set<Position> previous = naturalBellsAt(plot);
        if (previous.equals(bells)) {
            return;
        }
        data.naturalBells.removeAll(previous);
        data.naturalBells.addAll(bells);
        if (bells.isEmpty()) {
            naturalBells.remove(plot);
        } else {
            naturalBells.put(plot, bells);
        }
        dirty = true;
    }

    boolean isClaimAnchor(Village village, Block block) {
        Position position = Position.at(block);
        Settlement settlement = settlementAt(position.plot());
        return village.beacon.equals(position) || (settlement != null && settlement.bell().equals(position));
    }

    boolean isAdmin(UUID player) {
        return data.admins.contains(player);
    }

    boolean setAdmin(UUID player, boolean admin) {
        boolean changed = admin ? data.admins.add(player) : data.admins.remove(player);
        if (changed && !admin) {
            allegiances.remove(player);
        }
        dirty |= changed;
        return changed;
    }

    Village memberOf(UUID player) {
        if (allegiances.containsKey(player)) {
            var online = Bukkit.getPlayer(player);
            if (online != null && (isAdmin(player) || online.hasPermission("civmaker.admin"))) {
                return allegiances.get(player);
            }
            allegiances.remove(player);
        }
        return registeredVillage(player);
    }

    Village registeredVillage(UUID player) {
        return memberships.get(player);
    }

    void setAllegiance(UUID player, Village village) {
        allegiances.put(player, village);
    }

    void clearAllegiance(UUID player) {
        allegiances.remove(player);
    }

    Iterable<Village> villages() {
        return data.villages.values();
    }

    boolean hasVillage(UUID id) {
        return data.villages.containsKey(id);
    }

    Village village(UUID id) {
        return data.villages.get(id);
    }

    List<Window> windows() {
        return data.windows;
    }

    void addVillage(Village village) {
        village.color = VillageColors.next(usedColors());
        data.villages.put(village.id, village);
        addMember(village, village.owner);
        addPlot(village, village.beacon.plot());
    }

    void disband(Village village) {
        if (!data.villages.remove(village.id, village)) {
            return;
        }
        for (Plot plot : village.plots) {
            if (claims.remove(plot, village)) {
                settlementClaims.remove(plot);
                vaultBalances.remove(plot);
            }
        }
        for (UUID member : village.members) {
            memberships.remove(member, village);
        }
        allegiances.values().removeIf(allegiance -> allegiance == village);
        dirty = true;
    }

    private Set<Integer> usedColors() {
        Set<Integer> colors = new HashSet<>();
        for (Village village : data.villages.values()) {
            colors.add(village.color);
        }
        return colors;
    }

    void addMember(Village village, UUID player) {
        village.members.add(player);
        memberships.put(player, village);
        dirty = true;
    }

    void removeMember(Village village, UUID player) {
        village.members.remove(player);
        memberships.remove(player, village);
        clearAllegiance(player);
        dirty = true;
    }

    void addPlot(Village village, Plot plot) {
        village.plots.add(plot);
        claims.put(plot, village);
        dirty = true;
    }

    void addSettlement(Village village, Settlement settlement) {
        village.settlements.add(settlement);
        for (Plot plot : settlement.plots()) {
            addPlot(village, plot);
            settlementClaims.put(plot, settlement);
        }
    }

    void transferSettlement(Settlement settlement, Village defender, Village attacker) {
        defender.settlements.remove(settlement);
        defender.plots.removeAll(settlement.plots());
        settlement.defense().golem = null;
        addSettlement(attacker, settlement);
        defender.warpPortals.removeIf(warp -> {
            if (!settlement.plots().contains(warp.spot().plot())) {
                return false;
            }
            attacker.warpPortals.add(warp);
            return true;
        });
        defender.treasuries.removeIf(position -> {
            if (!settlement.plots().contains(position.plot())) {
                return false;
            }
            attacker.treasuries.add(position);
            return true;
        });
        if (defender.vaultDoors != null) {
            if (attacker.vaultDoors == null) {
                attacker.vaultDoors = new LinkedHashSet<>();
            }
            defender.vaultDoors.removeIf(position -> {
                if (!settlement.plots().contains(position.plot())) {
                    return false;
                }
                attacker.vaultDoors.add(position);
                return true;
            });
        }
    }

    void removePlot(Village village, Plot plot) {
        village.plots.remove(plot);
        claims.remove(plot, village);
        village.warpPortals.removeIf(warp -> warp.touches(plot));
        vaultBalances.remove(plot);
        village.treasuries.removeIf(treasury -> treasury.plot().equals(plot));
        if (village.vaultDoors != null) {
            village.vaultDoors.removeIf(door -> door.plot().equals(plot));
        }
        dirty = true;
    }

    long villageBalance(Village village) {
        return village.plots.stream().mapToLong(plot -> vaultBalances.getOrDefault(plot, 0L))
                .reduce(0, (balance, contents) -> balance + Math.min(contents, Long.MAX_VALUE - balance));
    }

    void updateVaultBalance(Plot plot, long balance) {
        Long previous = balance == 0 ? vaultBalances.remove(plot) : vaultBalances.put(plot, balance);
        dirty |= (previous == null ? 0L : previous) != balance;
    }

    Collection<FarmStation> farmStations() {
        return farmStations.values();
    }

    FarmStation farmStationAt(Plot plot) {
        return farmStations.get(plot);
    }

    void indexFarmStation(FarmStation station) {
        dirty |= !station.equals(farmStations.put(station.position().plot(), station));
    }

    void removeFarmStation(Plot plot) {
        dirty |= farmStations.remove(plot) != null;
    }

    RaidMode raidMode() {
        return data.raidMode;
    }

    void setRaidMode(RaidMode mode) {
        if (data.raidMode == mode) {
            return;
        }
        if (mode == RaidMode.ON || mode == RaidMode.SETTLEMENTS) {
            // Scheduled raid IDs are positive start times. Manual activations use negative IDs.
            data.manualRaidId--;
        }
        data.raidMode = mode;
        dirty = true;
    }

    Window activeWindow(long now) {
        return switch (data.raidMode) {
            case OFF -> null;
            case ON, SETTLEMENTS -> new Window(data.manualRaidId, Long.MAX_VALUE);
            case AUTO -> scheduledWindow(now);
        };
    }

    NightlyRaids nightly() {
        return data.nightly;
    }

    void setNightly(NightlyRaids nightly) {
        data.nightly = nightly;
        dirty = true;
    }

    private Window scheduledWindow(long now) {
        for (Window window : data.windows) {
            if (window.contains(now)) {
                return window;
            }
        }
        if (data.nightly != null) {
            var date = data.nightly.dateAt(now);
            for (var day : List.of(date, date.minusDays(1))) {
                Window window = data.nightly.window(day);
                if (window.contains(now) && !overlapsOneOff(window)) {
                    return window;
                }
            }
        }
        return null;
    }

    private boolean overlapsOneOff(Window nightly) {
        return data.windows.stream().anyMatch(window -> window.start() < nightly.end() && window.end() > nightly.start());
    }

    List<Window> upcomingWindows(long now, int nightlyDays) {
        var windows = new ArrayList<>(data.windows.stream().filter(window -> window.end() > now).toList());
        if (data.nightly != null) {
            var date = data.nightly.dateAt(now);
            for (int offset = -1; offset < nightlyDays; offset++) {
                Window window = data.nightly.window(date.plusDays(offset));
                if (window.end() > now && window.end() > window.start() && !overlapsOneOff(window)) {
                    windows.add(window);
                }
            }
        }
        windows.sort(java.util.Comparator.comparingLong(Window::start));
        return windows;
    }

    boolean shielded(Village village, Window window) {
        return village != null && window != null && village.shieldWindow != null
                && village.shieldWindow == window.start();
    }

    boolean raidCovers(Plot plot) {
        return (data.raidMode != RaidMode.SETTLEMENTS || settlementAt(plot) != null)
                && !shielded(claimAt(plot), activeWindow(System.currentTimeMillis()));
    }

    void save() throws IOException {
        data.vaultFunds = vaultBalances.entrySet().stream()
                .map(entry -> new VaultFunds(entry.getKey(), entry.getValue())).toList();
        data.farmStations = List.copyOf(farmStations.values());
        writeJson(directory.resolve("state.json"), data);
        dirty = false;
    }

    void export() throws IOException {
        var villages = new ArrayList<Map<String, Object>>();
        for (Village village : data.villages.values()) {
            var view = new LinkedHashMap<String, Object>();
            view.put("id", village.id);
            view.put("name", village.name);
            view.put("color", String.format("#%06x", village.color));
            view.put("ownerId", village.owner);
            String ownerName = Bukkit.getOfflinePlayer(village.owner).getName();
            if (ownerName != null) {
                view.put("ownerName", ownerName);
            }
            view.put("beacon", village.beacon);
            view.put("memberCount", village.members.size());
            view.put("claimCount", village.plots.size());
            view.put("points", village.points());
            view.put("settlements", village.settlements.stream().map(settlement -> Map.of(
                    "structure", settlement.structure(), "bell", settlement.bell(), "plots", settlement.plots())).toList());
            view.put("balance", villageBalance(village));
            view.put("timesRaided", village.timesRaided);
            view.put("claims", village.plots);
            villages.add(view);
        }
        var worlds = new LinkedHashMap<String, String>();
        for (World world : Bukkit.getWorlds()) {
            worlds.put(world.getUID().toString(), world.getName());
        }
        Instant now = Instant.now();
        var windows = upcomingWindows(now.toEpochMilli(), 14).stream()
                .map(window -> Map.of("start", Instant.ofEpochMilli(window.start()).toString(),
                        "end", Instant.ofEpochMilli(window.end()).toString()))
                .toList();
        writeJson(directory.resolve("public-v1.json"), Map.of(
                "schemaVersion", 1, "generatedAt", now.toString(), "worlds", worlds,
                "villages", villages, "raidWindows", windows, "raidMode", data.raidMode.name()));
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(value), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
