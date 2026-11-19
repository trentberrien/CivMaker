package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/** Replacement beacons share FoundingBeacons' effect, drop and environmental protections. */
final class CapitalBeaconMoving implements Listener {
    static final long PRICE = 1_000;
    static final NamespacedKey MOVE_KEY = new NamespacedKey("civmaker", "capital_beacon_move");
    private final CivMaker plugin;
    private final CivStore store;
    private final Wallets wallets;
    private final NaturalVillages natural;
    private final int minY;

    record Site(Village village, Block block, Position original, String purchase, String placement) {}

    CapitalBeaconMoving(CivMaker plugin, CivStore store, Wallets wallets, NaturalVillages natural) {
        this.plugin = plugin;
        this.store = store;
        this.wallets = wallets;
        this.natural = natural;
        minY = plugin.getConfig().getInt("village.min-beacon-y");
    }

    void buy(Player player, Village village) {
        String error = ownerError(player, village);
        if (error != null) {
            player.sendMessage(error);
            return;
        }
        if (!FactionRenaming.inReach(player, village)) {
            player.sendMessage("Move closer to your capital beacon and open its menu again.");
            return;
        }
        long balance = wallets.balance(player);
        if (balance < PRICE) {
            player.sendMessage("You need $1,000 in your wallet to move your capital beacon.");
            return;
        }
        String token = village.id + "/" + UUID.randomUUID();
        var inventory = player.getInventory();
        var contents = inventory.getStorageContents().clone();
        if (ItemTransfers.insert(contents, item(token, minY), inventory.getMaxStackSize()) > 0) {
            player.sendMessage("Free up inventory space before buying a replacement capital beacon.");
            return;
        }
        inventory.setStorageContents(contents);
        wallets.setBalance(player, balance - PRICE);
        village.beaconMoveToken = token;
        store.dirty = true;
        player.sendMessage("Bought a replacement capital beacon for $1,000. Place it in your own Overworld plot at Y "
                + minY + " or higher with clear sky above, then confirm. Your old beacon stays until confirmation.");
    }

    static ItemStack item(String token, int minY) {
        var beacon = new ItemStack(Material.BEACON);
        beacon.editMeta(meta -> {
            meta.displayName(Component.text("Replacement Capital Beacon", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Owner only: place in your own Overworld plot at Y " + minY + "+.", NamedTextColor.GRAY),
                    Component.text("Requires clear sky. Confirm the move after placing.", NamedTextColor.GRAY),
                    Component.text("Keeps your capital's upgrade. Inactive until confirmed.", NamedTextColor.GRAY))
                    .stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
            meta.getPersistentDataContainer().set(FoundingBeacons.BEACON_KEY, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(MOVE_KEY, PersistentDataType.STRING, token);
        });
        return beacon;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void place(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.BEACON || !item.getPersistentDataContainer().has(MOVE_KEY)) return;
        String token = item.getPersistentDataContainer().get(MOVE_KEY, PersistentDataType.STRING);
        Village village = village(token);
        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();
        String error = placementError(player, village, token, block);
        if (error != null || !event.canBuild() || !(block.getState(false) instanceof Beacon)) {
            event.setCancelled(true);
            if (error != null) player.sendMessage(error);
            return;
        }
        Beacon state = (Beacon) block.getState(false);
        String placement = player.getUniqueId() + "/" + UUID.randomUUID();
        state.getPersistentDataContainer().set(FoundingBeacons.BEACON_KEY, PersistentDataType.STRING, placement);
        state.getPersistentDataContainer().set(MOVE_KEY, PersistentDataType.STRING, token);
        if (!state.update(false, false)) {
            event.setCancelled(true);
            player.sendMessage("Could not set up this replacement beacon. Try placing it again.");
            return;
        }
        var site = new Site(village, block, village.beacon, token, placement);
        plugin.getServer().getScheduler().runTask(plugin, () -> prompt(player, site));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void interact(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || block.getType() != Material.BEACON) return;
        String token = marker(block.getState(false));
        if (token == null) return;
        Village capital = store.claimAt(block);
        if (capital != null && capital.beacon.equals(Position.at(block))) return;
        boolean denied = event.useInteractedBlock() == Event.Result.DENY;
        event.setCancelled(true);
        if (denied || event.getHand() != EquipmentSlot.HAND) return;
        Village village = village(token);
        if (village == null) {
            event.getPlayer().sendMessage("This replacement beacon is no longer valid.");
            return;
        }
        String placement = ((Beacon) block.getState(false)).getPersistentDataContainer()
                .get(FoundingBeacons.BEACON_KEY, PersistentDataType.STRING);
        prompt(event.getPlayer(), new Site(village, block, village.beacon, token, placement));
    }

    private void prompt(Player player, Site site) {
        if (!validate(player, site)) return;
        var confirm = ActionButton.builder(Component.text("Confirm move"))
                .action(DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player clicked) complete(clicked, site);
                }, ClickCallback.Options.builder().build())).build();
        var later = ActionButton.builder(Component.text("Not now")).build();
        var base = DialogBase.builder(Component.text("Move your capital beacon?"))
                .body(List.of(DialogBody.plainMessage(Component.text(
                        "Confirm to destroy the old beacon and make this your capital beacon. Your civilization, claims and upgrades are kept."
                                + " Right-click this replacement to resume if you choose Not now."))))
                .build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base).type(DialogType.confirmation(confirm, later))));
    }

    void complete(Player player, Site site) {
        if (!validate(player, site)) return;
        Village village = site.village();
        World world = site.block().getWorld();
        Position original = site.original();
        // Load the original chunk before changing either beacon, including after a server restart.
        World oldWorld = plugin.getServer().getWorld(original.world());
        if (oldWorld == null) {
            player.sendMessage("Your original capital's world is unavailable. Try again later.");
            return;
        }
        Block old = oldWorld.getBlockAt(original.x(), original.y(), original.z());
        if (old.getType() != Material.BEACON) {
            player.sendMessage("Your original capital beacon is missing. The move was not completed.");
            return;
        }
        Beacon replacement = (Beacon) site.block().getState(false);
        Beacon previous = (Beacon) old.getState(false);
        if (village.expandedClaims) {
            var primary = previous.getPrimaryEffect();
            var secondary = previous.getSecondaryEffect();
            replacement.setPrimaryEffect(primary == null ? null : primary.getType());
            replacement.setSecondaryEffect(secondary == null ? null : secondary.getType());
        }
        if (!replacement.update(false, false)) {
            player.sendMessage("Could not activate the replacement beacon. Your old capital is still in place.");
            return;
        }
        // The egg remains attached to the capital and keeps its existing defense progress.
        Block oldEgg = oldWorld.getBlockAt(original.x(), original.y() + 1, original.z());
        if (oldEgg.getType() == Material.DRAGON_EGG) {
            world.getBlockAt(site.block().getX(), site.block().getY() + 1, site.block().getZ())
                    .setType(Material.DRAGON_EGG, false);
            oldEgg.setType(Material.AIR, false);
        }
        village.beacon = Position.at(site.block());
        village.beaconMoveToken = null;
        store.dirty = true;
        old.setType(Material.AIR, false);
        player.sendMessage("Your capital beacon has moved. All capital perks and your beacon upgrade are kept.");
    }

    private boolean validate(Player player, Site site) {
        Block block = site.block();
        String error = placementError(player, site.village(), site.purchase(), block);
        if (error == null && (block.getType() != Material.BEACON || !site.original().equals(site.village().beacon)
                || !site.purchase().equals(marker(block.getState(false)))
                || site.placement() == null || !(block.getState(false) instanceof Beacon beacon)
                || !site.placement().equals(beacon.getPersistentDataContainer()
                        .get(FoundingBeacons.BEACON_KEY, PersistentDataType.STRING)))) {
            error = "This replacement beacon has changed. Right-click it to open a new confirmation.";
        }
        if (error == null && (player.getWorld() != block.getWorld()
                || player.getEyeLocation().distanceSquared(new Location(block.getWorld(),
                        block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5)) > 36)) {
            error = "Move closer to the replacement beacon and right-click it to confirm.";
        }
        if (error != null) player.sendMessage(error);
        return error == null;
    }

    private String ownerError(Player player, Village village) {
        if (village == null || store.village(village.id) != village
                || !village.owner.equals(player.getUniqueId()) || store.registeredVillage(player.getUniqueId()) != village) {
            return "Only the faction owner can move this capital beacon.";
        }
        return player.isOnline() && player.getGameMode() == GameMode.SURVIVAL
                ? null : "Switch to Survival to move your capital beacon.";
    }

    private String placementError(Player player, Village village, String token, Block block) {
        String error = ownerError(player, village);
        if (error != null) return error;
        if (token == null || !token.equals(village.beaconMoveToken)) return "This replacement beacon is no longer valid.";
        if (block.getWorld().getEnvironment() != World.Environment.NORMAL || block.getY() < minY) {
            return "Place in your own Overworld plot at Y " + minY + " or higher.";
        }
        if (store.claimAt(block) != village) return "Place the replacement beacon in a plot owned by your civilization.";
        if (natural.reserved(block.getWorld(), Position.at(block).plot()) || natural.inBellProtection(block)) {
            return "This area is reserved for a natural village. Choose a spot farther from the village.";
        }
        for (int y = block.getY() + 1; y < block.getWorld().getMaxHeight(); y++) {
            if (!block.getWorld().getBlockAt(block.getX(), y, block.getZ()).isEmpty()) {
                return "Clear the blocks above your replacement beacon so it has open sky.";
            }
        }
        return null;
    }

    static String marker(BlockState state) {
        return state instanceof Beacon beacon ? beacon.getPersistentDataContainer().get(MOVE_KEY, PersistentDataType.STRING) : null;
    }

    private Village village(String token) {
        if (token == null) return null;
        try {
            return store.village(UUID.fromString(token.split("/", 2)[0]));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
