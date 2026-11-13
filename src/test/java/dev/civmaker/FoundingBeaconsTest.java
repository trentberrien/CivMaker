package dev.civmaker;

import com.destroystokyo.paper.event.block.BeaconEffectEvent;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FoundingBeaconsTest {
    @TempDir Path directory;
    private CivStore store;
    private FoundingBeacons beacons;
    private World world;
    private Block capital;
    private Village village;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        beacons = new FoundingBeacons(plugin, store, mock(Wallets.class), mock(NaturalVillages.class));
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        capital = block(0, true);
        village = new Village("Test", UUID.randomUUID(), Position.at(capital));
        store.addVillage(village);
    }

    private Block block(int x, boolean founding) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(80);
        when(block.getType()).thenReturn(Material.BEACON);
        Beacon state = mock(Beacon.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(block.getState(false)).thenReturn(state);
        when(state.getPersistentDataContainer()).thenReturn(data);
        when(data.get(FoundingBeacons.BEACON_KEY, PersistentDataType.STRING))
                .thenReturn(founding ? "placement-token" : null);
        return block;
    }

    private void checkEffects(Block block, boolean locked) {
        BeaconEffectEvent effect = mock(BeaconEffectEvent.class);
        when(effect.getBlock()).thenReturn(block);
        beacons.effect(effect);
        PlayerChangeBeaconEffectEvent configure = mock(PlayerChangeBeaconEffectEvent.class);
        when(configure.getBeacon()).thenReturn(block);
        beacons.configure(configure);
        verify(effect, times(locked ? 1 : 0)).setCancelled(true);
        verify(configure, times(locked ? 1 : 0)).setCancelled(true);
    }

    @Test void baseCapitalKeepsItsPerksWhileVanillaBeaconPowersStayLocked() {
        checkEffects(capital, true);
        assertSame(village, store.protectedClaimAt(capital));
        assertTrue(store.isClaimAnchor(village, capital));
        assertEquals(100, village.points());
        assertEquals(100, village.capitalExpansionLimit());
        assertTrue(village.canManage(village.owner));
    }

    @Test void upgradedCapitalAllowsVanillaEffectsAndEffectSelectionAfterReload() throws Exception {
        village.expandedClaims = true;
        store.save();
        CivMaker plugin = mock(CivMaker.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        beacons = new FoundingBeacons(plugin, new CivStore(directory), mock(Wallets.class), mock(NaturalVillages.class));
        checkEffects(capital, false);
    }

    @Test void ordinaryBeaconsWorkAndUnusedFoundingBeaconsStayLocked() {
        checkEffects(block(1, false), false);
        checkEffects(block(2, true), true);
        village.expandedClaims = true;
        checkEffects(block(2, true), true);
    }

    @Test void legacyUnmarkedCapitalAlsoRequiresTheUpgrade() {
        capital = block(0, false);
        checkEffects(capital, true);
        village.expandedClaims = true;
        checkEffects(capital, false);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void vanillaBeaconScreenRequiresAnUpgrade(boolean upgraded) {
        village.expandedClaims = upgraded;
        Player player = mock(Player.class);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getClickedBlock()).thenReturn(capital);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        beacons.interact(event);
        verify(event, times(upgraded ? 0 : 1)).setCancelled(true);
    }

    @Test void disbandingLocksTheReleasedFoundingBeaconAgain() {
        village.expandedClaims = true;
        store.disband(village);
        checkEffects(capital, true);
    }
}
