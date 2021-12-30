package com.trentberrien.civmaker.clans;

import com.trentberrien.civmaker.villages.Village;

import org.bukkit.ChatColor;

import java.util.*;

public class Clan {

    private String name;
    private String chatTag;
    private ChatColor chatTagColor;
    private List<ClanMember> members;
    private List<Village> village;
    private double clanBalance;
    private HashMap<String, Double> playerContributions;

    public Clan(String name, String chatTag, ChatColor chatTagColor, List<ClanMember> members, double clanBalance) {
        this.name = name;
        this.chatTag = chatTag;
        this.chatTagColor = chatTagColor;
        this.members = members;
        this.clanBalance = clanBalance;
    }

    public Clan(String name) {
        this.name = name;
        this.chatTag = chatTag;
        this.chatTagColor = ChatColor.WHITE;
        this.members = new ArrayList<>();
    }

    public String getName() {
        return this.name;
    }

    public String getChatTag() {
        return this.chatTag;
    }

    public ChatColor getChatTagColor() {
        return this.chatTagColor;
    }

    public List<ClanMember> getMembers() {
        return this.members;
    }

    public double getClanBalance() {
        return this.clanBalance;
    }

    public void setClanName(String name) {
        this.name = name;
    }

    public void setChatTag(String tag) {
        this.chatTag = tag;
    }

    public void setChatTagColor(ChatColor color) {
        this.chatTagColor = color;
    }

    public List<Village> getVillages() {
        return this.village;
    }

    public boolean addMember(ClanMember member) {
        if (this.findClanMember(member.getName()) == -1) {
            this.members.add(member);
            return true;
        } else {
            return false;
        }
    }

    public int findClanMember(String name) {
        // sort by name so binary search will work correctly
        Collections.sort(this.members, Comparator.comparing(ClanMember::getName));

        int low = 0;
        int high = this.members.size()-1;
        int mid = (low + high) / 2;

        while (low <= high) {
            if (this.members.get(mid).getName().equals(name)) {
                return mid;
            } else if (this.members.get(mid).getName().compareTo(name) < 0) {
                high = mid - 1;
            } else if (this.members.get(mid).getName().compareTo(name) > 0) {
                low = mid + 1;
            }
            mid = (low + high) / 2;
        }
        return -1;
    }

    public void addAmountToBalance(double amount, String name) {
        if (!playerContributions.containsKey(name)) {
            playerContributions.put(name, amount);
        } else {
            playerContributions.put(name, playerContributions.get(name) + amount);
        }
        this.clanBalance += amount;
    }
    public double removeAmountFromBalance(double amount, String name) {
        if (!playerContributions.containsKey(name)) {
            return 0;
        } else {
            if (playerContributions.get(name) - amount >= 0) {
                playerContributions.put(name, playerContributions.get(name) - amount);
            } else {
                return 0;
            }
        }
        this.clanBalance -= amount;
        return amount;
    }

    public double getPlayerContribution(String name) {
        if (playerContributions.containsKey(name)) {
            return playerContributions.get(name);
        } else {
            return 0;
        }
    }

}
