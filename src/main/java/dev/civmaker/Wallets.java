package dev.civmaker;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

final class Wallets {
    private final NamespacedKey key;

    Wallets(CivMaker plugin) {
        key = new NamespacedKey(plugin, "wallet_v1");
    }

    long balance(Player player) {
        return player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.LONG, 0L);
    }

    void setBalance(Player player, long balance) {
        player.getPersistentDataContainer().set(key, PersistentDataType.LONG, balance);
    }
}
