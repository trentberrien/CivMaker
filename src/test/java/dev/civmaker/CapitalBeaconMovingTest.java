package dev.civmaker;

import com.destroystokyo.paper.event.block.BeaconEffectEvent;
import dev.civmaker.CapitalBeaconMoving.Site;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import io.papermc.paper.dialog.Dialog;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapitalBeaconMovingTest {
    @TempDir Path directory;
    private CivStore store;
    private CivMaker plugin;
    private Wallets wallets;
    private NaturalVillages natural;
    private CapitalBeaconMoving moving;
    private FoundingBeacons founding;
    private World world;
    private Village village;
    private Player owner;
    private Block original;
    private Block replacement;
    private Block air;
    private String purchase;
    private final List<Runnable> tasks = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        plugin = mock(CivMaker.class);
        var config = new YamlConfiguration();
        config.set("village.min-beacon-y", 64);
        when(plugin.getConfig()).thenReturn(config);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(call -> { tasks.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        wallets = mock(Wallets.class);
        natural = mock(NaturalVillages.class);
        moving = new CapitalBeaconMoving(plugin, store, wallets, natural);
        founding = new FoundingBeacons(plugin, store, wallets, natural);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getMaxHeight()).thenReturn(100);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(server.getWorld(world.getUID())).thenReturn(world);
        air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(air.isEmpty()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        original = block(0);
        replacement = block(32);
        owner = mock(Player.class);
        when(owner.getUniqueId()).thenReturn(UUID.randomUUID());
        when(owner.isOnline()).thenReturn(true);
        when(owner.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(owner.getWorld()).thenReturn(world);
        when(owner.getEyeLocation()).thenReturn(new Location(world, 0.5, 81.5, 1));
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(owner.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        when(inventory.getMaxStackSize()).thenReturn(64);
        when(wallets.balance(owner)).thenReturn(1_000L);
        village = new Village("Test", owner.getUniqueId(), Position.at(original));
        store.addVillage(village);
        store.addPlot(village, Position.at(replacement).plot());
        purchase = village.id + "/" + UUID.randomUUID();
        village.beaconMoveToken = purchase;
        mark(replacement, purchase, "placement");
        store.dirty = false;
    }

    private Block block(int x) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(80);
        when(block.getType()).thenReturn(Material.BEACON);
        Beacon state = mock(Beacon.class);
        when(block.getState(false)).thenReturn(state);
        when(state.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(state.update(false, false)).thenReturn(true);
        when(world.getBlockAt(x, 80, 0)).thenReturn(block);
        return block;
    }

    private void mark(Block block, String token, String placement) {
        var data = ((Beacon) block.getState(false)).getPersistentDataContainer();
        when(data.get(CapitalBeaconMoving.MOVE_KEY, PersistentDataType.STRING)).thenReturn(token);
        when(data.get(FoundingBeacons.BEACON_KEY, PersistentDataType.STRING)).thenReturn(placement);
    }

    private Site site() {
        when(owner.getEyeLocation()).thenReturn(new Location(world, 32.5, 81.5, 1));
        return new Site(village, replacement, Position.at(original), purchase, "placement");
    }

    private MockedConstruction<ItemStack> items() {
        return mockConstruction(ItemStack.class, (item, context) -> {
            when(item.getAmount()).thenReturn(1);
            when(item.getType()).thenReturn(Material.BEACON);
            when(item.getMaxStackSize()).thenReturn(64);
            when(item.asQuantity(anyInt())).thenReturn(item);
            ItemMeta meta = mock(ItemMeta.class);
            var data = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(data);
            when(item.getPersistentDataContainer()).thenReturn(data);
            doAnswer(call -> { call.<Consumer<ItemMeta>>getArgument(0).accept(meta); return true; })
                    .when(item).editMeta(any());
        });
    }

    @Test void purchaseCostsExactlyOneThousandGivesTaggedItemAndLeavesCapitalUntilConfirmation() throws Exception {
        try (var items = items()) {
            moving.buy(owner, village);
            verify(wallets).setBalance(owner, 0);
            verify(owner.getInventory()).setStorageContents(argThat(contents -> contents[0] == items.constructed().getFirst()));
            String token = village.beaconMoveToken;
            assertNotEquals(purchase, token);
            var data = (PersistentDataContainer) items.constructed().getFirst().getPersistentDataContainer();
            verify(data).set(CapitalBeaconMoving.MOVE_KEY, PersistentDataType.STRING, token);
            verify(data).set(FoundingBeacons.BEACON_KEY, PersistentDataType.BYTE, (byte) 1);
            verify(original, never()).setType(any(), anyBoolean());
            assertEquals(Position.at(original), village.beacon);
            store.save();
            assertEquals(token, new CivStore(directory).village(village.id).beaconMoveToken);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"poor", "full", "elder", "outsider", "creative", "away", "disband"})
    void failedPurchasesNeverChargeOrIssueItems(String failure) {
        switch (failure) {
            case "poor" -> when(wallets.balance(owner)).thenReturn(999L);
            case "full" -> {
                ItemStack full = mock(ItemStack.class);
                when(full.getAmount()).thenReturn(64);
                when(owner.getInventory().getStorageContents()).thenReturn(new ItemStack[]{full});
            }
            case "elder", "outsider" -> {
                UUID other = UUID.randomUUID();
                if (failure.equals("elder")) {
                    store.addMember(village, other);
                    store.setElder(village, other, true);
                }
                when(owner.getUniqueId()).thenReturn(other);
            }
            case "creative" -> when(owner.getGameMode()).thenReturn(GameMode.CREATIVE);
            case "away" -> when(owner.getEyeLocation()).thenReturn(new Location(world, 32, 80, 0));
            case "disband" -> store.disband(village);
        }
        try (var items = items()) {
            moving.buy(owner, village);
        }
        assertEquals(purchase, village.beaconMoveToken);
        assertEquals(Position.at(original), village.beacon);
        verify(wallets, never()).setBalance(any(), anyLong());
        verify(owner.getInventory(), never()).setStorageContents(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void confirmingMovesTheSameCapitalAndPreservesUpgradesAndClaimsAcrossReload(boolean upgraded) throws Exception {
        village.expandedClaims = upgraded;
        long points = village.points();
        var plots = List.copyOf(village.plots);
        moving.complete(owner, site());
        assertEquals(Position.at(replacement), village.beacon);
        assertNull(village.beaconMoveToken);
        assertEquals(upgraded, village.expandedClaims);
        assertEquals(points, village.points());
        assertEquals(plots, List.copyOf(village.plots));
        assertTrue(store.isClaimAnchor(village, replacement));
        assertFalse(store.isClaimAnchor(village, original));
        assertEquals(Position.at(replacement), store.warpSite(village, Position.at(original).plot()));
        verify(original).setType(Material.AIR, false);
        verify(replacement, never()).setType(Material.AIR, false);
        BeaconEffectEvent effects = mock(BeaconEffectEvent.class);
        when(effects.getBlock()).thenReturn(replacement);
        founding.effect(effects);
        verify(effects, times(upgraded ? 0 : 1)).setCancelled(true);
        store.save();
        Village restored = new CivStore(directory).village(village.id);
        assertEquals(Position.at(replacement), restored.beacon);
        assertEquals(upgraded, restored.expandedClaims);
        assertNull(restored.beaconMoveToken);
    }

    @Test void unconfirmedReplacementHasNoVanillaEffectsEvenWhenOriginalIsUpgraded() {
        village.expandedClaims = true;
        BeaconEffectEvent effects = mock(BeaconEffectEvent.class);
        when(effects.getBlock()).thenReturn(replacement);
        founding.effect(effects);
        verify(effects).setCancelled(true);
        assertEquals(Position.at(original), village.beacon);
    }

    @ParameterizedTest @ValueSource(strings = {"low", "nether", "unclaimed", "enemy", "reserved", "bell", "roof", "elder",
            "creative", "offline", "disband", "leave", "away", "world", "stalePurchase", "replaced", "missing", "movedCapital", "failedUpdate", "missingOriginal", "missingWorld"})
    void confirmationRevalidatesOwnerPlacementAndBothBeacons(String failure) {
        Site site = site();
        switch (failure) {
            case "low" -> when(replacement.getY()).thenReturn(63);
            case "nether" -> when(world.getEnvironment()).thenReturn(World.Environment.NETHER);
            case "unclaimed" -> store.removePlot(village, Position.at(replacement).plot());
            case "enemy" -> {
                store.removePlot(village, Position.at(replacement).plot());
                store.addVillage(new Village("Other", UUID.randomUUID(), Position.at(replacement)));
            }
            case "reserved" -> when(natural.reserved(world, Position.at(replacement).plot())).thenReturn(true);
            case "bell" -> when(natural.inBellProtection(replacement)).thenReturn(true);
            case "roof" -> {
                Block roof = mock(Block.class);
                when(roof.getType()).thenReturn(Material.STONE);
                when(world.getBlockAt(32, 90, 0)).thenReturn(roof);
            }
            case "elder" -> {
                UUID elder = UUID.randomUUID();
                store.addMember(village, elder);
                store.setElder(village, elder, true);
                when(owner.getUniqueId()).thenReturn(elder);
            }
            case "creative" -> when(owner.getGameMode()).thenReturn(GameMode.CREATIVE);
            case "offline" -> when(owner.isOnline()).thenReturn(false);
            case "disband" -> store.disband(village);
            case "leave" -> store.removeMember(village, owner.getUniqueId());
            case "away" -> when(owner.getEyeLocation()).thenReturn(new Location(world, 0, 80, 0));
            case "world" -> when(owner.getWorld()).thenReturn(mock(World.class));
            case "stalePurchase" -> village.beaconMoveToken = "newer-token";
            case "replaced" -> mark(replacement, purchase, "different-placement");
            case "missing" -> when(replacement.getType()).thenReturn(Material.AIR);
            case "movedCapital" -> village.beacon = new Position(world.getUID(), 1, 80, 1);
            case "failedUpdate" -> when(((Beacon) replacement.getState(false)).update(false, false)).thenReturn(false);
            case "missingOriginal" -> when(original.getType()).thenReturn(Material.AIR);
            case "missingWorld" -> when(plugin.getServer().getWorld(world.getUID())).thenReturn(null);
        }
        Position before = village.beacon;
        moving.complete(owner, site);
        assertEquals(before, village.beacon);
        verify(original, never()).setType(any(), anyBoolean());
        verify(wallets, never()).setBalance(any(), anyLong());
    }

    private BlockPlaceEvent placeEvent(String token) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.BEACON);
        var data = mock(PersistentDataContainer.class);
        when(item.getPersistentDataContainer()).thenReturn(data);
        when(data.has(CapitalBeaconMoving.MOVE_KEY)).thenReturn(true);
        when(data.get(CapitalBeaconMoving.MOVE_KEY, PersistentDataType.STRING)).thenReturn(token);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getItemInHand()).thenReturn(item);
        when(event.getPlayer()).thenReturn(owner);
        when(event.getBlockPlaced()).thenReturn(replacement);
        when(event.canBuild()).thenReturn(true);
        return event;
    }

    @ParameterizedTest @ValueSource(strings = {"low", "nether", "unclaimed", "enemy", "reserved", "bell", "roof", "elder", "stale", "malformed", "cannotBuild", "update"})
    void invalidPlacementIsCancelledAndKeepsTheOldCapital(String failure) {
        String token = purchase;
        switch (failure) {
            case "low" -> when(replacement.getY()).thenReturn(63);
            case "nether" -> when(world.getEnvironment()).thenReturn(World.Environment.NETHER);
            case "unclaimed" -> store.removePlot(village, Position.at(replacement).plot());
            case "enemy" -> {
                store.removePlot(village, Position.at(replacement).plot());
                store.addVillage(new Village("Enemy", UUID.randomUUID(), Position.at(replacement)));
            }
            case "reserved" -> when(natural.reserved(world, Position.at(replacement).plot())).thenReturn(true);
            case "bell" -> when(natural.inBellProtection(replacement)).thenReturn(true);
            case "roof" -> when(world.getBlockAt(32, 99, 0)).thenReturn(original);
            case "elder" -> when(owner.getUniqueId()).thenReturn(UUID.randomUUID());
            case "stale" -> token = village.id + "/old";
            case "malformed" -> token = "bad-token";
            case "update" -> when(((Beacon) replacement.getState(false)).update(false, false)).thenReturn(false);
        }
        BlockPlaceEvent event = placeEvent(token);
        if (failure.equals("cannotBuild")) when(event.canBuild()).thenReturn(false);
        moving.place(event);
        verify(event).setCancelled(true);
        assertTrue(tasks.isEmpty());
        assertEquals(Position.at(original), village.beacon);
        verify(original, never()).setType(any(), anyBoolean());
    }

    @Test void placingQueuesAConfirmationAndCannotAccidentallyFoundAnotherCivilization() {
        var event = placeEvent(purchase);
        founding.place(event);
        moving.place(event);
        verify(event, never()).setCancelled(true);
        assertEquals(1, tasks.size());
        verify(original, never()).setType(any(), anyBoolean());
        verify(owner, never()).showDialog(any(Dialog.class));
        assertEquals(Position.at(original), village.beacon);
    }

    @Test void duplicateConfirmationsOnlyMoveOnce() {
        Site site = site();
        moving.complete(owner, site);
        moving.complete(owner, site);
        verify(original, times(1)).setType(Material.AIR, false);
        assertEquals(Position.at(replacement), village.beacon);
    }

    @Test void miningReplacementKeepsItTaggedAndCanNeverProduceAnOrdinaryBeacon() {
        BlockDropItemEvent event = mock(BlockDropItemEvent.class);
        Beacon state = (Beacon) replacement.getState(false);
        when(event.getBlockState()).thenReturn(state);
        Item drop = mock(Item.class);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.BEACON);
        when(stack.getAmount()).thenReturn(1);
        when(drop.getItemStack()).thenReturn(stack);
        when(event.getItems()).thenReturn(List.of(drop));
        try (var items = items()) {
            founding.drop(event);
            verify(drop).setItemStack(items.constructed().getFirst());
            verify((PersistentDataContainer) items.constructed().getFirst().getPersistentDataContainer())
                    .set(CapitalBeaconMoving.MOVE_KEY, PersistentDataType.STRING, purchase);
        }
    }

    @Test void dragonEggMovesWithCapitalWithoutLosingItsDefenseProgress() {
        Block egg = mock(Block.class);
        when(egg.getType()).thenReturn(Material.DRAGON_EGG);
        when(world.getBlockAt(0, 81, 0)).thenReturn(egg);
        village.dragonEgg = true;
        village.eggDefendedRaids = 2;
        moving.complete(owner, site());
        verify(air).setType(Material.DRAGON_EGG, false);
        verify(egg).setType(Material.AIR, false);
        assertTrue(village.dragonEgg);
        assertEquals(2, village.eggDefendedRaids);
    }
}
