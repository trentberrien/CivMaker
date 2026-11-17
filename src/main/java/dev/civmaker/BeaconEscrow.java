package dev.civmaker;

import dev.civmaker.BeaconEscrowLedger.Holding;
import dev.civmaker.BeaconEscrowLedger.Snapshot;
import dev.civmaker.BeaconEscrowLedger.Transfer;
import dev.civmaker.BeaconEscrowLedger.Offline;
import dev.civmaker.BeaconEscrowLedger.Option;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Bridges the durable ledger and Paper's separately saved player files. */
final class BeaconEscrow implements Listener {
    private final CivMaker plugin;
    private final CivStore store;
    private final Wallets wallets;
    private final Notes notes;
    private final BeaconEscrowLedger ledger;
    private final NamespacedKey receipt;
    private final Set<UUID> disconnecting = new HashSet<>();

    BeaconEscrow(CivMaker plugin, CivStore store, Wallets wallets, Notes notes) throws IOException {
        this.plugin = plugin;
        this.store = store;
        this.wallets = wallets;
        this.notes = notes;
        ledger = new BeaconEscrowLedger(plugin.getDataFolder().toPath());
        receipt = new NamespacedKey(plugin, "beacon_escrow_receipt");
    }

    BigInteger total(Village village) { return ledger.total(village); }
    boolean looted(Village village, Window window) { return ledger.looted(village.beacon, window.start()); }

    /** Runs before trade recovery, death timeouts, or any other join-time inventory changes. */
    @EventHandler(priority = EventPriority.LOWEST)
    void join(PlayerJoinEvent event) {
        disconnecting.remove(event.getPlayer().getUniqueId());
        returning(event.getPlayer());
    }

    /** Trade cancellation runs at NORMAL and returns offered items before this snapshot. */
    @EventHandler(priority = EventPriority.MONITOR)
    void quit(PlayerQuitEvent event) { departing(event.getPlayer()); }

    void returning(Player player) {
        try {
            recover(player);
            ledger.returning(player.getUniqueId());
            refund(player);
        } catch (IOException | RuntimeException error) {
            failed(player, error);
        }
    }

    void departing(Player player) {
        try {
            recover(player);
            // Registered membership is used rather than an administrator's temporary allegiance.
            Village village = store.registeredVillage(player.getUniqueId());
            if (village == null) return;
            // Repeated quit/disable notifications cannot deposit the same inventory twice.
            if (ledger.offline(player.getUniqueId()) != null) return;
            ItemStack[] contents = player.getInventory().getContents().clone();
            BigInteger value = BigInteger.valueOf(balance(player));
            for (int slot = 0; slot < contents.length; slot++) {
                Stripped result = strip(contents[slot], 0);
                contents[slot] = result.item;
                value = value.add(result.value);
            }
            Stripped cursor = strip(player.getItemOnCursor(), 0);
            value = value.add(cursor.value);
            if (value.signum() > 0 && ledger.holding(player.getUniqueId()) == null) {
                Transfer transfer = transfer(player, 0, contents, cursor.item);
                ledger.deposit(player.getUniqueId(), new Holding(village.id, village.beacon, value), transfer);
                recover(player);
            }
            ledger.departing(player.getUniqueId(), new Offline(village.id, village.beacon, player.getName(),
                    transfer(player, balance(player), player.getInventory().getContents(), player.getItemOnCursor())));
        } catch (IOException | RuntimeException error) {
            failed(player, error);
        }
    }

    /** Overflow refund claims retry automatically as an online player makes wallet space. */
    void refresh() {
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (!player.isDead() && ledger.refundable(player.getUniqueId()).signum() > 0) returning(player);
        }
    }

    private void refund(Player player) throws IOException {
        UUID id = player.getUniqueId();
        BigInteger available = ledger.refundable(id);
        long balance = balance(player);
        long amount = available.min(BigInteger.valueOf(Long.MAX_VALUE - balance)).longValueExact();
        if (amount == 0 && ledger.holding(id) == null) return;
        Transfer transfer = transfer(player, balance + amount, player.getInventory().getContents(), player.getItemOnCursor());
        ledger.refund(id, amount, transfer);
        recover(player);
        if (amount > 0) player.sendMessage("Returned $" + String.format("%,d", amount) + " from your capital beacon to your wallet.");
        if (available.compareTo(BigInteger.valueOf(amount)) > 0)
            player.sendMessage("Your wallet is full. The remaining refund is reserved for you and returns as you make room.");
    }

    boolean canPick(Player player, Block target, Village village, Window window) {
        String reason = unavailable(player, target, village, window);
        if (reason != null) player.sendMessage(reason);
        return reason == null;
    }

    String unavailable(Player player, Block target, Village village, Window window) {
        String reason = raidUnavailable(player, target, village, window);
        if (reason != null) return reason;
        if (looted(village, window)) return "Offline money has already been looted during this raid period.";
        if (ledger.lootAmount(village).signum() == 0) return "This beacon needs at least $4 in offline funds to be looted.";
        return null;
    }

    String raidUnavailable(Player player, Block target, Village village, Window window) {
        if (village == null || !store.hasVillage(village.id) || target.getType() != Material.BEACON
                || !village.beacon.equals(Position.at(target))) return "Use the lockpick on a faction's capital beacon.";
        if (window == null || !window.equals(store.activeWindow(System.currentTimeMillis()))
                || !store.raidCovers(village.beacon.plot()) || store.raidRestricted(player.getUniqueId())
                || store.registeredVillage(player.getUniqueId()) == village || store.memberOf(player.getUniqueId()) == village
                || player.getGameMode() != GameMode.SURVIVAL)
            return "This capital must belong to an enemy faction and be open to raids.";
        if (!player.isOnline() || player.isDead() || !player.getWorld().equals(target.getWorld())
                || player.getLocation().distanceSquared(target.getLocation().add(0.5, 0.5, 0.5)) > 36)
            return "Move closer to the capital beacon.";
        return null;
    }

    boolean looted(Village village, Window window, Option option) {
        return ledger.looted(village.beacon, window.start(), option);
    }

    Map<UUID, Offline> offlinePlayers(Village village) {
        var result = new java.util.LinkedHashMap<UUID, Offline>();
        ledger.offlinePlayers().entrySet().stream()
                .filter(entry -> eligible(entry.getKey(), entry.getValue(), village))
                .sorted(java.util.Comparator.comparing(entry -> entry.getValue().name(),
                        java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private boolean eligible(UUID id, Offline offline, Village village) {
        Player online = plugin.getServer().getPlayer(id);
        return offline != null && (online == null || !online.isOnline())
                && offline.village().equals(village.id) && offline.beacon().equals(village.beacon)
                && store.registeredVillage(id) == village;
    }

    ItemStack[] items(UUID owner, Village village, Option option) {
        Offline offline = ledger.offline(owner);
        if (option == Option.MONEY || !eligible(owner, offline, village)) return null;
        Snapshot snapshot = offline.contents().snapshot();
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(decode(option == Option.ENDER_CHEST
                ? snapshot.enderChest() : snapshot.inventory()));
        if (option == Option.INVENTORY) {
            // The carried cursor is part of the offline player's personal inventory too.
            ItemStack[] cursor = ItemStack.deserializeItemsFromBytes(decode(snapshot.cursor()));
            items = java.util.Arrays.copyOf(items, items.length + 1);
            items[items.length - 1] = cursor[0];
        }
        return items;
    }

    boolean claimItems(Player player, Block target, Village village, Window window, Option option, UUID owner) {
        String reason = raidUnavailable(player, target, village, window);
        if (reason != null) { player.sendMessage(reason); return false; }
        if (looted(village, window, option)) {
            player.sendMessage("This option has already been picked during this raid period.");
            return false;
        }
        ItemStack[] contents = items(owner, village, option);
        if (contents == null || java.util.Arrays.stream(contents).noneMatch(item -> item != null && !item.isEmpty())) {
            player.sendMessage("That offline inventory is no longer available or is empty.");
            return false;
        }
        try {
            ledger.claimItems(village, window.start(), option);
            village.timesRaided++;
            store.dirty = true;
            return true;
        } catch (IOException | RuntimeException error) { failed(player, error); return false; }
    }

    boolean takeItem(Player player, UUID owner, Village village, Option option, int slot) {
        try {
            recover(player, false);
            Offline offline = ledger.offline(owner);
            ItemStack[] source = items(owner, village, option);
            if (source == null || slot < 0 || slot >= source.length || source[slot] == null || source[slot].isEmpty()) return false;
            ItemStack[] storage = player.getInventory().getStorageContents();
            if (ItemTransfers.insert(storage, source[slot], player.getInventory().getMaxStackSize()) != 0) {
                player.sendMessage("Free up enough inventory space for the whole stack.");
                return false;
            }
            source[slot] = null;
            ItemStack[] after = player.getInventory().getContents();
            System.arraycopy(storage, 0, after, 0, storage.length);
            Snapshot before = offline.contents().snapshot();
            Snapshot victim = option == Option.ENDER_CHEST
                    ? new Snapshot(before.inventory(), before.cursor(), before.data(), encode(ItemStack.serializeItemsAsBytes(source)))
                    : new Snapshot(encode(ItemStack.serializeItemsAsBytes(java.util.Arrays.copyOf(source, source.length - 1))),
                            encode(ItemStack.serializeItemsAsBytes(new ItemStack[]{source[source.length - 1]})), before.data(), before.enderChest());
            ledger.takeItems(owner, offline, new Transfer(UUID.randomUUID().toString(), offline.contents().walletAfter(), victim),
                    player.getUniqueId(), transfer(player, balance(player), after, player.getItemOnCursor()));
            recover(player, false);
            return true;
        } catch (IOException | RuntimeException error) { failed(player, error); return false; }
    }

    void loot(Player player, Block target, Village village, Window window) {
        try {
            recover(player);
            // Recheck at completion: another puzzle, a login, shield, or window change can win the race.
            if (!canPick(player, target, village, window)) return;
            BigInteger amount = ledger.lootAmount(village);
            long balance = balance(player);
            if (amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE - balance)) > 0) {
                player.sendMessage("Make room in your wallet before looting this beacon.");
                return;
            }
            Transfer transfer = transfer(player, balance + amount.longValueExact(),
                    player.getInventory().getContents(), player.getItemOnCursor());
            ledger.loot(player.getUniqueId(), village, window.start(), transfer);
            recover(player);
            village.timesRaided++;
            store.dirty = true;
            player.sendMessage("Beacon picked. You took $" + String.format("%,d", amount) + " (25%) into your wallet.");
        } catch (IOException | RuntimeException error) {
            failed(player, error);
        }
    }

    private long balance(Player player) {
        long balance = wallets.balance(player);
        if (balance < 0) throw new IllegalStateException("Negative wallet balance");
        return balance;
    }

    private Transfer transfer(Player player, long walletAfter, ItemStack[] contents, ItemStack cursor) throws IOException {
        Snapshot snapshot = new Snapshot(encode(ItemStack.serializeItemsAsBytes(contents)),
                encode(ItemStack.serializeItemsAsBytes(new ItemStack[]{cursor})),
                encode(player.getPersistentDataContainer().serializeToBytes()),
                encode(ItemStack.serializeItemsAsBytes(player.getEnderChest().getContents())));
        return new Transfer(UUID.randomUUID().toString(), walletAfter, snapshot);
    }

    void recover(Player player) throws IOException {
        recover(player, true);
    }

    private void recover(Player player, boolean close) throws IOException {
        UUID id = player.getUniqueId();
        Transfer transfer = ledger.lastTransfer(id);
        if (transfer == null) return;
        var data = player.getPersistentDataContainer();
        boolean applied = transfer.id().equals(data.get(receipt, PersistentDataType.STRING));
        if (!applied) {
            Snapshot snapshot = transfer.snapshot();
            // Include plugin PDC so replay cannot both restore refunded trade items and refund the old trade escrow.
            ItemStack[] contents = ItemStack.deserializeItemsFromBytes(decode(snapshot.inventory()));
            ItemStack[] cursor = ItemStack.deserializeItemsFromBytes(decode(snapshot.cursor()));
            if (contents.length != player.getInventory().getContents().length || cursor.length != 1)
                throw new IOException("Invalid player inventory snapshot");
            data.readFromBytes(decode(snapshot.data()), true);
            player.getInventory().setContents(contents);
            player.setItemOnCursor(cursor[0]);
            if (snapshot.enderChest() != null)
                player.getEnderChest().setContents(ItemStack.deserializeItemsFromBytes(decode(snapshot.enderChest())));
            wallets.setBalance(player, transfer.walletAfter());
            data.set(receipt, PersistentDataType.STRING, transfer.id());
            if (close) player.closeInventory();
        }
        if (!applied || ledger.pending(id) != null) {
            // Receipt, wallet, and inventory are in the SAME player save. A replay sets absolute values.
            player.saveData();
            if (ledger.pending(id) != null) ledger.finish(id, transfer.id());
        }
    }

    private record Stripped(ItemStack item, BigInteger value) {}

    /** Includes offhand, cursor, and legacy notes nested inside carried bundles/shulkers. */
    private Stripped strip(ItemStack original, int depth) {
        if (original == null || original.isEmpty()) return new Stripped(original, BigInteger.ZERO);
        if (depth > 64) throw new IllegalStateException("Carried container nesting is too deep");
        if (notes.isNote(original)) {
            long faceValue = notes.faceValue(original);
            if (faceValue <= 0) throw new IllegalStateException("Invalid banknote face value");
            return new Stripped(null, BigInteger.valueOf(faceValue).multiply(BigInteger.valueOf(original.getAmount())));
        }
        var meta = original.getItemMeta();
        BigInteger total = BigInteger.ZERO;
        if (meta instanceof BundleMeta bundle) {
            var items = new ArrayList<ItemStack>();
            for (ItemStack nested : bundle.getItems()) {
                Stripped stripped = strip(nested, depth + 1);
                if (stripped.item != null) items.add(stripped.item);
                total = total.add(stripped.value);
            }
            if (total.signum() > 0) bundle.setItems(items);
        } else if (meta instanceof BlockStateMeta blockMeta && blockMeta.getBlockState() instanceof Container container) {
            var inventory = container.getInventory();
            ItemStack[] contents = inventory.getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                Stripped stripped = strip(contents[slot], depth + 1);
                contents[slot] = stripped.item;
                total = total.add(stripped.value);
            }
            if (total.signum() > 0) {
                inventory.setContents(contents);
                blockMeta.setBlockState(container);
            }
        }
        if (total.signum() == 0) return new Stripped(original, total);
        ItemStack changed = original.clone();
        changed.setItemMeta(meta);
        return new Stripped(changed, total.multiply(BigInteger.valueOf(original.getAmount())));
    }

    private static String encode(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static byte[] decode(String bytes) { return Base64.getDecoder().decode(bytes); }

    private void failed(Player player, Exception error) {
        plugin.getLogger().log(Level.SEVERE, "Beacon escrow transfer failed for " + player.getUniqueId()
                + ". Recovery records retained; do not delete beacon-escrow.json.", error);
        if (player.isOnline() && disconnecting.add(player.getUniqueId()))
            player.kick(Component.text("Your beacon transfer needs recovery. Please reconnect; contact an admin if this continues."));
    }
}
