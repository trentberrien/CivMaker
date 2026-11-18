package dev.civmaker;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import dev.civmaker.CivStore.Position;
import dev.civmaker.RaidEquipment.Tool;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaidScaffoldingDropsTest {
    @TempDir Path directory;
    private CivStore store;
    private RaidScaffolding scaffolding;
    private RaidEquipment equipment;
    private World world;
    private Block block;
    private Player player;
    private final List<Runnable> callbacks = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        store.setRaidMode(CivStore.RaidMode.ON);
        equipment = mock(RaidEquipment.class);
        ItemStack taggedStack = mock(ItemStack.class);
        when(equipment.item(Tool.RAID_SCAFFOLDING, null)).thenReturn(taggedStack);
        when(taggedStack.asQuantity(anyInt())).thenAnswer(call -> stack(call.getArgument(0), true));
        CivMaker plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            callbacks.add(call.getArgument(1));
            return null;
        });
        scaffolding = new RaidScaffolding(plugin, store, equipment);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.SCAFFOLDING);
        when(block.getLocation()).thenAnswer(call -> new Location(world, 4, 80, 4));
        when(block.getX()).thenReturn(4);
        when(block.getY()).thenReturn(80);
        when(block.getZ()).thenReturn(4);
        store.recordRaidScaffolding(Position.at(block), store.activeWindow(System.currentTimeMillis()));
        player = mock(Player.class);
    }

    private ItemStack stack(int amount, boolean tagged) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.SCAFFOLDING);
        when(stack.getAmount()).thenReturn(amount);
        if (tagged) when(equipment.identify(stack)).thenReturn(Tool.RAID_SCAFFOLDING);
        return stack;
    }

    private Item dropped(int amount) {
        Item entity = mock(Item.class);
        ItemStack stack = stack(amount, false);
        when(entity.getItemStack()).thenReturn(stack);
        return entity;
    }

    private void finishEvents() {
        var tasks = List.copyOf(callbacks);
        callbacks.clear();
        tasks.forEach(Runnable::run);
    }

    private void assertTagged(Item entity, int amount) {
        var captured = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
        verify(entity).setItemStack(captured.capture());
        assertEquals(Tool.RAID_SCAFFOLDING, equipment.identify(captured.getValue()));
        assertEquals(amount, captured.getValue().getAmount());
    }

    @Test void breakingReturnsTaggedScaffoldingWithTheOriginalDropQuantity() {
        scaffolding.broken(new BlockBreakEvent(block, player));
        Item dropped = dropped(3);
        scaffolding.dropped(new BlockDropItemEvent(block, mock(BlockState.class), player, List.of(dropped)));
        assertTagged(dropped, 3);
        assertFalse(store.isRaidScaffolding(block));
        finishEvents();
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test void ordinaryScaffoldingAndSuppressedDropsDoNotProduceRaidItems() {
        store.forgetRaidScaffolding(Position.at(block));
        scaffolding.broken(new BlockBreakEvent(block, player));
        Item ordinary = dropped(1);
        scaffolding.dropped(new BlockDropItemEvent(block, mock(BlockState.class), player, List.of(ordinary)));
        verify(ordinary, never()).setItemStack(any());
        store.recordRaidScaffolding(Position.at(block), store.activeWindow(System.currentTimeMillis()));
        var broken = new BlockBreakEvent(block, player);
        broken.setDropItems(false);
        scaffolding.broken(broken);
        assertTrue(callbacks.isEmpty());
    }

    @Test void supportCollapseReplacesExactlyOneDrop() {
        BlockDestroyEvent event = mock(BlockDestroyEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.willDrop()).thenReturn(true);
        scaffolding.collapseDrop(event);
        scaffolding.destroyed(event);
        verify(event).setWillDrop(false);
        finishEvents();
        var captured = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
        verify(world).dropItemNaturally(any(Location.class), captured.capture());
        assertEquals(1, captured.getValue().getAmount());
        assertEquals(Tool.RAID_SCAFFOLDING, equipment.identify(captured.getValue()));
    }

    @Test void cancelledCollapseAndDisabledBlockDropsDoNotCreateItems() {
        BlockDestroyEvent event = mock(BlockDestroyEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.willDrop()).thenReturn(true);
        scaffolding.collapseDrop(event);
        when(event.isCancelled()).thenReturn(true);
        finishEvents();
        verify(world, never()).dropItemNaturally(any(), any());
        when(event.isCancelled()).thenReturn(false);
        when(world.getGameRuleValue("minecraft:block_drops")).thenReturn("false");
        scaffolding.collapseDrop(event);
        finishEvents();
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test void fallingDropsKeepTheirTagEvenWhenPaperRemovesTheEntityFirst() {
        FallingBlock falling = mock(FallingBlock.class);
        when(falling.getUniqueId()).thenReturn(UUID.randomUUID());
        store.startRaidScaffoldingFall(Position.at(block), falling.getUniqueId());
        scaffolding.removed(new EntityRemoveEvent(falling, EntityRemoveEvent.Cause.DROP));
        Item drop = dropped(1);
        scaffolding.fallingDrop(new EntityDropItemEvent(falling, drop));
        assertTagged(drop, 1);
        finishEvents();
        assertNull(store.fallingRaidScaffolding(falling.getUniqueId()));
    }

    @Test void ordinaryFallingDropsAreUnchanged() {
        FallingBlock falling = mock(FallingBlock.class);
        when(falling.getUniqueId()).thenReturn(UUID.randomUUID());
        Item drop = dropped(1);
        scaffolding.fallingDrop(new EntityDropItemEvent(falling, drop));
        verify(drop, never()).setItemStack(any());
    }

    private EntityExplodeEvent explosion(float yield) {
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(event.getYield()).thenReturn(yield);
        when(event.getExplosionResult()).thenReturn(ExplosionResult.DESTROY_WITH_DECAY);
        when(event.blockList()).thenReturn(new ArrayList<>(List.of(block)));
        return event;
    }

    @Test void explosionDropsAreTaggedAndDoNotMergeWithOrdinaryScaffolding() {
        var event = explosion(1);
        Block ordinary = mock(Block.class);
        when(ordinary.getType()).thenReturn(Material.SCAFFOLDING);
        when(ordinary.getWorld()).thenReturn(world);
        when(ordinary.getX()).thenReturn(8);
        event.blockList().add(ordinary);
        scaffolding.explosionDrops(event);
        assertEquals(List.of(ordinary), event.blockList());
        finishEvents();
        verify(block).setType(Material.AIR, true);
        verify(ordinary, never()).setType(any(), anyBoolean());
        var captured = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
        verify(world).dropItemNaturally(any(Location.class), captured.capture());
        assertEquals(Tool.RAID_SCAFFOLDING, equipment.identify(captured.getValue()));
        assertEquals(1, captured.getValue().getAmount());
    }

    @Test void cancelledExplosionsAndZeroYieldDoNotProduceItems() {
        var event = explosion(1);
        scaffolding.explosionDrops(event);
        when(event.isCancelled()).thenReturn(true);
        finishEvents();
        verify(block, never()).setType(any(), anyBoolean());
        scaffolding.explosionDrops(explosion(0));
        finishEvents();
        verify(block).setType(Material.AIR, true);
        verify(world, never()).dropItemNaturally(any(), any());
    }
}
