package dev.civmaker;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

class MiningTest {
    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE"})
    void lightingNaturalRedstonePreservesOneMiningPayout(Material ore) {
        Site site = site(ore);
        site.mining().entityChange(lighting(site, ore));
        site.mining().payForBreak(new BlockBreakEvent(site.block(), site.player()));
        site.mining().payForBreak(new BlockBreakEvent(site.block(), site.player()));
        verify(site.wallets(), times(1)).setBalance(site.player(), 140L);
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE"})
    void lightingPlacedRedstoneDoesNotRestoreEligibility(Material ore) {
        Site site = site(ore);
        BlockPlaceEvent placement = mock(BlockPlaceEvent.class);
        when(placement.getBlockPlaced()).thenReturn(site.block());
        site.mining().placed(placement);
        site.mining().entityChange(lighting(site, ore));
        site.mining().payForBreak(new BlockBreakEvent(site.block(), site.player()));
        verifyNoInteractions(site.wallets());
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE"})
    void entityReplacingRedstoneStillRemovesEligibility(Material ore) {
        Site site = site(ore);
        EntityChangeBlockEvent change = mock(EntityChangeBlockEvent.class);
        when(change.getBlock()).thenReturn(site.block());
        when(change.getTo()).thenReturn(Material.AIR);
        site.mining().entityChange(change);
        site.mining().payForBreak(new BlockBreakEvent(site.block(), site.player()));
        verifyNoInteractions(site.wallets());
    }

    private EntityChangeBlockEvent lighting(Site site, Material ore) {
        Lightable lit = mock(Lightable.class);
        when(lit.getMaterial()).thenReturn(ore);
        when(lit.isLit()).thenReturn(true);
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(site.player(), site.block(), lit);
        return event;
    }

    private Site site(Material ore) {
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        YamlConfiguration config = new YamlConfiguration();
        config.set("mining-payouts." + ore.name(), 40L);
        when(plugin.getConfig()).thenReturn(config);
        Wallets wallets = mock(Wallets.class);
        Mining mining = new Mining(plugin, wallets);

        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(-63);
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        when(chunk.getChunkSnapshot(false, false, false)).thenReturn(snapshot);
        when(snapshot.getBlockType(anyInt(), anyInt(), anyInt())).thenReturn(Material.STONE);
        when(snapshot.getBlockType(1, -64, 2)).thenReturn(ore);

        Map<NamespacedKey, byte[]> values = new HashMap<>();
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(chunk.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.BYTE_ARRAY)))
                .thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(data).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE_ARRAY), any(byte[].class));
        doAnswer(call -> values.remove(call.getArgument(0))).when(data).remove(any(NamespacedKey.class));
        mining.recordGeneration(new ChunkLoadEvent(chunk, true));

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(ore);
        when(block.getWorld()).thenReturn(world);
        when(block.getChunk()).thenReturn(chunk);
        when(block.getX()).thenReturn(1);
        when(block.getY()).thenReturn(-64);
        when(block.getZ()).thenReturn(2);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack pickaxe = mock(ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(pickaxe);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(block.isPreferredTool(pickaxe)).thenReturn(true);
        when(wallets.balance(player)).thenReturn(100L);
        return new Site(mining, wallets, block, player);
    }

    private record Site(Mining mining, Wallets wallets, Block block, Player player) {}
}
