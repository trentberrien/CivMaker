package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Village;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ProtectionTripwireTest {
    @TempDir Path directory;
    private CivStore store;
    private Protection protection;
    private World world;
    private Block block;
    private Player visitor;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        protection = new Protection(store, mock(RaidEquipment.class), mock(VaultDoors.class),
                mock(NaturalVillages.class), mock(DefenderGolems.class), mock(FarmStations.class));
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        store.addVillage(new Village("Ashford", UUID.randomUUID(), new Position(world.getUID(), 0, 80, 0)));
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(4);
        when(block.getY()).thenReturn(80);
        when(block.getZ()).thenReturn(4);
        when(block.getType()).thenReturn(Material.TRIPWIRE);
        visitor = mock(Player.class);
        when(visitor.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    private PlayerInteractEvent interaction(Action action) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(visitor);
        when(event.getAction()).thenReturn(action);
        return event;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void visitorsCanTriggerTripwiresInForeignClaimsOutsideRaids(boolean hasFaction) {
        if (hasFaction) {
            store.addVillage(new Village("Other", visitor.getUniqueId(), new Position(world.getUID(), 64, 80, 0)));
        }
        PlayerInteractEvent event = interaction(Action.PHYSICAL);

        protection.interact(event);

        verify(event, never()).setCancelled(true);
        verify(event, never()).setUseInteractedBlock(Event.Result.DENY);
    }

    @ParameterizedTest @ValueSource(strings = {"TRIPWIRE", "TRIPWIRE_HOOK"})
    void tripwireActivationDoesNotPermitBreakingOrRightClicking(Material material) {
        when(block.getType()).thenReturn(material);
        PlayerInteractEvent event = interaction(Action.RIGHT_CLICK_BLOCK);
        protection.interact(event);
        verify(event).setCancelled(true);

        BlockBreakEvent breaking = new BlockBreakEvent(block, visitor);
        protection.breakBlock(breaking);
        assertTrue(breaking.isCancelled());
    }

    @ParameterizedTest @ValueSource(strings = {"STONE_PRESSURE_PLATE", "OAK_PRESSURE_PLATE", "FARMLAND"})
    void otherPhysicalInteractionsRemainProtected(Material material) {
        when(block.getType()).thenReturn(material);
        PlayerInteractEvent event = interaction(Action.PHYSICAL);

        protection.interact(event);

        verify(event).setCancelled(true);
    }
}
