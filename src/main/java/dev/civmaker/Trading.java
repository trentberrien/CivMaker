package dev.civmaker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class Trading implements Listener, TabExecutor {
    private static final String TEST_PERMISSION = "civmaker.trade.test";
    private static final int OFFER_COLUMNS = 4;
    private static final int OFFER_SLOTS = OFFER_COLUMNS * 3;
    private static final int CONFIRM_SLOT = 22;
    private static final long REQUEST_MILLIS = 60_000;

    private record Request(UUID recipient, long expires) {
        boolean isFor(Player player, long now) {
            return expires > now && recipient.equals(player.getUniqueId());
        }
    }

    private static final class Side {
        final Player player;
        ItemStack[] offer = new ItemStack[OFFER_SLOTS];
        Menu menu;
        boolean confirmed;

        Side(Player player) {
            this.player = player;
            if (player == null) {
                offer[0] = new ItemStack(Material.DIRT, 10);
                confirmed = true;
            }
        }

        String name() {
            return player == null ? "Test Trader" : player.getName();
        }
    }

    private static final class Trade {
        final Side first;
        final Side second;
        boolean completionQueued;

        Trade(Player initiator, Player partner) {
            first = new Side(initiator);
            second = new Side(partner);
            first.menu = new Menu(this, first, second);
            if (partner != null) {
                second.menu = new Menu(this, second, first);
            }
        }

        void refresh() {
            first.menu.refresh();
            if (second.menu != null) {
                second.menu.refresh();
            }
        }

        void resetConfirmations() {
            first.confirmed = false;
            second.confirmed = second.player == null;
            refresh();
        }
    }

    private static final class Menu implements InventoryHolder {
        final Trade trade;
        final Side own;
        final Side other;
        private final Inventory inventory;

        Menu(Trade trade, Side own, Side other) {
            this.trade = trade;
            this.own = own;
            this.other = other;
            inventory = Bukkit.createInventory(this, 45, Component.text("Trade with " + other.name()));
            ItemStack divider = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (slot < 9 || slot >= 36 || slot % 9 == 4) {
                    inventory.setItem(slot, divider);
                }
            }
            inventory.setItem(1, icon(Material.CHEST, "Your offer"));
            inventory.setItem(7, icon(Material.CHEST, other.name() + "'s offer"));
            inventory.setItem(4, icon(Material.BOOK, "Click items to offer or take them back",
                    "Left-click for the stack. Right-click for one item.",
                    "Both sides must confirm again after any change."));
        }

        void refresh() {
            for (int index = 0; index < OFFER_SLOTS; index++) {
                int left = (index / OFFER_COLUMNS + 1) * 9 + index % OFFER_COLUMNS;
                inventory.setItem(left, display(own.offer[index]));
                inventory.setItem(left + 5, display(other.offer[index]));
            }
            inventory.setItem(CONFIRM_SLOT, own.confirmed
                    ? icon(Material.LIME_STAINED_GLASS_PANE, "Confirmed", "Click to withdraw confirmation.")
                    : icon(Material.RED_STAINED_GLASS_PANE, "Confirm trade", "Review both offers before confirming."));
            inventory.setItem(31, other.confirmed
                    ? icon(Material.LIME_DYE, other.name() + " has confirmed")
                    : icon(Material.GRAY_DYE, "Waiting for " + other.name()));
        }

        private static ItemStack display(ItemStack stack) {
            return stack == null ? null : stack.clone();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final CivMaker plugin;
    private final NamespacedKey escrowKey;
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<UUID, Trade> active = new HashMap<>();

    Trading(CivMaker plugin) {
        this.plugin = plugin;
        escrowKey = new NamespacedKey(plugin, "trade_escrow_v1");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !canTrade(player)) {
            sender.sendMessage("You must be in Survival to trade.");
        } else if (args.length != 1) {
            return false;
        } else if (active.containsKey(player.getUniqueId())) {
            player.sendMessage("Finish or close your current trade first.");
        } else if (args[0].equalsIgnoreCase("--recover")) {
            if (escrow(player).length == 0) {
                player.sendMessage("You have no trade items to recover.");
            } else if (refund(player)) {
                player.sendMessage("Your trade items were returned.");
            }
        } else if (args[0].equalsIgnoreCase("test")) {
            if (player.hasPermission(TEST_PERMISSION)) {
                start(player, null);
            } else {
                player.sendMessage("You need permission to use the test trade.");
            }
        } else {
            request(player, plugin.getServer().getPlayerExact(args[0]));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !(sender instanceof Player player)) {
            return List.of();
        }
        var choices = new ArrayList<String>();
        for (Player candidate : plugin.getServer().getOnlinePlayers()) {
            if (candidate != player && player.canSee(candidate)) {
                choices.add(candidate.getName());
            }
        }
        if (player.hasPermission(TEST_PERMISSION)) {
            choices.add("test");
        }
        choices.add("--recover");
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void request(Player player, Player partner) {
        if (partner == null || !player.canSee(partner)) {
            player.sendMessage("That player is offline.");
            return;
        }
        if (player.equals(partner) || !canTrade(partner) || active.containsKey(partner.getUniqueId())) {
            player.sendMessage("Choose another available player in Survival.");
            return;
        }
        long now = System.currentTimeMillis();
        Request incoming = requests.get(partner.getUniqueId());
        if (incoming != null && incoming.isFor(player, now)) {
            start(partner, player);
            return;
        }
        Request outgoing = requests.get(player.getUniqueId());
        if (outgoing != null && outgoing.isFor(partner, now)) {
            player.sendMessage("Your trade request to " + partner.getName() + " is still pending.");
            return;
        }
        requests.put(player.getUniqueId(), new Request(partner.getUniqueId(), now + REQUEST_MILLIS));
        player.sendMessage("Trade request sent to " + partner.getName() + ".");
        String accept = "/trade " + player.getName();
        partner.sendMessage(Component.text(player.getName() + " wants to trade. Run " + accept + " to accept.")
                .clickEvent(ClickEvent.runCommand(accept)));
    }

    private void start(Player first, Player second) {
        if (!refund(first)) {
            if (second != null) {
                second.sendMessage(first.getName() + " needs room to recover items from a previous trade.");
            }
            return;
        }
        if (second != null && !refund(second)) {
            first.sendMessage(second.getName() + " needs room to recover items from a previous trade.");
            return;
        }
        Trade trade = new Trade(first, second);
        register(trade, first);
        if (second != null) {
            register(trade, second);
        }
        trade.refresh();
        if (first.openInventory(trade.first.menu.getInventory()) == null || !viewing(trade.first)
                || (second != null && (second.openInventory(trade.second.menu.getInventory()) == null
                || !viewing(trade.second)))) {
            cancel(trade);
        }
    }

    private void register(Trade trade, Player player) {
        clearRequests(player);
        active.put(player.getUniqueId(), trade);
    }

    private void clearRequests(Player player) {
        UUID id = player.getUniqueId();
        requests.remove(id);
        requests.values().removeIf(request -> request.recipient().equals(id));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        Player player = menu.own.player;
        ClickType click = event.getClick();
        if (cancelled || event.getWhoClicked() != player || !viewing(menu.own) || !empty(event.getCursor())
                || (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT)) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw == CONFIRM_SLOT && click == ClickType.LEFT) {
            menu.own.confirmed = !menu.own.confirmed;
            menu.trade.refresh();
            queueCompletion(menu.trade);
        } else if (raw >= menu.getInventory().getSize() && raw < event.getView().countSlots()) {
            moveOffer(menu, event.getSlot(), -1, click == ClickType.RIGHT);
        } else if (raw >= 9 && raw < 36 && raw % 9 < OFFER_COLUMNS) {
            int index = (raw / 9 - 1) * OFFER_COLUMNS + raw % 9;
            moveOffer(menu, -1, index, click == ClickType.RIGHT);
        }
    }

    private void moveOffer(Menu menu, int inventorySlot, int offerSlot, boolean singleItem) {
        Side side = menu.own;
        var inventory = side.player.getInventory();
        ItemStack[] storage = inventory.getStorageContents().clone();
        ItemStack[] offer = side.offer.clone();
        boolean adding = offerSlot < 0;
        if (adding && (inventorySlot < 0 || inventorySlot >= storage.length)) {
            return;
        }
        ItemStack[] source = adding ? storage : offer;
        int sourceSlot = adding ? inventorySlot : offerSlot;
        ItemStack item = source[sourceSlot];
        if (empty(item)) {
            return;
        }
        int amount = singleItem ? 1 : item.getAmount();
        int moved = amount - ItemTransfers.insert(adding ? offer : storage, item.asQuantity(amount),
                adding ? menu.getInventory().getMaxStackSize() : inventory.getMaxStackSize());
        if (moved == 0) {
            side.player.sendMessage(adding ? "Your offer is full." : "Your inventory is full.");
            return;
        }
        source[sourceSlot] = remaining(item, item.getAmount() - moved);
        // Escrow is saved with the inventory in the owner's player data. The menus hold display copies.
        saveEscrow(side.player, offer);
        inventory.setStorageContents(storage);
        side.offer = offer;
        menu.trade.resetConfirmations();
        side.player.updateInventory();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    private void queueCompletion(Trade trade) {
        if (trade.first.confirmed && trade.second.confirmed && !trade.completionQueued) {
            trade.completionQueued = true;
            plugin.getServer().getScheduler().runTask(plugin, () -> complete(trade));
        }
    }

    private void complete(Trade trade) {
        trade.completionQueued = false;
        if (!isActive(trade) || !trade.first.confirmed || !trade.second.confirmed) {
            return;
        }
        if (!viewing(trade.first) || (trade.second.player != null && !viewing(trade.second))) {
            cancel(trade);
            return;
        }

        ItemStack[] firstItems = planDelivery(trade.first.player, trade.second.offer);
        Player second = trade.second.player;
        ItemStack[] secondItems = second == null ? null : planDelivery(second, trade.first.offer);
        if (firstItems == null || (second != null && secondItems == null)) {
            notifyBoth(trade, "Trade paused. Both players need enough room for the items they will receive.");
            trade.resetConfirmations();
            return;
        }

        detach(trade);
        deliver(trade.first.player, firstItems);
        if (second != null) {
            deliver(second, secondItems);
        }
        notifyBoth(trade, "Trade complete.");
        closeWindows(trade);
    }

    private static ItemStack[] planDelivery(Player player, ItemStack[] offer) {
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents().clone();
        for (ItemStack item : offer) {
            if (!empty(item) && ItemTransfers.insert(contents, item, inventory.getMaxStackSize()) > 0) {
                return null;
            }
        }
        return contents;
    }

    private void deliver(Player player, ItemStack[] contents) {
        player.getInventory().setStorageContents(contents);
        player.getPersistentDataContainer().remove(escrowKey);
    }

    @EventHandler
    void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu && event.getPlayer() == menu.own.player) {
            cancel(menu.trade);
        }
    }

    @EventHandler
    void quit(PlayerQuitEvent event) {
        clearRequests(event.getPlayer());
        cancel(active.get(event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void changeMode(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.SURVIVAL) {
            clearRequests(event.getPlayer());
            cancel(active.get(event.getPlayer().getUniqueId()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void death(PlayerDeathEvent event) {
        Player player = event.getEntity();
        clearRequests(player);
        Trade trade = active.get(player.getUniqueId());
        if (trade != null && !event.getKeepInventory()) {
            // Treat offered items like the dying player's regular inventory.
            event.getDrops().addAll(Arrays.asList(escrow(player)));
            player.getPersistentDataContainer().remove(escrowKey);
        }
        cancel(trade);
    }

    @EventHandler
    void join(PlayerJoinEvent event) {
        if (canTrade(event.getPlayer())) {
            refund(event.getPlayer());
        }
    }

    private void cancel(Trade trade) {
        if (trade == null || !isActive(trade)) {
            return;
        }
        detach(trade);
        refundBoth(trade);
        notifyBoth(trade, "Trade cancelled.");
        // Closing the partner's inventory may fire another close event.
        plugin.getServer().getScheduler().runTask(plugin, () -> closeWindows(trade));
    }

    void closeAll() {
        for (Trade trade : new HashSet<>(active.values())) {
            detach(trade);
            refundBoth(trade);
            closeWindows(trade);
        }
        requests.clear();
    }

    private void refundBoth(Trade trade) {
        refund(trade.first.player);
        if (trade.second.player != null) {
            refund(trade.second.player);
        }
    }

    private boolean refund(Player player) {
        ItemStack[] offered = escrow(player);
        if (offered.length == 0) {
            return true;
        }
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents().clone();
        boolean full = false;
        for (int index = 0; index < offered.length; index++) {
            ItemStack item = offered[index];
            if (!empty(item)) {
                int left = ItemTransfers.insert(contents, item, inventory.getMaxStackSize());
                offered[index] = remaining(item, left);
                full |= left > 0;
            }
        }
        saveEscrow(player, offered);
        inventory.setStorageContents(contents);
        if (full) {
            player.sendMessage("Some trade items are still saved. Make room and run /trade --recover.");
        }
        return !full;
    }

    ItemStack[] escrow(Player player) {
        byte[] bytes = player.getPersistentDataContainer().get(escrowKey, PersistentDataType.BYTE_ARRAY);
        return bytes == null ? new ItemStack[0] : ItemStack.deserializeItemsFromBytes(bytes);
    }

    void saveEscrow(Player player, ItemStack[] offer) {
        List<ItemStack> stacks = Arrays.stream(offer).filter(item -> !empty(item)).toList();
        var data = player.getPersistentDataContainer();
        if (stacks.isEmpty()) {
            data.remove(escrowKey);
        } else {
            data.set(escrowKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(stacks));
        }
    }

    private boolean viewing(Side side) {
        return canTrade(side.player) && isActive(side.menu.trade) && hasMenuOpen(side);
    }

    private boolean isActive(Trade trade) {
        return active.get(trade.first.player.getUniqueId()) == trade;
    }

    private void detach(Trade trade) {
        active.remove(trade.first.player.getUniqueId(), trade);
        if (trade.second.player != null) {
            active.remove(trade.second.player.getUniqueId(), trade);
        }
    }

    private static void closeWindows(Trade trade) {
        closeWindow(trade.first);
        if (trade.second.player != null) {
            closeWindow(trade.second);
        }
    }

    private static void closeWindow(Side side) {
        side.menu.getInventory().clear();
        if (hasMenuOpen(side)) {
            side.player.closeInventory();
        }
    }

    private static boolean hasMenuOpen(Side side) {
        Inventory top = side.player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() == side.menu;
    }

    private static void notifyBoth(Trade trade, String message) {
        trade.first.player.sendMessage(message);
        if (trade.second.player != null) {
            trade.second.player.sendMessage(message);
        }
    }

    private static boolean canTrade(Player player) {
        return player.isOnline() && !player.isDead() && player.getGameMode() == GameMode.SURVIVAL;
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    private static ItemStack remaining(ItemStack item, int amount) {
        return amount == 0 ? null : item.asQuantity(amount);
    }

    private static ItemStack icon(Material material, String title, String... details) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(Component.text(title));
            meta.lore(Arrays.stream(details).<Component>map(Component::text).toList());
        });
        return stack;
    }
}
