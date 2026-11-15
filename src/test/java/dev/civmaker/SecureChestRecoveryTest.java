package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecureChestRecoveryTest {
    @TempDir Path directory;
    private CivStore store;
    private RaidEquipment equipment;
    private SecureChestRecovery recovery;
    private MoneyStorage money;
    private Protection protection;
    private SettlementResources resources;
    private World world;
    private Player owner;
    private Village village;
    private ItemStack kit;
    private final Set<Block> secure = new HashSet<>();
    private final List<Runnable> tasks = new ArrayList<>();

    private record Site(Block block, Chest chest, Inventory inventory) {}

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(call -> { tasks.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        equipment = mock(RaidEquipment.class);
        when(equipment.isVault(any(Block.class))).thenAnswer(call -> secure.contains(call.getArgument(0)));
        when(equipment.reinforce(any(Chest.class))).thenAnswer(call -> {
            secure.add(call.<Chest>getArgument(0).getBlock());
            return true;
        });
        kit = mock(ItemStack.class);
        when(equipment.item(RaidEquipment.Tool.VAULT, null)).thenReturn(kit);
        recovery = new SecureChestRecovery(plugin, store, equipment);
        resources = mock(SettlementResources.class);
        money = new MoneyStorage(mock(Notes.class), store, equipment, resources);
        protection = new Protection(store, equipment, mock(VaultDoors.class), mock(NaturalVillages.class),
                mock(DefenderGolems.class), mock(FarmStations.class));
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        owner = player();
        village = new Village("Test", owner.getUniqueId(), new Position(world.getUID(), 0, 80, 0));
        store.addVillage(village);
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        return player;
    }

    private Site site(int x) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.CHEST);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(80);
        when(block.getZ()).thenReturn(2);
        when(block.getLocation()).thenAnswer(call -> new Location(world, x, 80, 2));
        Chest chest = mock(Chest.class);
        when(chest.getBlock()).thenReturn(block);
        when(chest.getType()).thenReturn(Material.CHEST);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(chest);
        when(inventory.getContents()).thenReturn(new ItemStack[0]);
        when(chest.getInventory()).thenReturn(inventory);
        when(equipment.storageAt(block)).thenReturn(chest);
        return new Site(block, chest, inventory);
    }

    private void join(Site left, Site right) {
        DoubleChest holder = mock(DoubleChest.class);
        when(holder.getLeftSide()).thenReturn(left.chest());
        when(holder.getRightSide()).thenReturn(right.chest());
        Inventory combined = mock(Inventory.class);
        when(combined.getHolder()).thenReturn(holder);
        when(combined.getContents()).thenReturn(new ItemStack[0]);
        when(left.chest().getInventory()).thenReturn(combined);
        when(right.chest().getInventory()).thenReturn(combined);
    }

    private BlockBreakEvent begin(Site site, Player player, boolean drops) {
        BlockBreakEvent event = new BlockBreakEvent(site.block(), player);
        event.setDropItems(drops);
        protection.breakBlock(event);
        if (!event.isCancelled()) money.breakVault(event);
        recovery.recordBreak(event);
        return event;
    }

    private BlockDropItemEvent finish(Site site, BlockBreakEvent event, Site other, boolean cancelDrops) {
        assertFalse(event.isCancelled());
        when(site.block().getType()).thenReturn(Material.AIR);
        secure.remove(site.block());
        if (other != null) when(other.chest().getInventory()).thenReturn(other.inventory());
        BlockDropItemEvent drops = new BlockDropItemEvent(site.block(), site.chest(), event.getPlayer(), new ArrayList<>());
        drops.setCancelled(cancelDrops);
        if (event.isDropItems() && event.getPlayer().getGameMode() == GameMode.SURVIVAL) recovery.recover(drops);
        for (Runnable task : List.copyOf(tasks)) task.run();
        tasks.clear();
        return drops;
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "elder", "member"})
    void ownProtectedSingleChestReturnsOneKitAlongsideItsNormalDrops(String role) {
        Player miner = owner;
        if (!role.equals("owner")) {
            miner = player();
            store.addMember(village, miner.getUniqueId());
            store.setElder(village, miner.getUniqueId(), role.equals("elder"));
        }
        Site chest = site(2);
        secure.add(chest.block());
        var event = begin(chest, miner, true);
        var drops = finish(chest, event, null, false);
        recovery.recover(drops);
        verify(world, times(1)).dropItemNaturally(new Location(world, 2.5, 80.5, 2.5), kit);
    }

    @ParameterizedTest @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void doubleChestReturnsOneKitOnlyAfterBothBlocksAreMined(boolean markedFirst, boolean bothMarked) {
        Site left = site(2), right = site(3);
        join(left, right);
        secure.add(left.block());
        if (bothMarked) secure.add(right.block());
        Site first = markedFirst ? left : right, last = markedFirst ? right : left;
        finish(first, begin(first, owner, true), last, false);
        verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
        assertTrue(secure.contains(last.block()), "The surviving half must keep its protection");
        finish(last, begin(last, owner, true), null, false);
        verify(world, times(1)).dropItemNaturally(any(Location.class), eq(kit));
    }

    @ParameterizedTest @ValueSource(strings = {"creative", "noDrops", "cancelDrops"})
    void firstHalfKeepsTheOtherHalfSecureEvenWhenItDoesNotDropItems(String mode) {
        Site left = site(2), right = site(3);
        join(left, right);
        secure.add(left.block());
        if (mode.equals("creative")) when(owner.getGameMode()).thenReturn(GameMode.CREATIVE);
        finish(left, begin(left, owner, !mode.equals("noDrops")), right, mode.equals("cancelDrops"));
        assertTrue(secure.contains(right.block()));
        verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
    }

    @ParameterizedTest @ValueSource(strings = {"ordinary", "creative", "noDrops", "cancelDrops", "unclaimed", "lostMembership"})
    void ineligibleBreaksNeverReturnAKit(String mode) {
        Site chest = site(2);
        if (!mode.equals("ordinary")) secure.add(chest.block());
        if (mode.equals("creative")) when(owner.getGameMode()).thenReturn(GameMode.CREATIVE);
        if (mode.equals("unclaimed")) store.disband(village);
        BlockBreakEvent event = begin(chest, owner, !mode.equals("noDrops"));
        if (mode.equals("lostMembership")) store.disband(village);
        finish(chest, event, null, mode.equals("cancelDrops"));
        verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
    }

    @Test void outsidersAndCurrencyRestrictedMembersCannotGetARefundByBreakingTheChest() {
        Site chest = site(2);
        secure.add(chest.block());
        assertTrue(begin(chest, player(), true).isCancelled());
        Player member = player();
        store.addMember(village, member.getUniqueId());
        ItemStack currency = mock(ItemStack.class);
        when(resources.isCurrency(currency)).thenReturn(true);
        when(chest.inventory().getContents()).thenReturn(new ItemStack[]{currency});
        assertTrue(begin(chest, member, true).isCancelled());
        assertTrue(tasks.isEmpty());
        verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
    }

    @Test void cancelledBreaksDoNotTransferTheSecureMarker() {
        Site left = site(2), right = site(3);
        join(left, right);
        secure.add(left.block());
        var event = new BlockBreakEvent(left.block(), owner);
        event.setCancelled(true);
        recovery.recordBreak(event);
        assertTrue(tasks.isEmpty());
        assertFalse(secure.contains(right.block()));
        verify(equipment, never()).reinforce(any());
    }
}
