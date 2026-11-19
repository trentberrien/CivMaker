package dev.civmaker;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HarnessDurabilityBarTest {
    private MockedStatic<Bukkit> bukkit;
    private RaidEquipment equipment;
    private Player player;
    private ItemStack harness;
    private HarnessDurabilityBar bar;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLevel()).thenReturn(15);
        when(player.getExp()).thenReturn(.3f);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        HappyGhast ghast = mock(HappyGhast.class);
        when(player.getVehicle()).thenReturn(ghast);
        var gear = mock(EntityEquipment.class);
        when(ghast.getEquipment()).thenReturn(gear);
        harness = mock(ItemStack.class);
        when(gear.getItem(EquipmentSlot.BODY)).thenReturn(harness);
        equipment = mock(RaidEquipment.class);
        when(equipment.harnessRemaining(harness)).thenReturn(1800);
        bar = new HarnessDurabilityBar(equipment);
    }

    @AfterEach void cleanup() { bukkit.close(); }

    @Test void fullHalfAndEmptyDurabilityFillTheBarWithoutChangingXp() {
        bar.refresh();
        when(equipment.harnessRemaining(harness)).thenReturn(900);
        bar.refresh();
        when(equipment.harnessRemaining(harness)).thenReturn(0);
        bar.refresh();
        var progress = ArgumentCaptor.forClass(Float.class);
        verify(player, times(3)).sendExperienceChange(progress.capture(), eq(15));
        assertEquals(1f, progress.getAllValues().get(0), .000001f);
        assertEquals(.5f, progress.getAllValues().get(1), .000001f);
        assertEquals(0f, progress.getAllValues().get(2), .000001f);
        verify(player, never()).setExp(anyFloat());
        verify(player, never()).setLevel(anyInt());
        verify(player, never()).setTotalExperience(anyInt());
    }

    @Test void dismountRestoresCurrentXpIncludingGainsWhileRiding() {
        bar.refresh();
        when(player.getExp()).thenReturn(.8f);
        when(player.getLevel()).thenReturn(19);
        var event = mock(EntityDismountEvent.class);
        when(event.getEntity()).thenReturn(player);
        bar.dismount(event);
        verify(player).sendExperienceChange(.8f, 19);
        when(player.getVehicle()).thenReturn(null);
        bar.refresh();
        verify(player, times(1)).sendExperienceChange(.8f, 19);
    }

    @Test void removingHarnessRestoresXpOnceAndOrdinaryHarnessesLeaveXpAlone() {
        bar.refresh();
        when(equipment.harnessRemaining(harness)).thenReturn(-1);
        bar.refresh();
        bar.refresh();
        verify(player, times(1)).sendExperienceChange(.3f, 15);
        clearInvocations(player);
        bar.refresh();
        verify(player, never()).sendExperienceChange(anyFloat(), anyInt());
    }

    @Test void swappingOrRepairingHarnessUpdatesItsDisplayedDurability() {
        when(equipment.harnessRemaining(harness)).thenReturn(180);
        bar.refresh();
        when(equipment.harnessRemaining(harness)).thenReturn(1800);
        bar.refresh();
        var progress = ArgumentCaptor.forClass(Float.class);
        verify(player, times(2)).sendExperienceChange(progress.capture(), eq(15));
        assertEquals(.1f, progress.getAllValues().get(0), .000001f);
        assertEquals(1f, progress.getAllValues().get(1), .000001f);
    }

    @Test void deathOrCreativeModeStopsTheDisplay() {
        bar.refresh();
        when(player.isDead()).thenReturn(true);
        bar.refresh();
        verify(player).sendExperienceChange(.3f, 15);
        when(player.isDead()).thenReturn(false);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        clearInvocations(player);
        bar.refresh();
        verify(player, never()).sendExperienceChange(anyFloat(), anyInt());
    }

    @Test void disableRestoresXpAndDisconnectedPlayersAreForgotten() {
        bar.refresh();
        bar.clear();
        verify(player).sendExperienceChange(.3f, 15);
        bar.refresh();
        var quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        bar.quit(quit);
        clearInvocations(player);
        bar.clear();
        verify(player, never()).sendExperienceChange(anyFloat(), anyInt());
    }

    @Test void heartbeatKeepsIdleBarVisibleWithoutAVisibleProgressChange() {
        for (int remaining : new int[]{0, 1, 600, 900, 1799, 1800}) {
            float normal = HarnessDurabilityBar.displayProgress(remaining, false);
            float heartbeat = HarnessDurabilityBar.displayProgress(remaining, true);
            assertNotEquals(normal, heartbeat);
            assertTrue(heartbeat >= 0 && heartbeat <= 1);
            assertEquals(normal, heartbeat, .000001f);
            assertEquals(Math.min(182, (int) (normal * 183)), Math.min(182, (int) (heartbeat * 183)));
        }
    }
}
