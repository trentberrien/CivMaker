package dev.civmaker;

import org.bukkit.Material;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class HarnessRepairsTest {
    private HarnessRepairs repairs;
    private RaidEquipment equipment;
    private PrepareAnvilEvent event;
    private ItemStack harness;
    private ItemStack ingredient;
    private ItemStack result;
    private Damageable damage;
    private Damageable resultDamage;
    private AnvilView view;

    @BeforeEach void setup() {
        equipment = mock(RaidEquipment.class);
        repairs = new HarnessRepairs(equipment);
        event = mock(PrepareAnvilEvent.class);
        var inventory = mock(AnvilInventory.class);
        view = mock(AnvilView.class);
        when(event.getView()).thenReturn(view);
        when(event.getInventory()).thenReturn(inventory);
        harness = mock(ItemStack.class);
        ingredient = mock(ItemStack.class);
        when(ingredient.getType()).thenReturn(Material.GHAST_TEAR);
        when(ingredient.getAmount()).thenReturn(64);
        when(inventory.getItem(0)).thenReturn(harness);
        when(inventory.getItem(1)).thenReturn(ingredient);
        when(equipment.identify(harness)).thenReturn(RaidEquipment.Tool.HAPPY_HARNESS);
        damage = mock(Damageable.class);
        when(damage.getDamage()).thenReturn(1800);
        when(harness.getItemMeta()).thenReturn(damage);
        result = mock(ItemStack.class);
        when(result.clone()).thenReturn(result);
        when(harness.clone()).thenReturn(result);
        resultDamage = mock(Damageable.class);
        when(result.getItemMeta()).thenReturn(resultDamage);
    }

    @Test void oneTearRepairsFullyAndRetainsVanillaRenamingResult() {
        when(event.getResult()).thenReturn(result);
        repairs.prepare(event);
        verify(resultDamage).setDamage(0);
        verify(result).setItemMeta(resultDamage);
        verify(event).setResult(result);
        verify(view).setRepairItemCountCost(1);
        verify(view).setRepairCost(1);
        verify(harness, never()).setItemMeta(any());
        verify(ingredient, never()).setAmount(anyInt());
    }

    @Test void legacyHarnessWithoutVanillaRepairResultStillRepairs() {
        repairs.prepare(event);
        verify(event).setResult(result);
        verify(resultDamage).setDamage(0);
        verify(view).setRepairItemCountCost(1);
    }

    @Test void fullDurabilityDoesNotConsumeATear() {
        when(damage.getDamage()).thenReturn(0);
        repairs.prepare(event);
        verify(event).setResult(null);
        verify(view).setRepairCost(0);
        verify(view, never()).setRepairItemCountCost(anyInt());
    }

    @Test void legacyMembraneRepairsAreBlocked() {
        when(ingredient.getType()).thenReturn(Material.PHANTOM_MEMBRANE);
        repairs.prepare(event);
        verify(event).setResult(null);
        verifyNoInteractions(view);
    }

    @Test void ordinaryHarnessesAndElytraKeepVanillaBehavior() {
        when(equipment.identify(harness)).thenReturn(null);
        repairs.prepare(event);
        verify(event, never()).setResult(any());
        verifyNoInteractions(view);
    }
}
