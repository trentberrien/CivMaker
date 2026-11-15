package dev.civmaker;

import dev.civmaker.CivStore.Village;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

final class FactionRenaming {
    private final CivStore store;
    private final FactionNames names;

    FactionRenaming(CivMaker plugin, CivStore store) {
        this.store = store;
        names = new FactionNames(plugin.getConfig().getStringList("name-filter.additional-blocked-terms"),
                plugin.getConfig().getStringList("name-filter.allowed-words"));
    }

    void open(Player player, Village village) {
        String error = accessError(player, village);
        if (error != null) {
            player.sendMessage(error);
            return;
        }
        prompt(player, village, village.name, null);
    }

    private void prompt(Player player, Village village, String initial, String error) {
        var input = DialogInput.text("name", Component.text("Faction name"))
                .initial(initial).maxLength(24).build();
        var submit = ActionButton.builder(Component.text("Rename faction"))
                .action(DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player clicked) {
                        String entered = response.getText("name");
                        String failure = rename(clicked, village, entered);
                        if (failure != null) {
                            if (accessError(clicked, village) == null) {
                                prompt(clicked, village, "", failure);
                            } else {
                                clicked.sendMessage(failure);
                            }
                        }
                    }
                }, ClickCallback.Options.builder().build())).build();
        var cancel = ActionButton.builder(Component.text("Cancel")).build();
        var rules = DialogBody.plainMessage(Component.text(FactionNames.FORMAT_ERROR
                + " No profanity, slurs, or extremist references. Names must be unique."));
        var base = DialogBase.builder(Component.text("Rename your faction"))
                .inputs(List.of(input))
                .body(error == null ? List.of(rules) : List.of(
                        DialogBody.plainMessage(Component.text(error, NamedTextColor.RED)), rules)).build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base).type(DialogType.confirmation(submit, cancel))));
    }

    String rename(Player player, Village village, String entered) {
        String error = accessError(player, village);
        if (error != null) {
            return error;
        }
        String name = entered == null ? "" : entered.strip();
        error = names.error(name);
        if (error != null) {
            return error;
        }
        for (Village other : store.villages()) {
            if (other != village && other.name.equalsIgnoreCase(name)) {
                return "That name is taken. Choose another name.";
            }
        }
        String previous = village.name;
        if (!previous.equals(name)) {
            village.name = name;
            store.dirty = true;
        }
        player.sendMessage("Your faction was renamed from " + previous + " to " + name + ".");
        return null;
    }

    private String accessError(Player player, Village village) {
        if (store.village(village.id) != village || store.registeredVillage(player.getUniqueId()) != village
                || !village.owner.equals(player.getUniqueId())) {
            return "Only the faction owner can rename this civilization.";
        }
        if (!player.isOnline() || player.getGameMode() != GameMode.SURVIVAL) {
            return "Switch to Survival to rename your faction.";
        }
        return inReach(player, village) ? null : "Move closer to your capital beacon and open its menu again.";
    }

    static boolean inReach(Player player, Village village) {
        var position = village.beacon;
        var world = player.getWorld();
        return world.getUID().equals(position.world())
                && world.isChunkLoaded(position.x() >> 4, position.z() >> 4)
                && world.getBlockAt(position.x(), position.y(), position.z()).getType() == Material.BEACON
                && player.getEyeLocation().distanceSquared(new Location(world,
                        position.x() + 0.5, position.y() + 0.5, position.z() + 0.5)) <= 36;
    }
}
