package dev.civmaker;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RadarInventoryTest {
    @Test void requiresTaggedRadarAnywhereInStorageOrOffhand() {
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        RaidEquipment equipment = new RaidEquipment(plugin);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack ordinary = item(Material.COMPASS, null);
        ItemStack radar = item(Material.COMPASS, "radar");
        ItemStack forged = item(Material.DIRT, "radar");
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{null, ordinary, forged});
        assertFalse(equipment.hasRadar(player));
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{null, ordinary, radar});
        assertTrue(equipment.hasRadar(player));
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{ordinary});
        when(inventory.getItemInOffHand()).thenReturn(radar);
        assertTrue(equipment.hasRadar(player));
        when(inventory.getItemInOffHand()).thenReturn(null);
        assertFalse(equipment.hasRadar(player));
        when(radar.getAmount()).thenReturn(0);
        when(inventory.getItemInOffHand()).thenReturn(radar);
        assertFalse(equipment.hasRadar(player));
    }

    private ItemStack item(Material material, String tag) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenReturn(1);
        var data = mock(PersistentDataContainer.class);
        when(item.getPersistentDataContainer()).thenReturn(data);
        when(data.get(NamespacedKey.fromString("civmaker:raid_item_v1"), PersistentDataType.STRING)).thenReturn(tag);
        return item;
    }
}
