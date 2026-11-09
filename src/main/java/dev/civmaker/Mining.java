package dev.civmaker;

import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

final class Mining implements Listener {
    private static final EnumSet<Material> ORES = EnumSet.of(Material.ANCIENT_DEBRIS);

    static {
        for (Material material : Material.values()) {
            if (material.name().endsWith("_ORE") && !material.isLegacy()) {
                ORES.add(material);
            }
        }
    }
    private final Wallets wallets;
    private final NamespacedKey naturalOres;
    private final Map<Material, Long> payouts = new EnumMap<>(Material.class);

    Mining(CivMaker plugin, Wallets wallets) {
        this.wallets = wallets;
        naturalOres = new NamespacedKey(plugin, "natural_ores_v1");
        var section = plugin.getConfig().getConfigurationSection("mining-payouts");
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            Material material = Material.getMaterial(name);
            if (material == null || !isOre(material)) {
                throw new IllegalArgumentException("Unknown mining ore: " + name);
            }
            long value = section.getLong(name);
            if (value > 0) {
                payouts.put(material, value);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    void recordGeneration(ChunkLoadEvent event) {
        if (!event.isNewChunk()) {
            return;
        }
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL
                && world.getEnvironment() != World.Environment.NETHER) {
            return;
        }
        var snapshot = chunk.getChunkSnapshot(false, false, false);
        int minY = world.getMinHeight();
        BitSet ores = new BitSet();
        for (int y = minY; y < world.getMaxHeight(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (isOre(snapshot.getBlockType(x, y, z))) {
                        ores.set(index(x, y - minY, z));
                    }
                }
            }
        }
        if (!ores.isEmpty()) {
            chunk.getPersistentDataContainer().set(naturalOres, PersistentDataType.BYTE_ARRAY, ores.toByteArray());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void payForBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isOre(block.getType()) || !forget(block)) {
            return;
        }
        long payout = payouts.getOrDefault(block.getType(), 0L);
        var player = event.getPlayer();
        if (payout == 0 || player.getGameMode() != GameMode.SURVIVAL || !block.isPreferredTool(player.getInventory().getItemInMainHand())) {
            return;
        }
        long balance = wallets.balance(player);
        if (balance <= Long.MAX_VALUE - payout) {
            wallets.setBalance(player, balance + payout);
            player.sendActionBar(Component.text("+$" + payout + " from mining"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void placed(BlockPlaceEvent event) {
        forgetOre(event.getBlockPlaced());
        if (event instanceof BlockMultiPlaceEvent multi) {
            multi.getReplacedBlockStates().forEach(state -> forget(state.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void blockExplosion(BlockExplodeEvent event) {
        event.blockList().forEach(this::forgetOre);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void entityExplosion(EntityExplodeEvent event) {
        event.blockList().forEach(this::forgetOre);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void entityChange(EntityChangeBlockEvent event) {
        if (isOre(event.getTo()) || isOre(event.getBlock().getType())) {
            forget(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void extend(BlockPistonExtendEvent event) {
        moved(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void retract(BlockPistonRetractEvent event) {
        if (event.getBlock().getBlockData() instanceof Directional piston) {
            moved(event.getBlocks(), piston.getFacing().getOppositeFace());
        }
    }

    private void moved(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            if (isOre(block.getType())) {
                forget(block);
                forget(block.getRelative(direction));
            }
        }
    }

    private void forgetOre(Block block) {
        if (isOre(block.getType())) {
            forget(block);
        }
    }

    private boolean forget(Block block) {
        var data = block.getChunk().getPersistentDataContainer();
        byte[] bytes = data.get(naturalOres, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) {
            return false;
        }
        int index = index(block.getX() & 15, block.getY() - block.getWorld().getMinHeight(), block.getZ() & 15);
        BitSet ores = BitSet.valueOf(bytes);
        if (!ores.get(index)) {
            return false;
        }
        ores.clear(index);
        if (ores.isEmpty()) {
            data.remove(naturalOres);
        } else {
            data.set(naturalOres, PersistentDataType.BYTE_ARRAY, ores.toByteArray());
        }
        return true;
    }

    private static int index(int x, int y, int z) {
        return (y * 16 + z) * 16 + x;
    }

    private static boolean isOre(Material material) {
        return ORES.contains(material);
    }
}
