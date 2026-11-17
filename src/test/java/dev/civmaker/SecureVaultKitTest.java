package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecureVaultKitTest {
    @ParameterizedTest
    @EnumSource(value = Material.class, mode = EnumSource.Mode.MATCH_ANY,
            names = {"CHEST", "TRAPPED_CHEST", "BARREL", ".*SHULKER_BOX"})
    void onlyChestsCanBeSecuredAndRejectedUsesKeepTheKit(Material material) {
        CivStore store = mock(CivStore.class);
        RaidEquipment equipment = mock(RaidEquipment.class);
        Protection protection = mock(Protection.class);
        RaidTools tools = new RaidTools(mock(CivMaker.class), store, equipment, protection,
                mock(VaultDoors.class), mock(PillagerGuards.class), mock(DefenderGolems.class),
                mock(SettlementVaults.class));
        Player player = mock(Player.class);
        UUID owner = UUID.randomUUID();
        Village village = new Village("Test", owner, new Position(UUID.randomUUID(), 0, 80, 0));
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(store.memberOf(owner)).thenReturn(village);
        Block target = mock(Block.class);
        when(target.getType()).thenReturn(material);
        when(store.protectedClaimAt(target)).thenReturn(village);

        boolean chest = material == Material.CHEST || material == Material.TRAPPED_CHEST;
        Container storage = chest ? mock(Chest.class)
                : material == Material.BARREL ? mock(Barrel.class) : mock(ShulkerBox.class);
        when(equipment.storageAt(target)).thenReturn(storage);
        Inventory contents = mock(Inventory.class);
        when(contents.getHolder()).thenReturn(storage);
        when(storage.getBlock()).thenReturn(target);
        when(storage.getInventory()).thenReturn(contents);
        when(equipment.reinforce(storage)).thenReturn(true);

        ItemStack kit = mock(ItemStack.class);
        when(kit.getType()).thenReturn(Material.IRON_INGOT);
        when(kit.getAmount()).thenReturn(2);
        when(equipment.identify(kit)).thenReturn(RaidEquipment.Tool.VAULT);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(kit);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                kit, target, BlockFace.UP, EquipmentSlot.HAND);

        tools.use(event);

        assertTrue(event.isCancelled());
        if (chest) {
            verify(equipment).reinforce(storage);
            verify(kit).setAmount(1);
            verify(inventory).setItemInMainHand(kit);
        } else {
            verify(equipment, never()).reinforce(any());
            verify(kit, never()).setAmount(anyInt());
            verify(inventory, never()).setItemInMainHand(any());
            verify(player).sendMessage("Use the kit on a chest or trapped chest in your own village.");
        }
    }
}
