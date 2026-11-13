package dev.civmaker;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import dev.civmaker.CivStore.FarmStation;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.UUID;

final class FarmStations implements Listener {
    private static final long PLAYER_DAMAGE_TICKS = 100;

    private final CivStore store;
    private final FarmStationLoot loot;
    private final NamespacedKey positionKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey lastPlayerDamageKey;

    FarmStations(CivMaker plugin, CivStore store, FarmStationLoot loot) {
        this.store = store;
        this.loot = loot;
        positionKey = new NamespacedKey(plugin, "farm_station_position");
        ownerKey = new NamespacedKey(plugin, "farm_station_owner");
        lastPlayerDamageKey = new NamespacedKey(plugin, "farm_station_player_damage");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void validatePlacement(BlockPlaceEvent event) {
        if (!event.canBuild() || !loot.isStation(event.getItemInHand())) {
            return;
        }
        Block block = event.getBlockPlaced();
        Block station = activeStation(block.getChunk());
        if (station != null && !station.equals(block)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("This chunk already has a Farm Station.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void recordPlacement(BlockPlaceEvent event) {
        if (!event.canBuild() || event.getBlockPlaced().getType() != Material.LODESTONE) {
            return;
        }
        Block block = event.getBlockPlaced();
        Chunk chunk = block.getChunk();
        if (loot.isStation(event.getItemInHand())) {
            var data = chunk.getPersistentDataContainer();
            data.set(positionKey, PersistentDataType.INTEGER_ARRAY, new int[]{block.getX() & 15, block.getY(), block.getZ() & 15});
            Player player = event.getPlayer();
            data.set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            String name = event.getItemInHand().getPersistentDataContainer().get(loot.nameKey, PersistentDataType.STRING);
            if (name == null) {
                data.remove(loot.nameKey);
            } else {
                data.set(loot.nameKey, PersistentDataType.STRING, name);
            }
            store.indexFarmStation(new FarmStation(Position.at(block), player.getUniqueId(), player.getName(), name));
            player.sendMessage("Farm Station active. Mobs can now drop loot without a player hit in this chunk.");
        } else if (block.equals(recordedStation(chunk))) {
            clearStation(chunk);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void nameStation(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.useInteractedBlock() == Event.Result.DENY || event.useItemInHand() == Event.Result.DENY
                || block == null || event.getItem() == null || !isStation(block)) {
            return;
        }
        String name = FarmProduce.farmName(event.getItem().getType());
        if (name == null) {
            return;
        }
        Player player = event.getPlayer();
        var data = block.getChunk().getPersistentDataContainer();
        if (!player.getUniqueId().toString().equals(data.get(ownerKey, PersistentDataType.STRING))) {
            player.sendMessage("Only the owner can name this Farm Station.");
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        data.set(loot.nameKey, PersistentDataType.STRING, name);
        store.indexFarmStation(new FarmStation(Position.at(block), player.getUniqueId(), player.getName(), name));
        player.sendMessage("Farm Station named " + name + ".");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void load(ChunkLoadEvent event) {
        indexChunk(event.getChunk());
    }

    void indexChunk(Chunk chunk) {
        Block block = activeStation(chunk);
        Plot plot = new Plot(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        String ownerId = block == null ? null : chunk.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerId == null) {
            store.removeFarmStation(plot);
            return;
        }
        UUID owner = UUID.fromString(ownerId);
        Position position = Position.at(block);
        String name = chunk.getPersistentDataContainer().get(loot.nameKey, PersistentDataType.STRING);
        FarmStation indexed = store.farmStationAt(plot);
        boolean sameOwner = indexed != null && indexed.owner().equals(owner);
        if (!sameOwner || !indexed.position().equals(position) || !Objects.equals(indexed.name(), name)) {
            String ownerName = sameOwner ? indexed.ownerName() : Bukkit.getOfflinePlayer(owner).getName();
            store.indexFarmStation(new FarmStation(position, owner, ownerName, name));
        }
    }

    static Component ownerLabel(CivStore store, FarmStation station) {
        var faction = store.registeredVillage(station.owner());
        return faction == null
                ? Component.text(station.ownerName() == null ? "Unaffiliated" : station.ownerName(), NamedTextColor.GRAY)
                : Component.text(faction.name, TextColor.color(faction.color));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void protectStation(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isStation(block)) {
            return;
        }
        Player player = event.getPlayer();
        if (!canMine(player, block)) {
            event.setCancelled(true);
            player.sendMessage("Only the station owner or their faction can mine this Farm Station outside an eligible raid.");
        }
    }

    private boolean canMine(Player player, Block block) {
        UUID miner = player.getUniqueId();
        String owner = block.getChunk().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (miner.toString().equals(owner)) {
            return true;
        }
        var faction = owner == null ? null : store.registeredVillage(UUID.fromString(owner));
        if (faction != null && store.registeredVillage(miner) == faction) {
            return true;
        }
        var window = store.activeWindow(System.currentTimeMillis());
        return window != null && !store.raidRestricted(miner) && store.raidCovers(Plot.at(block))
                && !store.shielded(faction, window);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void breakWithoutDrops(BlockBreakEvent event) {
        if ((!event.isDropItems() || event.getPlayer().getGameMode() == GameMode.CREATIVE) && isStation(event.getBlock())) {
            clearStation(event.getBlock().getChunk());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void recoverStation(BlockDropItemEvent event) {
        Block block = event.getBlock();
        if (event.getBlockState().getType() != Material.LODESTONE || !block.equals(recordedStation(block.getChunk()))) {
            return;
        }
        String name = block.getChunk().getPersistentDataContainer().get(loot.nameKey, PersistentDataType.STRING);
        clearStation(block.getChunk());
        for (var drop : event.getItems()) {
            if (drop.getItemStack().getType() == Material.LODESTONE) {
                drop.setItemStack(loot.createStation(name));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void explode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isStation);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void explode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isStation);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void extend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isStation)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void retract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isStation)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void destroy(BlockDestroyEvent event) {
        if (isStation(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void changeBlock(EntityChangeBlockEvent event) {
        if (isStation(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void recordPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Mob mob && event.getDamageSource().getCausingEntity() instanceof Player
                && event.getFinalDamage() > 0) {
            mob.getPersistentDataContainer().set(lastPlayerDamageKey, PersistentDataType.LONG, mob.getWorld().getGameTime());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void filterDrops(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        var lastDamage = mob.getLastDamageCause();
        if (lastDamage != null && lastDamage.getCause() == DamageCause.ENTITY_SWEEP_ATTACK
                && event.getDamageSource().getCausingEntity() instanceof Player attacker
                && lastDamage.getDamageSource().equals(event.getDamageSource()) && canAttackPlayers(mob)) {
            if (!allowsSweepRewards(mob.getChunk(), attacker.getUniqueId())) {
                event.getDrops().clear();
                event.setDroppedExp(0);
            }
            return;
        }
        if (event.getDrops().isEmpty()) {
            return;
        }
        Long hit = mob.getPersistentDataContainer().get(lastPlayerDamageKey, PersistentDataType.LONG);
        long now = mob.getWorld().getGameTime();
        if ((hit == null || hit > now || now - hit > PLAYER_DAMAGE_TICKS) && !allowsAutomation(mob.getChunk())) {
            event.getDrops().clear();
        }
    }

    private boolean allowsSweepRewards(Chunk chunk, UUID attacker) {
        if (!allowsAutomation(chunk)) {
            return false;
        }
        String ownerId = chunk.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerId == null) {
            return false;
        }
        UUID owner = UUID.fromString(ownerId);
        var faction = store.registeredVillage(owner);
        return faction == null ? owner.equals(attacker) : store.registeredVillage(attacker) == faction;
    }

    private static boolean canAttackPlayers(Mob mob) {
        if (mob instanceof Enemy) {
            return true;
        }
        return switch (mob.getType()) {
            case BEE, DOLPHIN, FOX, GOAT, IRON_GOLEM, LLAMA, NAUTILUS, PANDA, POLAR_BEAR,
                    TRADER_LLAMA, WOLF, ZOMBIE_NAUTILUS -> true;
            case RABBIT -> mob instanceof Rabbit rabbit && rabbit.getRabbitType() == Rabbit.Type.THE_KILLER_BUNNY;
            default -> false;
        };
    }

    boolean isStation(Block block) {
        return block.getType() == Material.LODESTONE && block.equals(recordedStation(block.getChunk()));
    }

    private Block activeStation(Chunk chunk) {
        Block block = recordedStation(chunk);
        return block != null && block.getType() == Material.LODESTONE ? block : null;
    }

    boolean allowsAutomation(Chunk chunk) {
        return activeStation(chunk) != null;
    }

    private Block recordedStation(Chunk chunk) {
        int[] position = chunk.getPersistentDataContainer().get(positionKey, PersistentDataType.INTEGER_ARRAY);
        return position == null ? null : chunk.getBlock(position[0], position[1], position[2]);
    }

    private void clearStation(Chunk chunk) {
        var data = chunk.getPersistentDataContainer();
        data.remove(positionKey);
        data.remove(ownerKey);
        data.remove(loot.nameKey);
        store.removeFarmStation(new Plot(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }
}
