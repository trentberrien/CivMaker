package dev.civmaker;

import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WildernessTest {
    private CivMaker plugin;
    private CivStore store;
    private CombatTimeout combat;
    private World world;
    private WorldBorder border;
    private Player player;
    private Wilderness wilderness;
    private Block floor;
    private CompletableFuture<Chunk> chunk;
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @BeforeEach void setup() {
        plugin = mock(CivMaker.class);
        when(plugin.isEnabled()).thenReturn(true);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return null;
        });
        store = mock(CivStore.class);
        combat = mock(CombatTimeout.class);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        border = mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.getCenter()).thenReturn(new Location(world, 100.5, 0, -199.5));
        when(border.getSize()).thenReturn(1.0); // Exactly one eligible block column.
        when(world.getHighestBlockYAt(anyInt(), anyInt(), eq(HeightMap.MOTION_BLOCKING))).thenReturn(64);
        floor = block(Material.STONE, 64);
        when(world.getBlockAt(anyInt(), eq(64), anyInt())).thenReturn(floor);
        doReturn(block(Material.AIR, 65)).when(world).getBlockAt(anyInt(), eq(65), anyInt());
        doReturn(block(Material.AIR, 66)).when(world).getBlockAt(anyInt(), eq(66), anyInt());
        chunk = new CompletableFuture<>();
        when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(chunk);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.getYaw()).thenReturn(32f);
        when(player.getPitch()).thenReturn(12f);
        when(player.teleport(any(Location.class), eq(TeleportCause.COMMAND))).thenReturn(true);
        wilderness = new Wilderness(plugin, store, combat);
    }

    private Block block(Material material, int y) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.isEmpty()).thenReturn(material == Material.AIR);
        when(block.isSolid()).thenReturn(material != Material.AIR && material != Material.WATER
                && material != Material.LAVA && material != Material.POWDER_SNOW);
        when(block.getY()).thenReturn(y);
        when(block.getBoundingBox()).thenReturn(new BoundingBox(100, y, -200, 101, y + 1, -199));
        return block;
    }

    private void run() {
        assertTrue(wilderness.onCommand(player, null, "wilderness", new String[0]));
    }

    private void drain() {
        int limit = 200;
        while (!tasks.isEmpty() && limit-- > 0) tasks.remove().run();
        assertTrue(tasks.isEmpty(), "Search must finish within a bounded number of tasks");
    }

    private void load() {
        chunk.complete(mock(Chunk.class));
        drain();
    }

    @Test void loadsBeforeTeleportingAndPreservesViewDirection() {
        run();
        verify(player, never()).teleport(any(Location.class), any(TeleportCause.class));
        verify(world).getChunkAtAsync(6, -13, true);
        load();
        var destination = ArgumentCaptor.forClass(Location.class);
        verify(player).teleport(destination.capture(), eq(TeleportCause.COMMAND));
        assertEquals(new Location(world, 100.5, 65, -199.5, 32f, 12f), destination.getValue());
        verify(player).sendMessage("Teleported to the wilderness.");
    }

    @Test void boundsHandleShiftedFractionalCentersAndWholePlayerWidth() {
        when(border.getCenter()).thenReturn(new Location(world, 10.25, 0, -20.75));
        when(border.getSize()).thenReturn(10.0);
        assertEquals(new Wilderness.Bounds(6, 14, -25, -17), Wilderness.Bounds.inside(border));
        when(border.getSize()).thenReturn(0.5);
        assertNull(Wilderness.Bounds.inside(border));
    }

    @Test void boundsAreClampedToMinecraftWorldLimits() {
        when(border.getCenter()).thenReturn(new Location(world, 29_999_980, 0, 0));
        when(border.getSize()).thenReturn(100.0);
        assertEquals(29_999_983, Wilderness.Bounds.inside(border).maxX());
    }

    @Test void rejectsConsoleArgumentsAndCombatTimeout() {
        CommandSender console = mock(CommandSender.class);
        wilderness.onCommand(console, null, "wilderness", new String[0]);
        verify(console).sendMessage("Only players can use /wilderness.");
        wilderness.onCommand(player, null, "wilderness", new String[]{"someone"});
        verify(player).sendMessage("Usage: /wilderness");
        when(combat.locked(player)).thenReturn(true);
        run();
        verify(world, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
    }

    @Test void duplicateCommandsDoNotStartAdditionalSearches() {
        run();
        run();
        verify(world, times(1)).getChunkAtAsync(anyInt(), anyInt(), eq(true));
        load();
        verify(player, times(1)).teleport(any(Location.class), any(TeleportCause.class));
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"WATER", "LAVA", "MAGMA_BLOCK", "CACTUS",
            "CAMPFIRE", "SOUL_CAMPFIRE", "POWDER_SNOW", "POINTED_DRIPSTONE"})
    void rejectsHazardousGroundAndStopsSearching(Material material) {
        when(floor.getType()).thenReturn(material);
        when(floor.isLiquid()).thenReturn(material == Material.WATER || material == Material.LAVA);
        run();
        load();
        verify(player, never()).teleport(any(Location.class), any(TeleportCause.class));
        verify(world, times(64)).getChunkAtAsync(anyInt(), anyInt(), eq(true));
        verify(player).sendMessage(contains("Could not find a safe wilderness location"));
    }

    @Test void rejectsBlockedHeadroomAndIncompleteFloor() {
        doReturn(block(Material.STONE, 66)).when(world).getBlockAt(100, 66, -200);
        assertNull(Wilderness.landing(world, 100, -200));
        doReturn(block(Material.AIR, 66)).when(world).getBlockAt(100, 66, -200);
        when(floor.getBoundingBox()).thenReturn(new BoundingBox(100, 64, -200, 101, 64.5, -199));
        assertNull(Wilderness.landing(world, 100, -200));
    }

    @Test void borderShrinkWhileLoadingPreventsTeleport() {
        run();
        when(border.getSize()).thenReturn(0.25);
        load();
        verify(player, never()).teleport(any(Location.class), any(TeleportCause.class));
    }

    @Test void claimsAreCheckedAgainAfterLoading() {
        run();
        when(store.claimAt(any(CivStore.Plot.class))).thenReturn(mock(CivStore.Village.class));
        load();
        verify(player, never()).teleport(any(Location.class), any(TeleportCause.class));
        verify(world, times(1)).getChunkAtAsync(anyInt(), anyInt(), eq(true));
    }

    @Test void changedWorldOrNewCombatTimeoutCancelsPendingTravel() {
        run();
        when(player.getWorld()).thenReturn(mock(World.class));
        load();
        verify(player, never()).teleport(any(Location.class), any(TeleportCause.class));
        when(player.getWorld()).thenReturn(world);
        chunk = new CompletableFuture<>();
        when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(chunk);
        run();
        when(combat.locked(player)).thenReturn(true);
        load();
        verify(player, never()).teleport(any(Location.class), any(TeleportCause.class));
    }

    @Test void disconnectAndPluginShutdownDiscardPendingTravel() {
        run();
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        wilderness.quit(quit);
        load();
        verify(player, never()).teleport(any(Location.class), any(TeleportCause.class));
        chunk = new CompletableFuture<>();
        when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(chunk);
        run();
        wilderness.close();
        load();
        verify(player, never()).teleport(any(Location.class), any(TeleportCause.class));
    }

    @Test void cancelledTeleportReportsFailure() {
        when(player.teleport(any(Location.class), eq(TeleportCause.COMMAND))).thenReturn(false);
        run();
        load();
        verify(player).sendMessage("Wilderness teleport was cancelled.");
        verify(player, never()).sendMessage("Teleported to the wilderness.");
    }

    @Test void netherSearchFindsGroundBelowTheRoof() {
        when(world.getEnvironment()).thenReturn(World.Environment.NETHER);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getLogicalHeight()).thenReturn(128);
        when(world.getHighestBlockYAt(100, -200, HeightMap.MOTION_BLOCKING)).thenReturn(127);
        Block air = block(Material.AIR, 0);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        doReturn(block(Material.NETHERRACK, 120)).when(world).getBlockAt(100, 120, -200);
        assertEquals(new Location(world, 100.5, 121, -199.5), Wilderness.landing(world, 100, -200));
        verify(world, never()).getBlockAt(100, 127, -200);
    }
}
