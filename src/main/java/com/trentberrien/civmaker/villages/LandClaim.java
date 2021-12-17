package com.trentberrien.civmaker.villages;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;

public class LandClaim {

    public Location corner1;
    public Location corner2;
    public Location corner3;
    public Location corner4;

    /*
    Claims are always square, but they can be overlapped/connected
    1-------2
    |       |
    |       |
    |       |
    3-------4
     */

    public LandClaim(Block block) {
        Location corner1 = new Location(block.getWorld(), block.getChunk().getX(), 0, block.getChunk().getZ());
        Location corner2 = new Location(block.getWorld(), block.getChunk().getX()+16, 0, block.getChunk().getZ());
        Location corner3 = new Location(block.getWorld(), block.getChunk().getX(), 0, block.getChunk().getZ()+16);
        Location corner4 = new Location(block.getWorld(), block.getChunk().getX()+16, 0, block.getChunk().getZ()+16);

        this.corner1 = corner1;
        this.corner2 = corner2;
        this.corner3 = corner3;
        this.corner4 = corner4;
    }

    public LandClaim(String claimAsString) {
        String[] stringCoords = claimAsString.split(";");
        Location[] locs = new Location[4];

        int i = 0;
        for (String coord : stringCoords) {
            String[] coords = coord.split(",");
            double x = Double.parseDouble(coords[0]);
            double z = Double.parseDouble(coords[1]);
            locs[i] = new Location(Bukkit.getWorld("world"), x, 0, z);
        }
        this.corner1 = locs[0];
        this.corner2 = locs[1];
        this.corner3 = locs[2];
        this.corner4 = locs[3];
    }

    public boolean locationIsInsideClaim(Location loc) {
        return loc.getX() >= corner1.getX() && loc.getZ() >= corner1.getZ() && loc.getX() <= corner4.getX() && loc.getZ() <= corner4.getZ();
    }

    public boolean isBorderingClaim(LandClaim claim, Location playerLocation) {
        if (!this.locationIsInsideClaim(playerLocation)) {
            if (((this.corner1.getX() + 1 == claim.corner3.getX() || this.corner1.getX() - 1 == claim.corner3.getX()) && this.corner1.getZ() == claim.corner3.getZ()) || ((this.corner1.getZ() + 1 == claim.corner3.getZ() || this.corner1.getZ() - 1 == claim.corner3.getZ()) && this.corner1.getX() == claim.corner3.getX())) {
                return true;
            }
        }
        return false;
    }

    public String toString() {
        return corner1.getX()+","+corner1.getZ()+";"+corner2.getX()+","+corner2.getZ()+";"+corner3.getX()+","+corner3.getZ()+";"+corner4.getX()+","+corner4.getZ();
    }

}
