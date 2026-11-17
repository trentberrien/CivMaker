package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.RaidMode;
import dev.civmaker.CivStore.Village;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DragonEggsTest {
    @TempDir Path directory;
    private CivStore store;
    private CivMaker plugin;
    private DragonEggs eggs;
    private Protection protection;
    private World world;
    private Block beacon;
    private Block egg;
    private Village village;
    private Player owner;
    private Player raider;
    private PluginManager events;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        events = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(events);
        eggs = new DragonEggs(plugin, store);
        protection = new Protection(store, mock(RaidEquipment.class), mock(VaultDoors.class),
                mock(NaturalVillages.class), mock(DefenderGolems.class), mock(FarmStations.class));
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(server.getWorld(world.getUID())).thenReturn(world);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        beacon = block(0, 80, Material.BEACON);
        egg = block(0, 81, Material.DRAGON_EGG);
        owner = player(UUID.randomUUID());
        raider = player(UUID.randomUUID());
        village = new Village("Holders", owner.getUniqueId(), Position.at(beacon));
        store.addVillage(village);
        doAnswer(call -> {
            BlockBreakEvent broken = call.getArgument(0);
            protection.breakBlock(broken);
            if (!broken.isCancelled()) eggs.broken(broken);
            return null;
        }).when(events).callEvent(any(BlockBreakEvent.class));
    }

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        return player;
    }

    private Block block(int x, int y, Material material) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getType()).thenReturn(material);
        when(block.getLocation()).thenReturn(new Location(world, x, y, 0));
        when(world.getBlockAt(x, y, 0)).thenReturn(block);
        return block;
    }

    private PlayerInteractEvent interact(Player player, Block block, Action action, ItemStack item) {
        return new PlayerInteractEvent(player, action, item, block, BlockFace.UP, EquipmentSlot.HAND);
    }

    @Test void onlyAnEggDirectlyOnTheCapitalCountsAndOnlyMembersCanPlaceIt() {
        ItemStack held = mock(ItemStack.class);
        when(held.getType()).thenReturn(Material.DRAGON_EGG);
        BlockPlaceEvent placed = new BlockPlaceEvent(egg, mock(BlockState.class), beacon, held, owner, true, EquipmentSlot.HAND);
        protection.place(placed);
        assertFalse(placed.isCancelled());
        eggs.placed(placed);
        assertEquals(350, village.points());
        var enemyPlace = new BlockPlaceEvent(egg, mock(BlockState.class), beacon, held, raider, true, EquipmentSlot.HAND);
        protection.place(enemyPlace);
        assertTrue(enemyPlace.isCancelled());
        when(egg.getType()).thenReturn(Material.STONE);
        var stone = new BlockPlaceEvent(egg, mock(BlockState.class), beacon, held, owner, true, EquipmentSlot.HAND);
        protection.place(stone);
        assertTrue(stone.isCancelled());
        eggs.refresh();
        assertEquals(100, village.points());
        Block nearby = block(1, 81, Material.DRAGON_EGG);
        assertNull(store.eggAt(nearby));
    }

    @Test void holdingEggOnBeaconBypassesBothCapitalMenusWithoutCancellingPlacement() {
        ItemStack held = mock(ItemStack.class);
        when(held.getType()).thenReturn(Material.DRAGON_EGG);
        when(plugin.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        ClaimBuying claims = new ClaimBuying(plugin, store, mock(Wallets.class), mock(NaturalVillages.class));
        FoundingBeacons founding = new FoundingBeacons(plugin, store, mock(Wallets.class), mock(NaturalVillages.class));
        PlayerInteractEvent click = interact(owner, beacon, Action.RIGHT_CLICK_BLOCK, held);
        protection.interact(click);
        founding.interact(click);
        claims.click(click);
        assertEquals(Event.Result.DENY, click.useInteractedBlock());
        assertNotEquals(Event.Result.DENY, click.useItemInHand());
        verify(owner, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
    }

    @Test void outsidersCanMineEggDuringRaidsButNeitherOwnerNorEnemyCanMineOutsideRaids() {
        assertTrue(deniedBreak(owner));
        assertTrue(deniedBreak(raider));
        store.setRaidMode(RaidMode.ON);
        assertFalse(deniedBreak(raider));
        assertFalse(deniedBreak(owner));
        village.shieldWindow = store.activeWindow(System.currentTimeMillis()).start();
        assertTrue(deniedBreak(raider));
        village.shieldWindow = null;
        village.eggProtected = true;
        assertTrue(deniedBreak(raider));
        assertTrue(deniedBreak(owner));
    }

    private boolean deniedBreak(Player player) {
        BlockBreakEvent event = new BlockBreakEvent(egg, player);
        protection.breakBlock(event);
        return event.isCancelled();
    }

    @Test void leftClickDuringRaidDropsOneEggInsteadOfTeleportingAndRemovesBonus() {
        eggs.refresh();
        store.setRaidMode(RaidMode.ON);
        PlayerInteractEvent click = interact(raider, egg, Action.LEFT_CLICK_BLOCK, null);
        protection.interact(click);
        try (var stacks = mockConstruction(ItemStack.class)) {
            eggs.interact(click);
            assertEquals(1, stacks.constructed().size());
            verify(world).dropItemNaturally(eq(egg.getLocation()), eq(stacks.constructed().getFirst()));
        }
        assertEquals(Event.Result.DENY, click.useInteractedBlock());
        verify(egg).setType(Material.AIR, false);
        assertEquals(100, village.points());
    }

    @Test void cancelledBreakAndRightClickCannotRemoveOrTeleportEgg() {
        eggs.refresh();
        store.setRaidMode(RaidMode.ON);
        doAnswer(call -> { ((BlockBreakEvent) call.getArgument(0)).setCancelled(true); return null; })
                .when(events).callEvent(any(BlockBreakEvent.class));
        eggs.interact(interact(raider, egg, Action.LEFT_CLICK_BLOCK, null));
        var rightClick = interact(raider, egg, Action.RIGHT_CLICK_BLOCK, null);
        eggs.interact(rightClick);
        assertEquals(Event.Result.DENY, rightClick.useInteractedBlock());
        verify(egg, never()).setType(any(Material.class), anyBoolean());
        verify(world, never()).dropItemNaturally(any(), any());
        assertEquals(350, village.points());
    }

    @Test void teleportAndExplosionsCannotBypassEggProtectionEvenAboveClaimHeight() {
        var teleport = new BlockFromToEvent(egg, block(1, 81, Material.AIR));
        protection.liquid(teleport);
        assertTrue(teleport.isCancelled());
        EntityExplodeEvent explosion = mock(EntityExplodeEvent.class);
        var destroyed = new ArrayList<>(List.of(egg));
        when(explosion.blockList()).thenReturn(destroyed);
        protection.entityExplosion(explosion);
        assertTrue(destroyed.isEmpty());
        Village high = new Village("High", UUID.randomUUID(), new Position(world.getUID(), 32, 220, 0));
        store.addVillage(high);
        Block highEgg = block(32, 221, Material.DRAGON_EGG);
        assertSame(high, store.protectedClaimAt(highEgg));
        assertSame(high, store.eggAt(highEgg));
    }

    @Test void deathEventCreditsTheKillerRatherThanTheVictimOrFaction() {
        store.setRaidMode(RaidMode.ON);
        when(raider.getKiller()).thenReturn(owner);
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(raider);
        eggs.death(death);
        assertEquals(1, store.raidKills(owner.getUniqueId()));
        assertEquals(0, store.raidKills(raider.getUniqueId()));
        assertEquals(150, village.points());
    }
}
