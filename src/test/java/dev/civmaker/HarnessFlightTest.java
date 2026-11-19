package dev.civmaker;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HarnessFlightTest {
    private final World world = mock(World.class);

    @Test void hoveringAndLookingAroundDoNotUseDurability() {
        HarnessFlight flight = new HarnessFlight();
        for (int tick = 0; tick < 144_000; tick++) {
            assertFalse(flight.advance(new Location(world, 10, 100, 10, tick % 360, 45), true));
        }
    }

    @Test void twoHoursOfMovingFlightMake7200WearAttempts() {
        HarnessFlight flight = new HarnessFlight();
        int attempts = 0;
        for (int tick = 0; tick <= 144_000; tick++) {
            if (flight.advance(new Location(world, tick * .1, 100, 0), true)) attempts++;
        }
        assertEquals(7200, attempts);
        assertEquals(7200, (RaidEquipment.HARNESS_DURABILITY - 1) * 4);
    }

    @Test void hoveringPreservesPartialSecondsAndVerticalMovementCounts() {
        HarnessFlight flight = new HarnessFlight();
        assertFalse(flight.advance(new Location(world, 0, 100, 0), true));
        for (int tick = 1; tick <= 10; tick++) {
            assertFalse(flight.advance(new Location(world, 0, 100 + tick * .1, 0), true));
        }
        for (int tick = 0; tick < 1000; tick++) {
            assertFalse(flight.advance(new Location(world, 0, 101, 0), true));
        }
        for (int tick = 1; tick < 10; tick++) {
            assertFalse(flight.advance(new Location(world, 0, 101 + tick * .1, 0), true));
        }
        assertTrue(flight.advance(new Location(world, 0, 102, 0), true));
    }

    @Test void groundedOrCreativeMovementTeleportsAndWorldChangesDoNotCount() {
        HarnessFlight flight = new HarnessFlight();
        for (int tick = 0; tick < 1000; tick++) {
            assertFalse(flight.advance(new Location(world, tick * .1, 100, 0), false));
        }
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(flight.advance(new Location(world, tick * 100, 100, 0), true));
        }
        World other = mock(World.class);
        assertFalse(flight.advance(new Location(other, 0, 100, 0), true));
        for (int tick = 1; tick < 20; tick++) {
            assertFalse(flight.advance(new Location(other, tick * .1, 100, 0), true));
        }
        assertTrue(flight.advance(new Location(other, 2, 100, 0), true));
    }

    @Test void upgradingPreservesWearFractionAndBrokenHarnesses() {
        assertEquals(0, RaidEquipment.rescaledHarnessDamage(0, 432));
        assertEquals(1800, RaidEquipment.rescaledHarnessDamage(431, 432));
        assertEquals(897, RaidEquipment.rescaledHarnessDamage(215, 432));
        assertEquals(123, RaidEquipment.rescaledHarnessDamage(123, 1801));
        assertEquals(0, RaidEquipment.rescaledHarnessDamage(0, 0));
        assertEquals(1800, RaidEquipment.rescaledHarnessDamage(Integer.MAX_VALUE, 432));
    }
}
