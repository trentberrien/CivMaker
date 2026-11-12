package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaidShieldsTest {
    @TempDir Path directory;
    private CivStore store;
    private RaidShields shields;
    private CivMaker plugin;
    private RaidEquipment equipment;
    private Village village;
    private YamlConfiguration config;
    private final long now = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli();

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        village = new Village("Ashford", UUID.randomUUID(), new Position(UUID.randomUUID(), 0, 80, 0));
        store.addVillage(village);
        plugin = mock(CivMaker.class);
        when(plugin.getName()).thenReturn("CivMaker");
        when(plugin.namespace()).thenReturn("civmaker");
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        equipment = mock(RaidEquipment.class);
        shields = new RaidShields(plugin, store, equipment);
    }

    private Window window(long start) {
        return new Window(start, start + 7_200_000);
    }

    @Test void reservesExactlyOneFutureWindowAndKeepsTheNoticeBoundary() throws Exception {
        Window tooSoon = window(now + RaidShields.NOTICE_MILLIS - 1);
        Window selected = window(now + RaidShields.NOTICE_MILLIS);
        Window later = window(now + 3 * RaidShields.NOTICE_MILLIS);
        store.windows().addAll(List.of(tooSoon, selected, later));
        assertEquals(selected, shields.reserve(village, now));
        assertFalse(store.shielded(village, tooSoon));
        assertTrue(store.shielded(village, selected));
        assertFalse(store.shielded(village, later));
        assertFalse(store.raidRestricted(village.owner, tooSoon));
        assertTrue(store.raidRestricted(village.owner, selected));
        assertFalse(store.raidRestricted(village.owner, later));
        store.save();
        CivStore restored = new CivStore(directory);
        assertTrue(restored.shielded(restored.village(village.id), selected));
        assertTrue(restored.raidRestricted(village.owner, selected));
        assertThrows(IllegalArgumentException.class, () -> shields.reserve(village, now));
    }

    @Test void dailySchedulesSelectTheFirstWindowWithTwentyFourHoursNotice() {
        store.setNightly(new NightlyRaids("20:00", "22:00", "UTC"));
        Window selected = shields.reserve(village, now);
        assertEquals(Instant.parse("2026-11-13T20:00:00Z").toEpochMilli(), selected.start());
    }

    @Test void failsWithoutEligibleScheduleOrDuringManualOverrideWithoutChangingState() {
        assertThrows(IllegalArgumentException.class, () -> shields.reserve(village, now));
        store.windows().add(window(now + RaidShields.NOTICE_MILLIS - 1));
        assertThrows(IllegalArgumentException.class, () -> shields.reserve(village, now));
        store.windows().add(window(now + 2 * RaidShields.NOTICE_MILLIS));
        store.setRaidMode(CivStore.RaidMode.ON);
        assertThrows(IllegalArgumentException.class, () -> shields.reserve(village, now));
        assertNull(village.shieldWindow);
        assertEquals(0, village.shieldUsedAt);
    }

    @Test void schedulingCooldownAndDisabledPurchasesStillApply() {
        store.windows().add(window(now + RaidShields.NOTICE_MILLIS));
        village.shieldUsedAt = now - 1;
        assertThrows(IllegalArgumentException.class, () -> shields.reserve(village, now));
        village.shieldUsedAt = 0;
        config.set("raid-protection.enabled", false);
        assertThrows(IllegalArgumentException.class, () -> shields.reserve(village, now));
        assertNull(village.shieldWindow);
    }

    @Test void failedUseKeepsStarAndSuccessfulUseConsumesOneAndAnnouncesTheWindow() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(village.owner);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack star = mock(ItemStack.class);
        when(inventory.getItemInMainHand()).thenReturn(star);
        when(equipment.identify(star)).thenReturn(RaidEquipment.Tool.RAID_SHIELD);
        when(star.getAmount()).thenReturn(1);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(star.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(), eq(PersistentDataType.STRING))).thenReturn(village.id.toString());
        Server server = mock(Server.class);
        when(player.getServer()).thenReturn(server);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getItem()).thenReturn(star);
        when(event.getPlayer()).thenReturn(player);
        shields.use(event);
        verify(star, never()).setAmount(anyInt());
        Window selected = window(System.currentTimeMillis() + 2 * RaidShields.NOTICE_MILLIS);
        store.windows().add(selected);
        shields.use(event);
        verify(star).setAmount(0);
        assertEquals(selected.start(), village.shieldWindow);
        verify(player).sendMessage(contains("Your faction cannot raid during that window"));
    }

    @Test void leavingKickingAndDisbandingCannotEvadeAnActiveRestrictionEvenAfterRestart() throws Exception {
        store.setRaidMode(CivStore.RaidMode.ON);
        Window active = store.activeWindow(System.currentTimeMillis());
        village.shieldWindow = active.start();
        UUID leaver = UUID.randomUUID();
        store.addMember(village, leaver);
        store.removeMember(village, leaver);
        Village other = new Village("Rivals", UUID.randomUUID(), new Position(village.beacon.world(), 32, 80, 0));
        store.addVillage(other);
        store.addMember(other, leaver);
        assertTrue(store.raidRestricted(leaver));
        store.disband(village);
        assertTrue(store.raidRestricted(village.owner));
        store.save();
        CivStore restored = new CivStore(directory);
        assertTrue(restored.raidRestricted(leaver));
        assertTrue(restored.raidRestricted(village.owner));
        restored.setRaidMode(CivStore.RaidMode.OFF);
        assertFalse(restored.raidRestricted(leaver));
        restored.setRaidMode(CivStore.RaidMode.ON);
        assertFalse(restored.raidRestricted(leaver));
    }
}
