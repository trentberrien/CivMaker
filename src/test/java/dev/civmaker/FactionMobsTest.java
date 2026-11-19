package dev.civmaker;

import dev.civmaker.CivStore.Village;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactionMobsTest {
    private CivMaker plugin;
    private CivStore store;
    private FactionMobs mobs;
    private EntityProtection protection;
    private Village home;
    private Village enemy;
    private Player player;
    private Location location;
    private Mob mob;
    private Map<NamespacedKey, String> data;

    @BeforeEach void setup() {
        plugin = mock(CivMaker.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("name-filter.additional-blocked-terms", java.util.List.of("Forbidden"));
        when(plugin.getConfig()).thenReturn(config);
        store = mock(CivStore.class);
        mobs = new FactionMobs(plugin, store);
        protection = new EntityProtection(store, mock(NaturalVillages.class), mock(PillagerGuards.class),
                mock(DefenderGolems.class), mobs);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        location = new Location(world, 0, 250, 0);
        var beacon = new CivStore.Position(world.getUID(), 0, 80, 0);
        home = new Village("Home", UUID.randomUUID(), beacon);
        enemy = new Village("Enemy", UUID.randomUUID(), beacon);
        when(store.village(home.id)).thenReturn(home);
        when(store.village(enemy.id)).thenReturn(enemy);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(store.memberOf(player.getUniqueId())).thenReturn(home);
        when(store.claimAt(location)).thenReturn(home);
        held(Material.AIR);
        mount(Horse.class, EntityType.HORSE);
    }

    private void mount(Class<? extends Mob> type, EntityType entityType) {
        mob = mock(type);
        when(mob.getType()).thenReturn(entityType);
        when(mob.getLocation()).thenReturn(location);
        when(mob.isValid()).thenReturn(true);
        when(player.getVehicle()).thenReturn(mob);
        data = new HashMap<>();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(mob.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> data.get(call.getArgument(0)));
        when(pdc.getOrDefault(any(), eq(PersistentDataType.STRING), anyString()))
                .thenAnswer(call -> data.getOrDefault(call.getArgument(0), call.getArgument(2)));
        when(pdc.has(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> data.containsKey(call.getArgument(0)));
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(pdc).set(any(), eq(PersistentDataType.STRING), anyString());
        doAnswer(call -> { data.remove(call.getArgument(0)); return null; }).when(pdc).remove(any(NamespacedKey.class));
    }

    private void held(Material material) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(item);
    }

    private void tame(String... name) {
        String[] args = new String[name.length + 1];
        args[0] = "tame";
        System.arraycopy(name, 0, args, 1, name.length);
        mobs.tame(player, args);
    }

    private String label() {
        var capture = ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(mob, atLeastOnce()).customName(capture.capture());
        return PlainTextComponentSerializer.plainText().serialize(capture.getValue());
    }

    @Test void claimingPersistsFactionOwnershipAndAVisibleFilteredMultiwordName() {
        tame("Silver", "Wind");
        assertSame(home, mobs.owner(mob));
        assertEquals("Silver Wind [Home]", label());
        verify(mob).setCustomNameVisible(true);
        verify(mob).setPersistent(true);
        verify(mob).setRemoveWhenFarAway(false);
        verify((Tameable) mob).setTamed(true);
        assertSame(home, new FactionMobs(plugin, store).owner(mob));
    }

    @Test void defaultNamesAndFactionRenamesRefreshOnReload() {
        mount(HappyGhast.class, EntityType.HAPPY_GHAST);
        tame();
        assertEquals("Happy Ghast [Home]", label());
        home.name = "New Home";
        new FactionMobs(plugin, store).track(mob);
        assertEquals("Happy Ghast [New Home]", label());
    }

    @ParameterizedTest @ValueSource(strings = {"Forbidden", "f.u.c.k", "Bad§Name", "x", "VeryLongMobNameThatExceeds24Characters"})
    void rejectedNamesDoNotChangeOwnershipOrLabels(String name) {
        assertThrows(IllegalArgumentException.class, () -> tame(name));
        assertTrue(data.isEmpty());
        verify(mob, never()).customName(any());
    }

    @Test void contentFilterRejectionsPreserveExistingOwnerAndName() {
        tame("Silver Wind");
        when(store.memberOf(player.getUniqueId())).thenReturn(enemy);
        when(store.claimAt(location)).thenReturn(enemy);
        assertEquals(FactionNames.CONTENT_ERROR,
                assertThrows(IllegalArgumentException.class, () -> tame("Forbidden")).getMessage());
        assertSame(home, mobs.owner(mob));
        assertEquals("Silver Wind [Home]", label());
    }

    @Test void commandRequiresFactionRiddenMobAndBothRiderAndMobInOwnClaimEvenDuringRaids() {
        when(store.memberOf(player.getUniqueId())).thenReturn(null);
        assertThrows(IllegalArgumentException.class, this::tame);
        when(store.memberOf(player.getUniqueId())).thenReturn(home);
        when(player.getVehicle()).thenReturn(null);
        assertThrows(IllegalArgumentException.class, this::tame);
        when(player.getVehicle()).thenReturn(mock(Boat.class));
        assertThrows(IllegalArgumentException.class, this::tame);
        when(player.getVehicle()).thenReturn(mob);
        when(store.activeWindow(anyLong())).thenReturn(new CivStore.Window(1, Long.MAX_VALUE));
        when(store.claimAt(location)).thenReturn(enemy);
        assertThrows(IllegalArgumentException.class, this::tame);
        when(store.claimAt(location)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, this::tame);
        when(store.claimAt(location)).thenReturn(home);
        Location outside = new Location(location.getWorld(), 32, 250, 0);
        when(player.getLocation()).thenReturn(outside);
        assertThrows(IllegalArgumentException.class, this::tame);
        assertTrue(data.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"HORSE", "DONKEY", "CAMEL", "HAPPY_GHAST", "PIG", "STRIDER"})
    void ownerCanRemountInEnemyTerritoryAndForeignRidersAreBlockedEverywhere(String type) {
        switch (type) {
            case "HORSE" -> mount(Horse.class, EntityType.HORSE);
            case "DONKEY" -> mount(Donkey.class, EntityType.DONKEY);
            case "CAMEL" -> mount(Camel.class, EntityType.CAMEL);
            case "HAPPY_GHAST" -> mount(HappyGhast.class, EntityType.HAPPY_GHAST);
            case "PIG" -> mount(Pig.class, EntityType.PIG);
            case "STRIDER" -> mount(Strider.class, EntityType.STRIDER);
        }
        tame();
        when(store.claimAt(location)).thenReturn(enemy);
        assertRideAllowed(true);
        when(store.memberOf(player.getUniqueId())).thenReturn(null);
        assertRideAllowed(false);
        when(store.claimAt(location)).thenReturn(null);
        assertRideAllowed(false);
        when(store.claimAt(location)).thenReturn(home);
        when(store.memberOf(player.getUniqueId())).thenReturn(enemy);
        assertRideAllowed(false);
    }

    private void assertRideAllowed(boolean expected) {
        var mount = new EntityMountEvent(player, mob);
        protection.mount(mount);
        assertEquals(!expected, mount.isCancelled());
        var interact = new PlayerInteractEntityEvent(player, mob, EquipmentSlot.HAND);
        protection.interact(interact);
        assertEquals(!expected, interact.isCancelled());
    }

    @Test void enemyCanRideAndReclaimAMobLeftInTheirClaimWithoutRaidHours() {
        tame("Silver", "Wind");
        when(store.memberOf(player.getUniqueId())).thenReturn(enemy);
        when(store.claimAt(location)).thenReturn(enemy);
        assertRideAllowed(true);
        tame();
        assertSame(enemy, mobs.owner(mob));
        assertEquals("Silver Wind [Enemy]", label());
        when(store.memberOf(player.getUniqueId())).thenReturn(home);
        assertRideAllowed(false);
    }

    @Test void raidsAllowEnemyMountingWithoutOwnershipTransferButRespectShieldsAndRaidRestrictions() {
        tame();
        when(store.memberOf(player.getUniqueId())).thenReturn(enemy);
        when(store.activeWindow(anyLong())).thenReturn(new CivStore.Window(1, Long.MAX_VALUE));
        when(store.raidCovers(any())).thenReturn(true);
        assertRideAllowed(true);
        assertSame(home, mobs.owner(mob));
        assertThrows(IllegalArgumentException.class, this::tame);
        when(store.raidRestricted(player.getUniqueId())).thenReturn(true);
        assertRideAllowed(false);
        when(store.raidRestricted(player.getUniqueId())).thenReturn(false);
        when(store.raidCovers(any())).thenReturn(false);
        assertRideAllowed(false);
        when(store.activeWindow(anyLong())).thenReturn(null);
        assertRideAllowed(false);
    }

    @Test void vanillaNameTagsCannotReplaceTheFactionLabelOrBypassTheFilter() {
        tame();
        held(Material.NAME_TAG);
        var event = new PlayerInteractEntityEvent(player, mob, EquipmentSlot.HAND);
        protection.interact(event);
        assertTrue(event.isCancelled());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disbandingReleasesLoadedAndUnloadedMobs(boolean unloaded) {
        tame("Silver Wind");
        when(store.village(home.id)).thenReturn(null);
        if (unloaded) new FactionMobs(plugin, store).track(mob);
        else mobs.refresh();
        assertNull(mobs.owner(mob));
        assertTrue(data.isEmpty());
        assertEquals("Silver Wind", label());
    }
}
