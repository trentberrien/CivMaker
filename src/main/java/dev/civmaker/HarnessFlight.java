package dev.civmaker;

import org.bukkit.Location;

/** Counts actual moving flight ticks, retaining partial seconds while hovering. */
final class HarnessFlight {
    private Location previous;
    private int movingTicks;

    boolean advance(Location current, boolean flying) {
        Location last = previous;
        previous = current.clone();
        if (!flying || last == null || !current.getWorld().equals(last.getWorld())) {
            return false;
        }
        double distance = current.distanceSquared(last);
        // Ignore tiny position jitter and teleports rather than charging them as flight.
        if (distance <= 0.000001 || distance > 64) {
            return false;
        }
        if (++movingTicks < 20) {
            return false;
        }
        movingTicks = 0;
        return true;
    }
}
