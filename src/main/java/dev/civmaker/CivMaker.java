package dev.civmaker;

import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Pillager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

public class CivMaker extends JavaPlugin {
    private CivStore store;
    private NaturalVillages naturalVillages;
    private PlayerHud hud;
    private BeaconLabels beaconLabels;
    private VaultTracking vaultTracking;
    private RaidEquipment equipment;
    private RaidShop raidShop;
    private ClaimBuying claimBuying;
    private SettlementVaults settlementVaults;
    private SettlementBuying settlementBuying;
    private RaidTools raidTools;
    private Trading trading;
    private SettlementCapture capture;
    private DefenderGolems defenders;
    private CombatTimeout combatTimeout;
    private WarpPortals warpPortals;
    private EndPortals endPortals;
    private DiscordBridge discordBridge;
    private boolean exportPending;
    private long nextNightlyExport;

    /**
     * Checks ownership of a purchased settlement at this location. Call on the server thread.
     */
    public boolean ownsSettlementAt(UUID player, Location location) {
        if (!isEnabled() || store == null) {
            return false;
        }
        CivStore.Plot plot = CivStore.Plot.at(location);
        CivStore.Village village = store.claimAt(plot);
        return village != null && village.owner.equals(player) && store.settlementAt(plot) != null;
    }

    /**
     * Returns the purchased settlement's type key, or null outside a settlement.
     * Meadow settlements have their own key because their bell determines their vault deposit.
     * Call on the server thread.
     */
    public String settlementTypeAt(Location location) {
        if (!isEnabled() || store == null) {
            return null;
        }
        return settlementType(store.settlementAt(CivStore.Plot.at(location)), location);
    }

    /**
     * Returns the type of a purchased or natural settlement at this location.
     * Call on the server thread.
     */
    public String tradeSettlementTypeAt(Location location) {
        String purchased = settlementTypeAt(location);
        return purchased != null || naturalVillages == null ? purchased
                : settlementType(naturalVillages.settlementAt(location.getWorld(), CivStore.Plot.at(location)), location);
    }

    private static String settlementType(CivStore.Settlement settlement, Location location) {
        if (settlement == null) {
            return null;
        }
        String structure = settlement.structure();
        if ("minecraft:village_plains".equals(structure)) {
            var bell = settlement.bell();
            var block = location.getWorld().getBlockAt(bell.x(), bell.y(), bell.z());
            if (SettlementResources.Reward.at(structure, block) == SettlementResources.Reward.AMETHYST) {
                return "minecraft:village_meadow";
            }
        }
        return structure;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            store = new CivStore(getDataFolder().toPath());
        } catch (IOException | RuntimeException error) {
            getLogger().log(Level.SEVERE, "Could not load CivMaker state.", error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Notes notes = new Notes(this);
        Wallets wallets = new Wallets(this);
        NaturalVillages natural = new NaturalVillages(this, store);
        naturalVillages = natural;
        claimBuying = new ClaimBuying(this, store, wallets, natural);
        VillageBorders villageBorders = new VillageBorders(this, store);
        equipment = new RaidEquipment(this);
        SettlementResources resources = new SettlementResources(this, equipment);
        settlementVaults = new SettlementVaults(this, store, resources);
        settlementBuying = new SettlementBuying(this, store, wallets, settlementVaults, natural);
        VaultDoors vaultDoors = new VaultDoors(store, equipment);
        PillagerGuards guards = new PillagerGuards(this, store, equipment);
        defenders = new DefenderGolems(this, store, guards);
        Protection protection = new Protection(store, equipment, vaultDoors, natural, defenders);
        RaidShields shields = new RaidShields(this, store, equipment);
        FoundingBeacons foundingBeacons = new FoundingBeacons(this, store, wallets, natural);
        raidShop = new RaidShop(this, store, wallets, equipment, resources, shields, foundingBeacons);
        capture = new SettlementCapture(this, store, defenders);
        Radar radar = new Radar(this, store, equipment, guards);
        raidTools = new RaidTools(this, store, equipment, protection, vaultDoors, guards, defenders, settlementVaults);
        trading = new Trading(this);
        combatTimeout = new CombatTimeout(this);
        warpPortals = new WarpPortals(this, store, equipment, combatTimeout, capture);
        endPortals = new EndPortals(this, store, wallets);
        CompanionMaps companionMaps = new CompanionMaps(this, store, equipment);
        hud = new PlayerHud(this, store, wallets);
        beaconLabels = new BeaconLabels(this, store);
        vaultTracking = new VaultTracking(this, store, equipment, notes);
        FarmStationLoot farmStationLoot = new FarmStationLoot(this);
        FarmStations farmStations = new FarmStations(this, store, farmStationLoot);
        var events = getServer().getPluginManager();
        events.registerEvents(new Mining(this, wallets), this);
        events.registerEvents(farmStationLoot, this);
        events.registerEvents(protection, this);
        events.registerEvents(farmStations, this);
        events.registerEvents(new FarmHarvests(this, farmStations), this);
        events.registerEvents(foundingBeacons, this);
        events.registerEvents(vaultDoors, this);
        events.registerEvents(notes, this);
        events.registerEvents(hud, this);
        events.registerEvents(claimBuying, this);
        events.registerEvents(natural, this);
        events.registerEvents(settlementBuying, this);
        events.registerEvents(capture, this);
        events.registerEvents(settlementVaults, this);
        events.registerEvents(raidShop, this);
        events.registerEvents(shields, this);
        events.registerEvents(raidTools, this);
        events.registerEvents(guards, this);
        events.registerEvents(defenders, this);
        events.registerEvents(new BlastRockets(this, equipment), this);
        events.registerEvents(trading, this);
        events.registerEvents(new WalletDrops(wallets, notes, trading), this);
        events.registerEvents(new MoneyStorage(notes, store, equipment, resources), this);
        events.registerEvents(equipment, this);
        events.registerEvents(vaultTracking, this);
        events.registerEvents(combatTimeout, this);
        events.registerEvents(warpPortals, this);
        events.registerEvents(endPortals, this);
        events.registerEvents(companionMaps, this);
        for (var world : getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                farmStations.indexChunk(chunk);
                endPortals.queue(chunk);
            }
            world.getEntitiesByClass(HappyGhast.class).forEach(equipment::syncHarness);
            world.getEntitiesByClass(AbstractHorse.class).forEach(equipment::syncSaddle);
            world.getEntitiesByClass(Pillager.class).forEach(guards::track);
            world.getEntitiesByClass(IronGolem.class).forEach(defenders::track);
        }
        Objects.requireNonNull(getCommand("civ")).setExecutor(
                new CivCommands(this, store, notes, wallets, claimBuying, settlementBuying, natural, villageBorders, raidShop,
                        settlementVaults, shields, foundingBeacons, farmStationLoot));
        Objects.requireNonNull(getCommand("trade")).setExecutor(trading);
        discordBridge = DiscordBridge.start(this, store);

        getServer().getScheduler().runTaskTimer(this, capture::refresh,
                SettlementCapture.UPDATE_TICKS, SettlementCapture.UPDATE_TICKS);
        getServer().getScheduler().runTaskTimer(this, vaultTracking::refresh, 1, 20);
        getServer().getScheduler().runTaskTimer(this, this::flush, 20, 200);
        getServer().getScheduler().runTaskTimer(this, hud::refresh, 1, 5);
        getServer().getScheduler().runTaskTimer(this, claimBuying::showBorders, 1, 8);
        getServer().getScheduler().runTaskTimer(this, villageBorders::refresh, 1, 8);
        getServer().getScheduler().runTaskTimer(this, beaconLabels::refresh, 1, 20);
        getServer().getScheduler().runTaskTimer(this, natural::discoverCenters, 1, 1);
        getServer().getScheduler().runTaskTimer(this, settlementBuying::refreshClaimants, 1, 20);
        getServer().getScheduler().runTaskTimer(this, settlementVaults::refresh, 1, 20);
        getServer().getScheduler().runTaskTimer(this, raidTools::closeExpired, 1, 5);
        getServer().getScheduler().runTaskTimer(this, guards::refresh, 1, 40);
        getServer().getScheduler().runTaskTimer(this, defenders::refresh, 1, 5);
        getServer().getScheduler().runTaskTimer(this, defenders::tickBoulders, 1, 1);
        getServer().getScheduler().runTaskTimer(this, radar::refresh, 1, 20);
        getServer().getScheduler().runTaskTimer(this, equipment::refreshRiddenSpeeds, 1, 1);
        getServer().getScheduler().runTaskTimer(this, combatTimeout::refresh, 1, 20);
        getServer().getScheduler().runTaskTimer(this, warpPortals::refresh, 1, 20);
        getServer().getScheduler().runTaskTimer(this, endPortals::discoverRooms, 1, 1);
        getServer().getScheduler().runTaskTimer(this, companionMaps::tick, 1, 1);
    }

    @Override
    public void onDisable() {
        if (discordBridge != null) {
            discordBridge.close();
            discordBridge = null;
        }
        if (claimBuying != null) {
            claimBuying.closeAll();
        }
        if (endPortals != null) {
            endPortals.closeAll();
        }
        if (warpPortals != null) {
            warpPortals.closeAll();
        }
        if (combatTimeout != null) {
            combatTimeout.close();
        }
        if (capture != null) {
            capture.clear();
        }
        if (defenders != null) {
            defenders.clear();
        }
        if (equipment != null) {
            equipment.clearRiddenSpeeds();
        }
        if (trading != null) {
            trading.closeAll();
        }
        if (raidShop != null) {
            raidShop.closeAll();
        }
        if (settlementVaults != null) {
            settlementVaults.closeAll();
        }
        if (settlementBuying != null) {
            settlementBuying.closeAll();
        }
        if (raidTools != null) {
            raidTools.closeAll();
        }
        if (beaconLabels != null) {
            beaconLabels.clear();
        }
        if (vaultTracking != null) {
            vaultTracking.refreshBalances();
            vaultTracking.clear();
        }
        if (hud != null) {
            hud.clear();
        }
        if (store != null) {
            flush();
        }
    }

    void saveState() throws IOException {
        store.save();
        exportPending = true;
    }

    void discordCommand(org.bukkit.entity.Player player, String[] args) {
        if (discordBridge == null) {
            throw new IllegalArgumentException("Discord linking is unavailable. Ask an administrator to enable the Discord bridge.");
        }
        discordBridge.command(player, args);
    }

    private void flush() {
        if (store.dirty) {
            try {
                saveState();
            } catch (IOException error) {
                getLogger().log(Level.SEVERE, "Could not save CivMaker state. Will retry.", error);
            }
        }
        long now = System.currentTimeMillis();
        if (store.nightly() != null && now >= nextNightlyExport) {
            exportPending = true;
        }
        if (exportPending && getConfig().getBoolean("export.enabled", true)) {
            try {
                store.export();
                nextNightlyExport = now + 3_600_000;
                exportPending = false;
            } catch (IOException error) {
                getLogger().log(Level.WARNING, "Could not write the public village export. Will retry.", error);
            }
        }
    }
}
