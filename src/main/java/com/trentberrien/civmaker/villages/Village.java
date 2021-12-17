package com.trentberrien.civmaker.villages;

import com.trentberrien.civmaker.clans.Clan;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.List;

public class Village {

    private String name;
    private List<LandClaim> landClaims;
    private Location villageCenter;

    public Village(String name, List<LandClaim> landClaims, String center) {
        this.name = name;
        this.landClaims = landClaims;
        this.villageCenter = new Location(Bukkit.getWorld("world"), Double.parseDouble(center.split(",")[0]), Double.parseDouble(center.split(",")[1]), Double.parseDouble(center.split(",")[2]));
    }

    public Village(String name, List<LandClaim> landClaims, Location center) {
        this.name = name;
        this.landClaims = landClaims;
        this.villageCenter = center;
    }

    public List<LandClaim> getLandClaims() {
        return this.landClaims;
    }

}
