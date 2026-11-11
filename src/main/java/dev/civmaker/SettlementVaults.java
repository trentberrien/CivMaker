package dev.civmaker;

import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Settlement;
import dev.civmaker.CivStore.Village;
import dev.civmaker.SettlementResources.Reward;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

final class SettlementVaults implements Listener {
    static final int SLOTS = 5;
    private static final long DAY_TICKS = 24_000;
    private static final long TEST_DELAY_TICKS = 1_200;
    private static final List<BlockFace> SIDES = List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

    private static final class Menu implements InventoryHolder {
        final Position position;
        final Inventory inventory = Bukkit.createInventory(this, InventoryType.HOPPER, Component.text("Settlement Vault"));

        Menu(Position position, Inventory source) {
            this.position = position;
            refresh(source);
        }

        void refresh(Inventory source) {
            for (int slot = 0; slot < SLOTS; slot++) {
                inventory.setItem(slot, source.getItem(slot));
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final CivMaker plugin;
    private final CivStore store;
    private final SettlementResources resources;
    private final NamespacedKey kitKey;
    private final NamespacedKey depositKey;
    private final NamespacedKey legacyDepositKey;

    SettlementVaults(CivMaker plugin, CivStore store, SettlementResources resources) {
        this.plugin = plugin;
        this.store = store;
        this.resources = resources;
        kitKey = new NamespacedKey(plugin, "settlement_vault_kit");
        depositKey = new NamespacedKey(plugin, "settlement_vault_last_deposit_tick");
        legacyDepositKey = new NamespacedKey(plugin, "settlement_vault_last_deposit");
    }

    boolean hasPlacedVault(Settlement settlement) {
        Position position = settlement.vault().position;
        if (position == null) {
            return false;
        }
        Block existing = loadedBlock(position);
        return existing == null || stampedChest(existing) != null;
    }

    static boolean canPlaceAt(Block block, Settlement settlement) {
        Position position = Position.at(block);
        World world = block.getWorld();
        if (!world.isChunkLoaded(position.plot().x(), position.plot().z())
                || !settlement.plots().contains(position.plot()) || !withinRadius(position, settlement.bell())
                || !block.getType().isAir() || !block.getRelative(BlockFace.UP).getType().isAir()) {
            return false;
        }
        for (BlockFace side : SIDES) {
            Block neighbor = block.getRelative(side);
            Plot plot = Plot.at(neighbor);
            if (!world.isChunkLoaded(plot.x(), plot.z()) || neighbor.getType() == Material.CHEST) {
                return false;
            }
        }
        return true;
    }

    boolean placeAt(Block block, Block bell, Settlement settlement) {
        if (!canPlaceAt(block, settlement)) {
            return false;
        }
        Reward reward = Reward.at(settlement.structure(), bell);
        Material previous = block.getType();
        boolean placed = false;
        try {
            block.setType(Material.CHEST, false);
            placed = block.getState() instanceof Chest chest && register(chest, settlement, reward);
            return placed;
        } finally {
            if (!placed) {
                block.setType(previous, false);
            }
        }
    }

    private static String binding(Village village, Settlement settlement) {
        Position bell = settlement.bell();
        return village.id + "/" + bell.world() + "/" + bell.x() + "/" + bell.y() + "/" + bell.z();
    }

    private String kitBinding(ItemStack item) {
        return item == null || item.getType() != Material.CHEST ? null
                : item.getPersistentDataContainer().get(kitKey, PersistentDataType.STRING);
    }

    static boolean withinRadius(Position position, Position bell) {
        long dx = (long) position.x() - bell.x();
        long dy = (long) position.y() - bell.y();
        long dz = (long) position.z() - bell.z();
        return position.world().equals(bell.world()) && Math.abs(dx) <= 8 && Math.abs(dy) <= 8 && Math.abs(dz) <= 8
                && dx * dx + dy * dy + dz * dz <= 64;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void place(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (resources.isCurrency(event.getItemInHand())) {
            event.setCancelled(true);
            return;
        }
        if (block.getType() != Material.CHEST) {
            return;
        }
        String kit = kitBinding(event.getItemInHand());
        if (kit == null) {
            if (SIDES.stream().map(block::getRelative).anyMatch(side -> store.settlementVaultAt(side) != null)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("Leave a block of space between chests and the Settlement Vault.");
            }
            return;
        }
        Plot plot = Plot.at(block);
        Settlement settlement = store.settlementAt(plot);
        Village village = store.claimAt(plot);
        if (settlement == null || village == null || !kit.equals(binding(village, settlement))
                || store.memberOf(event.getPlayer().getUniqueId()) != village || settlement.vault().position != null
                || !withinRadius(Position.at(block), settlement.bell())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Place this vault inside its own settlement within 8 blocks of the bell. Each settlement has one vault.");
        } else if (SIDES.stream().map(block::getRelative).anyMatch(side -> side.getType() == Material.CHEST)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Leave a block of space between the Settlement Vault and other chests.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void recordPlacement(BlockPlaceEvent event) {
        if (!event.canBuild() || kitBinding(event.getItemInHand()) == null
                || !(event.getBlockPlaced().getState() instanceof Chest chest)) {
            return;
        }
        Block block = event.getBlockPlaced();
        Settlement settlement = store.settlementAt(Plot.at(block));
        Position bell = settlement.bell();
        register(chest, settlement, Reward.at(settlement.structure(), block.getWorld().getBlockAt(bell.x(), bell.y(), bell.z())));
    }

    private boolean register(Chest chest, Settlement settlement, Reward reward) {
        Block block = chest.getBlock();
        chest.getPersistentDataContainer().set(depositKey, PersistentDataType.LONG, block.getWorld().getFullTime());
        if (!chest.update(false, false)) {
            return false;
        }
        settlement.vault().position = Position.at(block);
        settlement.vault().reward = reward;
        store.dirty = true;
        return true;
    }

    private Chest stampedChest(Block block) {
        if (block.getType() != Material.CHEST || !(block.getState(false) instanceof Chest chest)) {
            return null;
        }
        var data = chest.getPersistentDataContainer();
        return data.has(depositKey, PersistentDataType.LONG) || data.has(legacyDepositKey, PersistentDataType.LONG)
                ? chest : null;
    }

    private Block loadedBlock(Position position) {
        World world = plugin.getServer().getWorld(position.world());
        Plot plot = position.plot();
        return world == null || !world.isChunkLoaded(plot.x(), plot.z()) ? null
                : world.getBlockAt(position.x(), position.y(), position.z());
    }

    void testDeposit(Player player) {
        Block block = player.getTargetBlockExact(5);
        Settlement settlement = block == null ? null : store.settlementVaultAt(block);
        if (settlement == null || settlement.vault().reward == null || stampedChest(block) == null) {
            player.sendMessage("Look at a placed Settlement Vault within five blocks.");
            return;
        }
        deposit(block, settlement);
        Chest chest = (Chest) block.getState();
        Inventory inventory = chest.getSnapshotInventory();
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) {
                long last = block.getWorld().getFullTime() - DAY_TICKS + TEST_DELAY_TICKS;
                chest.getPersistentDataContainer().set(depositKey, PersistentDataType.LONG, last);
                if (chest.update(false, false)) {
                    player.sendMessage("Test deposit due in 1,200 world ticks (about one minute at normal speed).");
                } else {
                    player.sendMessage("Could not schedule the test deposit. Look at the vault and try again.");
                }
                return;
            }
        }
        player.sendMessage("This vault is full. Empty a slot before scheduling a test deposit.");
    }

    void deposit(Block block, Settlement settlement) {
        Chest live = stampedChest(block);
        if (live == null || settlement.vault().reward == null) {
            return;
        }
        long now = block.getWorld().getFullTime();
        Long last = live.getPersistentDataContainer().get(depositKey, PersistentDataType.LONG);
        long elapsed = last == null ? 0 : Math.max(0, (now - last) / DAY_TICKS);
        if (last != null && elapsed == 0) {
            return;
        }
        Chest chest = (Chest) block.getState();
        long days = elapsed;
        Inventory inventory = chest.getSnapshotInventory();
        for (int slot = 0; slot < SLOTS && days > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) {
                inventory.setItem(slot, resources.stack(settlement.vault().reward));
                days--;
            }
        }
        // Start old wall-clock vaults on the current world tick and preserve their inventory.
        var data = chest.getPersistentDataContainer();
        data.remove(legacyDepositKey);
        // Advance even when full. The deposit time and items are saved together on the chest.
        data.set(depositKey, PersistentDataType.LONG, last == null ? now : last + elapsed * DAY_TICKS);
        chest.update(false, false);
    }

    void refresh() {
        for (Village village : store.villages()) {
            for (Settlement settlement : village.settlements) {
                Position position = settlement.vault().position;
                Block block = position == null ? null : loadedBlock(position);
                if (block != null) {
                    deposit(block, settlement);
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof Menu menu) {
                Inventory source = available(player, menu.position);
                if (source == null) {
                    player.closeInventory();
                } else {
                    menu.refresh(source);
                }
            }
        }
    }

    private Inventory available(Player player, Position position) {
        Block block = loadedBlock(position);
        Village village = store.claimAt(position.plot());
        if (block == null || village == null || village != store.memberOf(player.getUniqueId())
                || player.getGameMode() == GameMode.SPECTATOR || !player.getWorld().equals(block.getWorld())
                || player.getLocation().distanceSquared(block.getLocation().toCenterLocation()) > 36) {
            return null;
        }
        Settlement settlement = store.settlementVaultAt(block);
        if (settlement == null) {
            return null;
        }
        deposit(block, settlement);
        Chest chest = stampedChest(block);
        return chest == null ? null : chest.getBlockInventory();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void open(InventoryOpenEvent event) {
        for (Block block : Protection.inventoryBlocks(event.getInventory())) {
            if (store.settlementVaultAt(block) == null) {
                continue;
            }
            event.setCancelled(true);
            Position position = Position.at(block);
            if (event.getPlayer() instanceof Player player) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Inventory source = available(player, position);
                    if (source != null && player.isOnline()) {
                        player.openInventory(new Menu(position, source).getInventory());
                    }
                });
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (!(event.getWhoClicked() instanceof Player player) || slot < 0 || slot >= SLOTS
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.SHIFT_LEFT)
                || (event.getCursor() != null && !event.getCursor().isEmpty())) {
            return;
        }
        Inventory source = withdraw(player, menu.position, slot, event.getCurrentItem());
        if (source != null) {
            menu.refresh(source);
        }
    }

    Inventory withdraw(Player player, Position position, int slot, ItemStack expected) {
        Inventory source = available(player, position);
        ItemStack item = source == null ? null : source.getItem(slot);
        if (item == null || !item.equals(expected)) {
            return null;
        }
        Village village = store.claimAt(position.plot());
        if (resources.isCurrency(item) && (village == null
                || store.registeredVillage(player.getUniqueId()) != village || !village.canManage(player.getUniqueId()))) {
            player.sendMessage(MoneyStorage.WITHDRAW_MESSAGE);
            return null;
        }
        var inventory = player.getInventory();
        var contents = inventory.getStorageContents().clone();
        if (ItemTransfers.insert(contents, item, inventory.getMaxStackSize()) > 0) {
            player.sendMessage("Free up inventory space to withdraw this stack.");
            return null;
        }
        source.setItem(slot, null);
        inventory.setStorageContents(contents);
        return source;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void dispense(BlockDispenseEvent event) {
        if (event.getBlock().getType() == Material.DISPENSER
                && (resources.isCurrency(event.getItem()) || kitBinding(event.getItem()) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void craft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (kitBinding(item) != null) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void crafter(CrafterCraftEvent event) {
        if (event.getBlock().getState(false) instanceof org.bukkit.block.Crafter crafter) {
            for (ItemStack item : crafter.getInventory().getContents()) {
                if (kitBinding(item) != null) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof Menu) {
                player.closeInventory();
            }
        }
    }
}
