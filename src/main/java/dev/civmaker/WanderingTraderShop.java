package dev.civmaker;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import dev.civmaker.RaidEquipment.Tool;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

final class WanderingTraderShop implements Listener {
    private static final int OFFER_COUNT = 6;
    private static final int FIRST_SLOT = 10;

    record Offer(Tool tool, long price, int discount, boolean sold) {
        Offer {
            if (price < 0 || discount < 25 || discount > 50) {
                throw new IllegalArgumentException("Invalid wandering trader offer");
            }
        }

        Offer sell() {
            return new Offer(tool, price, discount, true);
        }
    }

    private static final class Menu implements InventoryHolder {
        private final Inventory inventory = Bukkit.createInventory(this, 27, Component.text("Wandering Trader Deals"));
        private final WanderingTrader trader;

        Menu(WanderingTrader trader) {
            this.trader = trader;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final CivMaker plugin;
    private final RaidShop shop;
    private final RaidEquipment equipment;
    private final WarpPortals warps;
    private final NamespacedKey offersKey;

    WanderingTraderShop(CivMaker plugin, RaidShop shop, RaidEquipment equipment, WarpPortals warps) {
        this.plugin = plugin;
        this.shop = shop;
        this.equipment = equipment;
        this.warps = warps;
        offersKey = new NamespacedKey(plugin, "wandering_trader_offers_v1");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void loaded(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof WanderingTrader trader) {
            trader.setRecipes(List.of());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void acquireTrade(VillagerAcquireTradeEvent event) {
        if (event.getEntity() instanceof WanderingTrader) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void interact(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof WanderingTrader trader) || warps.isWarpTrader(trader)) {
            return;
        }
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (cancelled || event.getHand() != EquipmentSlot.HAND || player.getGameMode() != GameMode.SURVIVAL
                || player.isDead()) {
            return;
        }
        trader.setRecipes(List.of());
        // Open on the next tick, after Minecraft finishes handling the entity interaction.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!available(player, trader)) {
                return;
            }
            Menu menu = new Menu(trader);
            refresh(menu, offers(trader));
            player.openInventory(menu.inventory);
        });
    }

    static List<Offer> generate(RaidShop shop, RandomGenerator random) {
        var products = new ArrayList<>(RaidShop.products());
        var offers = new ArrayList<Offer>();
        for (int i = 0; i < OFFER_COUNT && !products.isEmpty(); i++) {
            Tool tool = products.remove(random.nextInt(products.size()));
            int discount = random.nextInt(25, 51);
            long price = discountedPrice(shop.price(tool), discount);
            offers.add(new Offer(tool, price, discount, false));
        }
        return offers;
    }

    static long discountedPrice(long price, int discount) {
        // Round up to whole wallet dollars without overflowing large configured prices.
        int percent = 100 - discount;
        return price / 100 * percent + (price % 100 * percent + 99) / 100;
    }

    static String encode(List<Offer> offers) {
        return offers.stream().map(offer -> offer.tool().name() + ":" + offer.price() + ":"
                + offer.discount() + ":" + offer.sold()).collect(Collectors.joining(";"));
    }

    static List<Offer> decode(String data) {
        var offers = new ArrayList<Offer>();
        var seen = new HashSet<Tool>();
        for (String entry : data.split(";")) {
            String[] fields = entry.split(":");
            if (fields.length != 4 || !(fields[3].equals("true") || fields[3].equals("false"))) {
                throw new IllegalArgumentException("Invalid wandering trader stock");
            }
            Offer offer = new Offer(Tool.valueOf(fields[0]), Long.parseLong(fields[1]), Integer.parseInt(fields[2]),
                    Boolean.parseBoolean(fields[3]));
            if (!seen.add(offer.tool())) {
                throw new IllegalArgumentException("Duplicate wandering trader item");
            }
            offers.add(offer);
        }
        if (offers.size() > OFFER_COUNT) {
            throw new IllegalArgumentException("Too many wandering trader offers");
        }
        return offers;
    }

    private List<Offer> offers(WanderingTrader trader) {
        String data = trader.getPersistentDataContainer().get(offersKey, PersistentDataType.STRING);
        if (data != null) {
            return decode(data);
        }
        List<Offer> offers = generate(shop, ThreadLocalRandom.current());
        save(trader, offers);
        return offers;
    }

    private void save(WanderingTrader trader, List<Offer> offers) {
        trader.getPersistentDataContainer().set(offersKey, PersistentDataType.STRING, encode(offers));
    }

    private void refresh(Menu menu, List<Offer> offers) {
        for (int i = 0; i < offers.size(); i++) {
            Offer offer = offers.get(i);
            ItemStack icon;
            if (offer.sold()) {
                icon = new ItemStack(Material.BARRIER);
                icon.editMeta(meta -> meta.displayName(Component.text(offer.tool().label + " — Sold out", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)));
            } else {
                icon = equipment.item(offer.tool(), String.format(Locale.US, "Left-click to pay $%,d (%d%% off)",
                        offer.price(), offer.discount()));
                icon.editMeta(meta -> {
                    var lore = new ArrayList<>(meta.lore());
                    lore.add(Component.text("One purchase per trader, shared by all players.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false));
                    meta.lore(lore);
                });
            }
            menu.inventory.setItem(FIRST_SLOT + i, icon);
        }
    }

    private boolean available(Player player, WanderingTrader trader) {
        return player.isOnline() && !player.isDead() && player.getGameMode() == GameMode.SURVIVAL
                && trader.isValid() && !warps.isWarpTrader(trader) && player.getWorld().equals(trader.getWorld())
                && player.getLocation().distanceSquared(trader.getLocation()) <= 25;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        var cursor = event.getCursor();
        if (cancelled || event.getClick() != ClickType.LEFT || (cursor != null && !cursor.isEmpty())
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!available(player, menu.trader)) {
            player.closeInventory();
            return;
        }
        int index = event.getRawSlot() - FIRST_SLOT;
        if (index < 0 || index >= OFFER_COUNT) {
            return;
        }
        // Always re-read the entity's stock: another player may have bought from a different menu.
        List<Offer> offers = offers(menu.trader);
        if (index >= offers.size()) {
            return;
        }
        Offer offer = offers.get(index);
        if (offer.sold()) {
            player.sendMessage("This trader has already sold that item.");
            refresh(menu, offers);
            return;
        }
        if (!shop.purchase(player, offer.tool(), false, offer.price())) {
            return;
        }
        offers.set(index, offer.sell());
        save(menu.trader, offers);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof Menu open
                    && open.trader.getUniqueId().equals(menu.trader.getUniqueId())) {
                refresh(open, offers);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                player.closeInventory();
            }
        }
    }
}
