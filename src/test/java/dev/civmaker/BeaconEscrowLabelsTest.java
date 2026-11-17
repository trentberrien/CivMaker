package dev.civmaker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BeaconEscrowLabelsTest {
    @TempDir Path directory;

    @Test void showsLiveValueAndLootStatusOnlyDuringRaids() throws Exception {
        CivStore store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        Block beacon = mock(Block.class);
        when(beacon.getType()).thenReturn(Material.BEACON);
        when(world.getBlockAt(0, 80, 0)).thenReturn(beacon);
        var village = new CivStore.Village("Capital", UUID.randomUUID(), new CivStore.Position(worldId, 0, 80, 0));
        store.addVillage(village);
        BeaconEscrow escrow = mock(BeaconEscrow.class);
        when(escrow.total(village)).thenReturn(BigInteger.valueOf(12345));
        TextDisplay display = mock(TextDisplay.class);
        when(display.isValid()).thenReturn(true);
        when(display.getLocation()).thenReturn(new Location(world, 0.5, 81.25, 0.5));
        AtomicReference<Component> text = new AtomicReference<>();
        doAnswer(call -> { text.set(call.getArgument(0)); return null; }).when(display).text(any(Component.class));
        when(display.text()).thenAnswer(call -> text.get());
        when(world.spawn(any(Location.class), eq(TextDisplay.class), org.mockito.ArgumentMatchers.<Consumer<TextDisplay>>any())).thenAnswer(call -> {
            call.<Consumer<TextDisplay>>getArgument(2).accept(display);
            return display;
        });
        BeaconLabels labels = new BeaconLabels(plugin, store);
        labels.beaconEscrow(escrow);
        labels.refresh();
        assertFalse(plain(text.get()).contains("Offline funds"));
        store.setRaidMode(CivStore.RaidMode.ON);
        labels.refresh();
        assertTrue(plain(text.get()).contains("Offline funds: $12,345"));
        assertTrue(plain(text.get()).contains("25%"));
        when(escrow.looted(eq(village), any())).thenReturn(true);
        when(escrow.total(village)).thenReturn(BigInteger.valueOf(9260));
        labels.refresh();
        assertTrue(plain(text.get()).contains("$9,260"));
        assertTrue(plain(text.get()).contains("Already looted"));
        village.shieldWindow = store.activeWindow(System.currentTimeMillis()).start();
        labels.refresh();
        assertTrue(plain(text.get()).contains("Capital protected"));
        store.setRaidMode(CivStore.RaidMode.OFF);
        labels.refresh();
        assertFalse(plain(text.get()).contains("Offline funds"));
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
}
