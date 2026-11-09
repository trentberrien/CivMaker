package dev.civmaker;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.inventory.ItemStack;

final class WalletDrops implements Listener {
    private final Wallets wallets;
    private final Notes notes;
    private final Trading trading;

    WalletDrops(Wallets wallets, Notes notes, Trading trading) {
        this.wallets = wallets;
        this.notes = notes;
        this.trading = trading;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void dropWalletMoney(PlayerDeathEvent event) {
        Player player = event.getEntity();
        long balance = wallets.balance(player);
        long walletShare = balance / (10 * Notes.VALUE);
        long carriedShare = 0;
        ItemStack[] escrow = trading.escrow(player);
        if (event.getKeepInventory()) {
            // Physical banknotes would normally drop with the rest of the inventory.
            var inventory = player.getInventory();
            ItemStack[] carried = inventory.getContents();
            long walletRemainder = balance / Notes.VALUE % 10;
            carriedShare = (noteCount(carried) + noteCount(escrow) + walletRemainder) / 10;
            if (carriedShare > 0) {
                long remaining = deduct(carried, carriedShare);
                inventory.setContents(carried);
                if (remaining > 0) {
                    deduct(escrow, remaining);
                    trading.saveEscrow(player, escrow);
                }
            }
        } else {
            // Active trades have already closed. Recover any banknotes still saved from an earlier trade.
            dropEscrowBanknotes(event, player, escrow);
        }
        if (walletShare > 0) {
            wallets.setBalance(player, balance - walletShare * Notes.VALUE);
        }
        notes.addDeathDrops(event.getDrops(), walletShare + carriedShare);
    }

    private void dropEscrowBanknotes(PlayerDeathEvent event, Player player, ItemStack[] escrow) {
        boolean changed = false;
        for (int slot = 0; slot < escrow.length; slot++) {
            if (notes.isNote(escrow[slot])) {
                event.getDrops().add(escrow[slot]);
                escrow[slot] = null;
                changed = true;
            }
        }
        if (changed) {
            trading.saveEscrow(player, escrow);
        }
    }

    private long noteCount(ItemStack[] contents) {
        long count = 0;
        for (ItemStack item : contents) {
            if (notes.isNote(item)) {
                count += (notes.faceValue(item) / Notes.VALUE) * item.getAmount();
            }
        }
        return count;
    }

    private long deduct(ItemStack[] contents, long count) {
        for (int slot = 0; slot < contents.length && count > 0; slot++) {
            ItemStack item = contents[slot];
            if (!notes.isNote(item)) {
                continue;
            }
            long units = notes.faceValue(item) / Notes.VALUE;
            if (units == 1) {
                int take = (int) Math.min(count, item.getAmount());
                contents[slot] = take == item.getAmount() ? null : item.asQuantity(item.getAmount() - take);
                count -= take;
            } else if (units > 1) {
                // High-value notes are single items. Return any change in the same slot.
                long take = Math.min(count, units);
                contents[slot] = take == units ? null : notes.stack(1, (units - take) * Notes.VALUE);
                count -= take;
            }
        }
        return count;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void depositLargeNote(PlayerAttemptPickupItemEvent event) {
        Item item = event.getItem();
        long value = notes.largeNoteValue(item.getItemStack());
        if (value <= 0) {
            return;
        }

        event.setCancelled(true);
        event.setFlyAtPlayer(false);
        Player player = event.getPlayer();
        var owner = item.getOwner();
        if (!item.isValid() || !player.getCanPickupItems() || (owner != null && !owner.equals(player.getUniqueId()))) {
            return;
        }

        long balance = wallets.balance(player);
        if (balance > Long.MAX_VALUE - value) {
            return;
        }

        item.setCanPlayerPickup(false);
        wallets.setBalance(player, balance + value);
        item.remove();
        player.sendActionBar(Component.text("+$" + value + " from a banknote"));
    }
}
