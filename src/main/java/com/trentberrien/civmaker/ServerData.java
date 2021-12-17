package com.trentberrien.civmaker;

import com.trentberrien.civmaker.clans.Clan;
import com.trentberrien.civmaker.clans.ClanMember;
import com.trentberrien.civmaker.villages.LandClaim;
import com.trentberrien.civmaker.villages.Village;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.sql.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ServerData {

    //SERVER PROPERTIES
    //List as variables here
    //Don't include them in the constructor

    private HashMap<Material, Double> itemPrices;
    private List<Clan> activeClans;

    private File dataFolder;


    public ServerData(File dataFolder) {
        this.dataFolder = dataFolder;
        this.loadServerData();
    }

    public void loadServerData() {
        //TODO: FINISH THIS WHEN IM NOT ABOUT TO GO SEE SPIDER MAN
        // THIS METHOD IS NOT FINISHED
        // DO NOT ATTEMPT TO USE THIS METHOD IT WILL LIKELY BREAK EVERYTHING UNTIL THIS COMMENT IS REMOVED
        if (!this.dataFolder.exists()) {
            this.dataFolder.mkdir();
        }

        File clansFile = new File(this.dataFolder, "CivClans.yml");

        try {
            if (!clansFile.exists()) clansFile.createNewFile();
            FileConfiguration clansYAMLFile = YamlConfiguration.loadConfiguration(clansFile);

            List<String> clanNames = new ArrayList<>();
            for (Clan c : this.activeClans) {
                clanNames.add(c.getName());
            }

            clansYAMLFile.set("clanNames", clanNames);

            for (String name : clanNames) {
                File currentClanFile = new File(this.dataFolder, name+".yml");
                if (!currentClanFile.exists()) currentClanFile.createNewFile();
                FileConfiguration currClanYAMLFile = YamlConfiguration.loadConfiguration(currentClanFile);

                for (Clan c : this.activeClans) {

                    List<String> currClanMembers = new ArrayList<>();
                    for (ClanMember cm : c.getMembers()) {
                        currClanMembers.add(cm.getName());
                    }


                }

                List<String> currClanMembers = currClanYAMLFile.getStringList("members");
                List<ClanMember> members = new ArrayList<>();
                for (String member : currClanMembers) {
                    ClanMember cMember = new ClanMember(member, currClanYAMLFile.getString(name+".uuid"), currClanYAMLFile.getDouble(name+".carryBalance"), currClanYAMLFile.getInt(name+".kills"), currClanYAMLFile.getInt(name+".deaths"));
                    members.add(cMember);
                }
                Clan currClan = new Clan(currClanYAMLFile.getString("name"), currClanYAMLFile.getString("chatTag"), members, currClanYAMLFile.getDouble("balance"));
                List<Village> clanVillages = new ArrayList<>();
                if (currClanYAMLFile.contains("villages")) {
                    List<String> villageNames = currClanYAMLFile.getStringList("villages");

                    for (String vName : villageNames) {
                        String center = currClanYAMLFile.getString(vName+".center");
                        List<String> landClaims = currClanYAMLFile.getStringList(vName+".landClaims");
                        List<LandClaim> currClaims = new ArrayList<>();
                        for (String claim : landClaims) {
                            currClaims.add(new LandClaim((claim)));
                        }

                        Village currVillage = new Village(vName, currClaims, center);
                        clanVillages.add(currVillage);
                    }
                }

                this.activeClans.add(currClan);
            }
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }
    }

    public void saveServerData() {
        if (!this.dataFolder.exists()) {
            this.dataFolder.mkdir();
        }

        File clansFile = new File(this.dataFolder, "CivClans.yml");

        try {
            if (!clansFile.exists()) clansFile.createNewFile();
            FileConfiguration clansYAMLFile = YamlConfiguration.loadConfiguration(clansFile);

            List<String> clanNames = clansYAMLFile.getStringList("clanNames");

            for (String name : clanNames) {
                File currentClanFile = new File(this.dataFolder, name+".yml");
                if (!currentClanFile.exists()) currentClanFile.createNewFile();
                FileConfiguration currClanYAMLFile = YamlConfiguration.loadConfiguration(currentClanFile);

                List<String> currClanMembers = currClanYAMLFile.getStringList("members");
                List<ClanMember> members = new ArrayList<>();
                for (String member : currClanMembers) {
                    ClanMember cMember = new ClanMember(member, currClanYAMLFile.getString(name+".uuid"), currClanYAMLFile.getDouble(name+".carryBalance"), currClanYAMLFile.getInt(name+".kills"), currClanYAMLFile.getInt(name+".deaths"));
                    members.add(cMember);
                }
                Clan currClan = new Clan(currClanYAMLFile.getString("name"), currClanYAMLFile.getString("chatTag"), members, currClanYAMLFile.getDouble("balance"));
                List<Village> clanVillages = new ArrayList<>();
                if (currClanYAMLFile.contains("villages")) {
                    List<String> villageNames = currClanYAMLFile.getStringList("villages");

                    for (String vName : villageNames) {
                        String center = currClanYAMLFile.getString(vName+".center");
                        List<String> landClaims = currClanYAMLFile.getStringList(vName+".landClaims");
                        List<LandClaim> currClaims = new ArrayList<>();
                        for (String claim : landClaims) {
                            currClaims.add(new LandClaim((claim)));
                        }

                        Village currVillage = new Village(vName, currClaims, center);
                        clanVillages.add(currVillage);
                    }
                }

                this.activeClans.add(currClan);
            }
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }
    }

}
