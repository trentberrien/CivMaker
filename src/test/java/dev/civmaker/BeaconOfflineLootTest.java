package dev.civmaker;

import dev.civmaker.BeaconEscrowLedger.Option;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BeaconOfflineLootTest {
    @TempDir Path directory;
    private CivMaker plugin;
    private CivStore store;
    private CivStore.Village village;
    private CivStore.Window window;
    private BeaconEscrow escrow;
    private Block beacon;
    private World world;
    private Server server;
    private Actor owner, raider;
    private MockedStatic<ItemStack> codec;
    private final Map<String, ItemStack[]> itemSnapshots = new HashMap<>();
    private final Map<String, Map<NamespacedKey, Object>> dataSnapshots = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        plugin = mock(CivMaker.class);
        when(plugin.namespace()).thenReturn("civmaker");
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        beacon = mock(Block.class);
        when(beacon.getWorld()).thenReturn(world);
        when(beacon.getY()).thenReturn(80);
        when(beacon.getType()).thenReturn(Material.BEACON);
        when(beacon.getLocation()).thenReturn(new Location(world, 0, 80, 0));
        codec = mockStatic(ItemStack.class);
        codec.when(() -> ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(call -> {
            String key = UUID.randomUUID().toString();
            itemSnapshots.put(key, ((ItemStack[]) call.getArgument(0)).clone());
            return key.getBytes(StandardCharsets.UTF_8);
        });
        codec.when(() -> ItemStack.deserializeItemsFromBytes(any(byte[].class))).thenAnswer(call ->
                itemSnapshots.get(new String((byte[]) call.getArgument(0), StandardCharsets.UTF_8)).clone());
        owner = new Actor();
        raider = new Actor();
        store = new CivStore(directory);
        village = new CivStore.Village("Defenders", owner.id, CivStore.Position.at(beacon));
        store.addVillage(village);
        store.setRaidMode(CivStore.RaidMode.ON);
        window = store.activeWindow(System.currentTimeMillis());
        reload();
    }

    @AfterEach void closeCodec() { if (codec != null) codec.close(); }
    private void reload() throws Exception { escrow = new BeaconEscrow(plugin, store, new Wallets(plugin), mock(Notes.class)); }
    private ItemStack stack() {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(1);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.asQuantity(1)).thenReturn(item);
        return item;
    }
    private void logout() {
        escrow.departing(owner.player);
        when(owner.player.isOnline()).thenReturn(false);
    }

    @Test void bothItemSourcesAreAvailableWithoutMoneyAndLoginKeepsOnlyTheUnstolenItems() throws Exception {
        ItemStack carried = stack(), armor = stack(), cursor = stack(), ender = stack(), kept = stack();
        owner.contents[0] = carried;
        owner.contents[36] = armor;
        owner.cursor = cursor;
        owner.ender[2] = ender;
        owner.ender[4] = kept;
        logout();
        assertEquals(1, escrow.offlinePlayers(village).size());
        assertTrue(escrow.claimItems(raider.player, beacon, village, window, Option.INVENTORY, owner.id));
        assertTrue(escrow.takeItem(raider.player, owner.id, village, Option.INVENTORY, 0));
        assertTrue(escrow.takeItem(raider.player, owner.id, village, Option.INVENTORY, 36));
        assertTrue(escrow.takeItem(raider.player, owner.id, village, Option.INVENTORY, 41));
        assertTrue(escrow.claimItems(raider.player, beacon, village, window, Option.ENDER_CHEST, owner.id));
        assertTrue(escrow.takeItem(raider.player, owner.id, village, Option.ENDER_CHEST, 2));
        assertFalse(escrow.takeItem(raider.player, owner.id, village, Option.ENDER_CHEST, 2));
        reload();
        assertTrue(escrow.looted(village, window, Option.INVENTORY));
        assertTrue(escrow.looted(village, window, Option.ENDER_CHEST));
        assertFalse(escrow.looted(village, window, Option.MONEY));
        when(owner.player.isOnline()).thenReturn(true);
        escrow.returning(owner.player);
        assertNull(owner.contents[0]);
        assertNull(owner.contents[36]);
        assertNull(owner.cursor);
        assertNull(owner.ender[2]);
        assertSame(kept, owner.ender[4]);
        assertEquals(4, Arrays.stream(raider.contents).filter(item -> item != null).count());
        owner.contents[0] = stack();
        escrow.returning(owner.player);
        assertNotNull(owner.contents[0], "A completed receipt must not overwrite later inventory changes");
    }

    @Test void ownerLoginMembershipChangeAndRaidProtectionInvalidateAccess() {
        owner.contents[0] = stack();
        logout();
        when(owner.player.isOnline()).thenReturn(true);
        assertNull(escrow.items(owner.id, village, Option.INVENTORY));
        assertFalse(escrow.claimItems(raider.player, beacon, village, window, Option.INVENTORY, owner.id));
        when(owner.player.isOnline()).thenReturn(false);
        village.shieldWindow = window.start();
        assertFalse(escrow.claimItems(raider.player, beacon, village, window, Option.INVENTORY, owner.id));
        village.shieldWindow = null;
        store.disband(village);
        assertNull(escrow.items(owner.id, village, Option.INVENTORY));
    }

    @Test void fullInventoryLeavesVictimUntouchedAndInterruptedDeliveryRecoversExactlyOnce() throws Exception {
        ItemStack loot = stack();
        owner.ender[0] = loot;
        logout();
        assertTrue(escrow.claimItems(raider.player, beacon, village, window, Option.ENDER_CHEST, owner.id));
        ItemStack full = mock(ItemStack.class);
        when(full.getAmount()).thenReturn(64);
        Arrays.fill(raider.contents, full);
        assertFalse(escrow.takeItem(raider.player, owner.id, village, Option.ENDER_CHEST, 0));
        assertSame(loot, escrow.items(owner.id, village, Option.ENDER_CHEST)[0]);
        Arrays.fill(raider.contents, null);
        raider.save();
        doThrow(new IllegalStateException("Interrupted save")).when(raider.player).saveData();
        assertFalse(escrow.takeItem(raider.player, owner.id, village, Option.ENDER_CHEST, 0));
        raider.crash();
        doAnswer(call -> { raider.save(); return null; }).when(raider.player).saveData();
        reload();
        escrow.returning(raider.player);
        assertSame(loot, raider.contents[0]);
        escrow.returning(raider.player);
        assertEquals(1, Arrays.stream(raider.contents).filter(item -> item != null).count());
        escrow.returning(owner.player);
        assertNull(owner.ender[0]);
    }

    private final class Actor {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final Map<NamespacedKey, Object> data = new HashMap<>();
        ItemStack[] contents = new ItemStack[41], ender = new ItemStack[27];
        ItemStack cursor;
        ItemStack[] savedContents, savedEnder;
        Map<NamespacedKey, Object> savedData;

        Actor() throws Exception {
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn(id.toString());
            when(server.getPlayer(id)).thenReturn(player);
            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(new Location(world, 0.5, 80, 1));
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            PlayerInventory inventory = mock(PlayerInventory.class);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getContents()).thenAnswer(call -> contents.clone());
            when(inventory.getStorageContents()).thenAnswer(call -> Arrays.copyOf(contents, 36));
            when(inventory.getMaxStackSize()).thenReturn(64);
            doAnswer(call -> { contents = ((ItemStack[]) call.getArgument(0)).clone(); return null; }).when(inventory).setContents(any());
            Inventory chest = mock(Inventory.class);
            when(player.getEnderChest()).thenReturn(chest);
            when(chest.getContents()).thenAnswer(call -> ender.clone());
            doAnswer(call -> { ender = ((ItemStack[]) call.getArgument(0)).clone(); return null; }).when(chest).setContents(any());
            when(player.getItemOnCursor()).thenAnswer(call -> cursor);
            doAnswer(call -> { cursor = call.getArgument(0); return null; }).when(player).setItemOnCursor(any());
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
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
            doAnswer(call -> { save(); return null; }).when(player).saveData();
        }
        void save() { savedContents = contents.clone(); savedEnder = ender.clone(); savedData = new HashMap<>(data); }
        void crash() { contents = savedContents.clone(); ender = savedEnder.clone(); data.clear(); data.putAll(savedData); }
    }
}
