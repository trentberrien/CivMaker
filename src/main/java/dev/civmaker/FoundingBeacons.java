package dev.civmaker;

import com.destroystokyo.paper.event.block.BeaconEffectEvent;
import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

final class FoundingBeacons implements Listener {
    static final long PRICE = 10_000;
    static final String PRICE_LABEL = "$10,000";
    private static final String NAME_INPUT = "name";
    private static final String NAME_RULES = FactionNames.FORMAT_ERROR + " No profanity, slurs, or extremist references.";
    private static final String ALREADY_MEMBER = "You already belong to a civilization.";
    private static final String RESUME = "Right-click the Founding Beacon whenever you're ready.";

    // A fresh token binds each dialog to one placement, even if the block is mined and placed again.
    private record Site(Block block, UUID owner, String token) {}

    private final CivMaker plugin;
    private final CivStore store;
    private final Wallets wallets;
    private final NaturalVillages natural;
    private final int minY;
    private final NamespacedKey beaconKey;
    private final FactionNames factionNames;

    FoundingBeacons(CivMaker plugin, CivStore store, Wallets wallets, NaturalVillages natural) {
        this.plugin = plugin;
        this.store = store;
        this.wallets = wallets;
        this.natural = natural;
        minY = plugin.getConfig().getInt("village.min-beacon-y");
        beaconKey = new NamespacedKey(plugin, "founding_beacon");
        factionNames = new FactionNames(plugin.getConfig().getStringList("name-filter.additional-blocked-terms"),
                plugin.getConfig().getStringList("name-filter.allowed-words"));
    }

    boolean canBuy(Player player) {
        UUID id = player.getUniqueId();
        return store.registeredVillage(id) == null && store.memberOf(id) == null;
    }

    String placementHint() {
        return "Place in an unclaimed Overworld chunk at Y " + minY + " or higher.";
    }

    void buy(Player player) {
        if (!canBuy(player)) {
            player.sendMessage(ALREADY_MEMBER + " Founding Beacons are available when you have no membership.");
            return;
        }
        long balance = wallets.balance(player);
        if (balance < PRICE) {
            player.sendMessage("You need " + PRICE_LABEL + " in your wallet to buy a Founding Beacon.");
            return;
        }
        var inventory = player.getInventory();
        var contents = inventory.getStorageContents().clone();
        if (ItemTransfers.insert(contents, item(), inventory.getMaxStackSize()) > 0) {
            player.sendMessage("Free up inventory space before buying a Founding Beacon.");
            return;
        }
        inventory.setStorageContents(contents);
        wallets.setBalance(player, balance - PRICE);
        player.sendMessage("Bought a Founding Beacon for " + PRICE_LABEL + ". " + placementHint());
        player.sendMessage("Give it clear sky above. A setup menu opens when you place it.");
    }

    private ItemStack item() {
        var beacon = new ItemStack(Material.BEACON);
        beacon.editMeta(meta -> {
            meta.displayName(Component.text("Founding Beacon", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text(placementHint(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Give it clear sky above, then follow the setup menu.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Beacon powers unlock when it becomes your capital.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(beaconKey, PersistentDataType.BYTE, (byte) 1);
        });
        return beacon;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void place(BlockPlaceEvent event) {
        var item = event.getItemInHand();
        Block block = event.getBlockPlaced();
        if (item.getType() != Material.BEACON
                || !item.getPersistentDataContainer().has(beaconKey, PersistentDataType.BYTE)) {
            return;
        }
        if (!event.canBuild() || !(block.getState(false) instanceof Beacon beacon)) {
            event.setCancelled(true);
            return;
        }
        Player player = event.getPlayer();
        String error = foundingError(player, block);
        if (error != null) {
            event.setCancelled(true);
            player.sendMessage(error);
            return;
        }
        UUID owner = player.getUniqueId();
        String token = owner + "/" + UUID.randomUUID();
        beacon.getPersistentDataContainer().set(beaconKey, PersistentDataType.STRING, token);
        if (!beacon.update(false, false)) {
            event.setCancelled(true);
            player.sendMessage("Could not set up this Founding Beacon. Try placing it again.");
            return;
        }
        var site = new Site(block, owner, token);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && token.equals(marker(block))) {
                confirm(player, site);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void interact(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null) {
            return;
        }
        String token = marker(block);
        if (token == null || isCapital(block)) {
            return;
        }
        boolean blocked = event.useInteractedBlock() == Event.Result.DENY;
        event.setCancelled(true);
        if (blocked || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!token.startsWith(id + "/")) {
            player.sendMessage("The player who placed this Founding Beacon can open its setup menu.");
            return;
        }
        confirm(player, new Site(block, id, token));
    }

    private void confirm(Player player, Site site) {
        if (!validate(player, site)) {
            return;
        }
        var next = action("Choose a name", (response, audience) -> {
            if (audience instanceof Player clicked && validate(clicked, site)) {
                name(clicked, site, "", null);
            }
        });
        var later = ActionButton.builder(Component.text("Not now")).build();
        var base = DialogBase.builder(Component.text("Start a civilization?"))
                .body(List.of(
                        DialogBody.plainMessage(Component.text("This chunk will become your capital. The beacon will unlock its normal powers.")),
                        DialogBody.plainMessage(Component.text(RESUME, NamedTextColor.GRAY))))
                .build();
        show(player, base, DialogType.confirmation(next, later));
    }

    private void name(Player player, Site site, String initial, String error) {
        var input = DialogInput.text(NAME_INPUT, Component.text("Civilization name"))
                .initial(initial).maxLength(24).build();
        var submit = action("Found civilization", (response, audience) -> {
            if (audience instanceof Player clicked && validate(clicked, site)) {
                String entered = response.getText(NAME_INPUT);
                found(clicked, site, entered == null ? "" : entered.strip());
            }
        });
        var later = ActionButton.builder(Component.text("Not now")).build();
        var base = DialogBase.builder(Component.text("Name your civilization"))
                .inputs(List.of(input))
                .body(error == null ? List.of(DialogBody.plainMessage(Component.text(NAME_RULES)))
                        : List.of(DialogBody.plainMessage(Component.text(error, NamedTextColor.RED)),
                                DialogBody.plainMessage(Component.text(NAME_RULES))))
                .build();
        show(player, base, DialogType.confirmation(submit, later));
    }

    private static ActionButton action(String label, DialogActionCallback callback) {
        return ActionButton.builder(Component.text(label))
                .action(DialogAction.customClick(callback, ClickCallback.Options.builder().build())).build();
    }

    private static void show(Player player, DialogBase base, DialogType type) {
        player.showDialog(Dialog.create(builder -> builder.empty().base(base).type(type)));
    }

    private boolean validate(Player player, Site site) {
        Block block = site.block();
        if (!site.owner().equals(player.getUniqueId()) || !player.isOnline()) {
            return false;
        }
        if (player.getWorld() != block.getWorld()
                || player.getEyeLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > 36) {
            player.sendMessage("Move closer to your Founding Beacon. " + RESUME);
            return false;
        }
        if (!site.token().equals(marker(block))) {
            player.sendMessage("That Founding Beacon has moved. Right-click it in its new spot to start again.");
            return false;
        }
        String error = foundingError(player, block);
        if (error != null) {
            player.sendMessage(error);
            return false;
        }
        return true;
    }

    private String foundingError(Player player, Block block) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return "Switch to Survival to place or set up a Founding Beacon.";
        }
        if (!canBuy(player)) {
            return ALREADY_MEMBER + " Spare Founding Beacons stay inactive.";
        }
        if (block.getWorld().getEnvironment() != World.Environment.NORMAL || block.getY() < minY) {
            return placementHint();
        }
        if (store.claimAt(block) != null) {
            return "This chunk is claimed. Choose an unclaimed chunk for your capital.";
        }
        if (natural.reserved(block.getWorld(), Position.at(block).plot()) || natural.inBellProtection(block)) {
            return "This area is reserved for a natural village. Choose a spot farther from the village.";
        }
        for (int y = block.getY() + 1; y < block.getWorld().getMaxHeight(); y++) {
            if (!block.getWorld().getBlockAt(block.getX(), y, block.getZ()).getType().isAir()) {
                return "Clear the blocks above your Founding Beacon so it has open sky.";
            }
        }
        return null;
    }

    private void found(Player player, Site site, String name) {
        String nameError = factionNames.error(name);
        if (nameError != null) {
            name(player, site, "", nameError);
            return;
        }
        for (Village village : store.villages()) {
            if (village.name.equalsIgnoreCase(name)) {
                name(player, site, name, "That name is taken. Choose another name.");
                return;
            }
        }
        store.addVillage(new Village(name, player.getUniqueId(), Position.at(site.block())));
        player.sendMessage(name + " is founded! Your capital chunk is protected and starts with "
                + CivStore.CAPITAL_POINTS + " points. Your beacon is ready to power with a pyramid.");
    }

    private String marker(Block block) {
        return block.getType() == Material.BEACON ? marker(block.getState(false)) : null;
    }

    private String marker(BlockState state) {
        return state instanceof Beacon beacon ? beacon.getPersistentDataContainer().get(beaconKey, PersistentDataType.STRING) : null;
    }

    private boolean isCapital(Block block) {
        Village village = store.claimAt(block);
        return village != null && village.beacon.equals(Position.at(block));
    }

    private boolean locked(Block block) {
        return marker(block) != null && !isCapital(block);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void effect(BeaconEffectEvent event) {
        if (locked(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void configure(PlayerChangeBeaconEffectEvent event) {
        if (locked(event.getBeacon())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void drop(BlockDropItemEvent event) {
        if (marker(event.getBlockState()) != null) {
            for (var drop : event.getItems()) {
                if (drop.getItemStack().getType() == Material.BEACON) {
                    drop.setItemStack(item().asQuantity(drop.getItemStack().getAmount()));
                }
            }
        }
    }

    // Environmental drops bypass BlockDropItemEvent and could otherwise yield a vanilla beacon.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void explode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::locked);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void explode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::locked);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void destroy(BlockDestroyEvent event) {
        if (marker(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void entityChange(EntityChangeBlockEvent event) {
        if (locked(event.getBlock())) {
            event.setCancelled(true);
        }
    }
}
