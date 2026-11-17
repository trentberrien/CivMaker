package dev.civmaker;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BeaconEscrowTest {
    @TempDir Path directory;
    private CivMaker plugin;
    private CivStore store;
    private CivStore.Village village;
    private Wallets wallets;
    private Notes notes;
    private BeaconEscrow escrow;
    private Player player;
    private PlayerInventory inventory;
    private PersistentDataContainer pdc;
    private World world;
    private Block beacon;
    private final Map<NamespacedKey, Object> data = new HashMap<>();
    private final Map<String, Map<NamespacedKey, Object>> dataSnapshots = new HashMap<>();
    private final Map<String, ItemStack[]> itemSnapshots = new HashMap<>();
    private final AtomicReference<ItemStack[]> contents = new AtomicReference<>(new ItemStack[41]);
    private final AtomicReference<ItemStack> cursor = new AtomicReference<>();
    private Map<NamespacedKey, Object> savedData;
    private ItemStack[] savedContents;
    private MockedStatic<ItemStack> codec;

    @BeforeEach void setup() throws Exception {
        plugin = mock(CivMaker.class);
        when(plugin.namespace()).thenReturn("civmaker");
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenAnswer(call -> contents.get().clone());
        doAnswer(call -> { contents.set(((ItemStack[]) call.getArgument(0)).clone()); return null; })
                .when(inventory).setContents(any(ItemStack[].class));
        when(player.getItemOnCursor()).thenAnswer(call -> cursor.get());
        doAnswer(call -> { cursor.set(call.getArgument(0)); return null; }).when(player).setItemOnCursor(any());
        pdc = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), any())).thenAnswer(call -> data.get(call.getArgument(0)));
        when(pdc.getOrDefault(any(), any(), any())).thenAnswer(call -> data.getOrDefault(call.getArgument(0), call.getArgument(2)));
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(), any(), any());
        when(pdc.serializeToBytes()).thenAnswer(call -> {
            String key = UUID.randomUUID().toString();
            dataSnapshots.put(key, new HashMap<>(data));
            return key.getBytes(StandardCharsets.UTF_8);
        });
        doAnswer(call -> {
            data.clear();
            data.putAll(dataSnapshots.get(new String((byte[]) call.getArgument(0), StandardCharsets.UTF_8)));
            return null;
        }).when(pdc).readFromBytes(any(), eq(true));
        doAnswer(call -> { savePlayer(); return null; }).when(player).saveData();
        codec = mockStatic(ItemStack.class);
        codec.when(() -> ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(call -> {
            String key = UUID.randomUUID().toString();
            itemSnapshots.put(key, ((ItemStack[]) call.getArgument(0)).clone());
            return key.getBytes(StandardCharsets.UTF_8);
        });
        codec.when(() -> ItemStack.deserializeItemsFromBytes(any(byte[].class))).thenAnswer(call ->
                itemSnapshots.get(new String((byte[]) call.getArgument(0), StandardCharsets.UTF_8)).clone());
        store = new CivStore(directory);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 80, 1));
        beacon = mock(Block.class);
        when(beacon.getWorld()).thenReturn(world);
        when(beacon.getY()).thenReturn(80);
        when(beacon.getType()).thenReturn(Material.BEACON);
        when(beacon.getLocation()).thenReturn(new Location(world, 0, 80, 0));
        village = new CivStore.Village("Test", player.getUniqueId(), CivStore.Position.at(beacon));
        store.addVillage(village);
        wallets = new Wallets(plugin);
        notes = mock(Notes.class);
        escrow = new BeaconEscrow(plugin, store, wallets, notes);
        savePlayer();
    }

    @AfterEach void closeCodec() { if (codec != null) codec.close(); }

    private void savePlayer() {
        savedData = new HashMap<>(data);
        savedContents = contents.get().clone();
    }

    private void crashPlayer() {
        data.clear();
        data.putAll(savedData);
        contents.set(savedContents.clone());
        cursor.set(null);
    }

    private ItemStack note(int amount, long value) {
        ItemStack note = mock(ItemStack.class);
        when(notes.isNote(note)).thenReturn(true);
        when(notes.faceValue(note)).thenReturn(value);
        when(note.getAmount()).thenReturn(amount);
        return note;
    }

    @Test void logoutIncludesWalletInventoryOffhandAndCursorAndReconnectPaysOnce() throws Exception {
        wallets.setBalance(player, 700);
        contents.get()[0] = note(2, 1000);
        contents.get()[40] = note(1, 1000);
        cursor.set(note(1, 5000));
        ItemStack sword = mock(ItemStack.class);
        contents.get()[8] = sword;
        escrow.departing(player);
        assertEquals(0, wallets.balance(player));
        assertEquals(BigInteger.valueOf(8700), escrow.total(village));
        assertNull(contents.get()[0]);
        assertNull(contents.get()[40]);
        assertNull(cursor.get());
        assertSame(sword, contents.get()[8]);
        escrow.departing(player);
        assertEquals(BigInteger.valueOf(8700), escrow.total(village));
        escrow.returning(player);
        assertEquals(8700, wallets.balance(player));
        escrow.returning(player);
        assertEquals(8700, wallets.balance(player));
        assertEquals(BigInteger.ZERO, escrow.total(village));
    }

    @Test void crashBeforePlayerSaveReplaysDebitBeforeRefundWithoutDuplication() throws Exception {
        wallets.setBalance(player, 500);
        contents.get()[1] = note(2, 1000);
        savePlayer();
        doThrow(new IllegalStateException("Simulated disk failure")).when(player).saveData();
        escrow.departing(player);
        assertEquals(BigInteger.valueOf(2500), new BeaconEscrowLedger(directory).total(village));
        crashPlayer();
        doAnswer(call -> { savePlayer(); return null; }).when(player).saveData();
        escrow = new BeaconEscrow(plugin, store, wallets, notes);
        escrow.returning(player);
        assertEquals(2500, wallets.balance(player));
        assertNull(contents.get()[1]);
        assertEquals(BigInteger.ZERO, escrow.total(village));
        escrow.returning(player);
        assertEquals(2500, wallets.balance(player));
    }

    @Test void crashDuringRefundReplaysCreditOnceAndRecoversClearedTradeEscrow() throws Exception {
        NamespacedKey trade = new NamespacedKey("civmaker", "trade_escrow");
        data.put(trade, "stale trade items");
        wallets.setBalance(player, 1200);
        savePlayer();
        // A normal trade-close handler has refunded the items and cleared its PDC before logout.
        data.remove(trade);
        ItemStack refundedSword = mock(ItemStack.class);
        contents.get()[4] = refundedSword;
        escrow.departing(player);
        doThrow(new IllegalStateException("Simulated interrupted refund")).when(player).saveData();
        escrow.returning(player);
        assertEquals(1200, wallets.balance(player));
        crashPlayer();
        doAnswer(call -> { savePlayer(); return null; }).when(player).saveData();
        escrow = new BeaconEscrow(plugin, store, wallets, notes);
        escrow.returning(player);
        assertEquals(1200, wallets.balance(player));
        assertSame(refundedSword, contents.get()[4]);
        assertFalse(data.containsKey(trade));
        escrow.returning(player);
        assertEquals(1200, wallets.balance(player));
    }

    @Test void completedReceiptRecoversAPlayerSaveThatSilentlyFailed() throws Exception {
        wallets.setBalance(player, 1500);
        savePlayer();
        doNothing().when(player).saveData();
        escrow.departing(player);
        assertNull(new BeaconEscrowLedger(directory).pending(player.getUniqueId()));
        crashPlayer();
        doAnswer(call -> { savePlayer(); return null; }).when(player).saveData();
        escrow = new BeaconEscrow(plugin, store, wallets, notes);
        escrow.returning(player);
        assertEquals(1500, wallets.balance(player));
        assertEquals(BigInteger.ZERO, escrow.total(village));
    }

    @Test void failedLedgerWriteDoesNotTouchMoneyOrInventory() throws Exception {
        wallets.setBalance(player, 700);
        ItemStack note = note(1, 1000);
        contents.get()[0] = note;
        Files.createDirectory(directory.resolve("beacon-escrow.json.tmp"));
        escrow.departing(player);
        assertEquals(700, wallets.balance(player));
        assertSame(note, contents.get()[0]);
        assertEquals(BigInteger.ZERO, escrow.total(village));
        verify(player, never()).saveData();
    }

    @Test void overflowIsReservedAndPaidWhenWalletHasRoom() throws Exception {
        wallets.setBalance(player, Long.MAX_VALUE);
        contents.get()[0] = note(1, 1000);
        escrow.departing(player);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(1000)), escrow.total(village));
        escrow.returning(player);
        assertEquals(Long.MAX_VALUE, wallets.balance(player));
        assertEquals(BigInteger.ZERO, escrow.total(village));
        wallets.setBalance(player, Long.MAX_VALUE - 1500);
        escrow.refresh();
        assertEquals(Long.MAX_VALUE - 500, wallets.balance(player));
        escrow.refresh();
        assertEquals(Long.MAX_VALUE - 500, wallets.balance(player));
    }

    @Test void nestedLegacyBanknotesAreRemovedWithoutDestroyingContainerOrOtherItems() {
        ItemStack bundle = mock(ItemStack.class), changed = mock(ItemStack.class), stone = mock(ItemStack.class);
        BundleMeta meta = mock(BundleMeta.class);
        when(bundle.getItemMeta()).thenReturn(meta);
        when(bundle.getAmount()).thenReturn(1);
        when(bundle.clone()).thenReturn(changed);
        ItemStack nestedNote = note(3, 1000);
        when(meta.getItems()).thenReturn(List.of(nestedNote, stone));
        contents.get()[3] = bundle;
        escrow.departing(player);
        assertEquals(BigInteger.valueOf(3000), escrow.total(village));
        assertSame(changed, contents.get()[3]);
        verify(meta).setItems(List.of(stone));
        verify(changed).setItemMeta(meta);
    }

    @Test void successfulRaidPaysQuarterAndSecondRaiderCannotLootAgain() throws Exception {
        wallets.setBalance(player, 4000);
        escrow.departing(player);
        Player raider = mock(Player.class);
        when(raider.getUniqueId()).thenReturn(UUID.randomUUID());
        when(raider.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(raider.getWorld()).thenReturn(world);
        when(raider.getLocation()).thenReturn(new Location(world, 0.5, 80, 1));
        when(raider.isOnline()).thenReturn(true);
        Map<NamespacedKey, Object> raiderData = new HashMap<>();
        PersistentDataContainer raiderPdc = mock(PersistentDataContainer.class);
        when(raiderPdc.get(any(), any())).thenAnswer(call -> raiderData.get(call.getArgument(0)));
        when(raiderPdc.getOrDefault(any(), any(), any())).thenAnswer(call -> raiderData.getOrDefault(call.getArgument(0), call.getArgument(2)));
        doAnswer(call -> { raiderData.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(raiderPdc).set(any(), any(), any());
        when(raiderPdc.serializeToBytes()).thenAnswer(call -> {
            String key = UUID.randomUUID().toString();
            dataSnapshots.put(key, new HashMap<>(raiderData));
            return key.getBytes(StandardCharsets.UTF_8);
        });
        doAnswer(call -> {
            raiderData.clear();
            raiderData.putAll(dataSnapshots.get(new String((byte[]) call.getArgument(0), StandardCharsets.UTF_8)));
            return null;
        }).when(raiderPdc).readFromBytes(any(), eq(true));
        when(raider.getPersistentDataContainer()).thenReturn(raiderPdc);
        when(raider.getInventory()).thenReturn(inventory);
        store.setRaidMode(CivStore.RaidMode.ON);
        var window = store.activeWindow(System.currentTimeMillis());
        escrow.loot(raider, beacon, village, window);
        assertEquals(1000, wallets.balance(raider));
        assertEquals(BigInteger.valueOf(3000), escrow.total(village));
        escrow.loot(raider, beacon, village, window);
        assertEquals(1000, wallets.balance(raider));
        assertEquals(BigInteger.valueOf(3000), escrow.total(village));
        escrow.returning(player);
        assertEquals(3000, wallets.balance(player));
        assertEquals(1000, wallets.balance(raider));
    }

    @Test void ownBeaconShieldAndSettlementOnlyWindowsRejectRaids() {
        wallets.setBalance(player, 400);
        escrow.departing(player);
        store.setRaidMode(CivStore.RaidMode.ON);
        assertFalse(escrow.canPick(player, beacon, village, store.activeWindow(System.currentTimeMillis())));
        Player outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        when(outsider.getGameMode()).thenReturn(GameMode.SURVIVAL);
        village.shieldWindow = store.activeWindow(System.currentTimeMillis()).start();
        assertFalse(escrow.canPick(outsider, beacon, village, store.activeWindow(System.currentTimeMillis())));
        village.shieldWindow = null;
        store.setRaidMode(CivStore.RaidMode.SETTLEMENTS);
        assertFalse(escrow.canPick(outsider, beacon, village, store.activeWindow(System.currentTimeMillis())));
    }
}
