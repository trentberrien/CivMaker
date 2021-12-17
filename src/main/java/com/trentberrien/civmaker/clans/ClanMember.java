package com.trentberrien.civmaker.clans;

import com.trentberrien.civmaker.ServerData;
import com.trentberrien.civmaker.ServerProperties;
import com.trentberrien.civmaker.villages.Village;

public class ClanMember {

    private String name;
    private String UUID;
    private double carryBalance;
    private int kills;
    private int deaths;

    public ClanMember(String name, String UUID, double carryBalance, int kills, int deaths) {
        this.name = name;
        this.UUID = UUID;
        this.carryBalance = carryBalance;
        this.kills = kills;
        this.deaths = deaths;
    }

    public String getName() {
        return this.name;
    }

    public String getUUID() {
        return this.UUID;
    }

    public double getCarryBalance() {
        return this.carryBalance;
    }

    public int getKills() {
        return this.getKills();
    }

    public int getDeaths() {
        return this.getDeaths();
    }

    public int getKDR() {
        return this.kills / this.deaths;
    }

    public void addKill() {
        this.kills++;
    }

    public void addDeath() {
        this.deaths++;
    }

    public void addAmountToCarryBalance(double amount) {
        this.carryBalance += amount;
    }

    public boolean transferAmountToBank(double amount, Clan clan) {
        //Player must carry a certain percent of their total balance
        if (this.carryBalance - amount > (clan.getClanBalance() + this.carryBalance) * ServerProperties.MIN_BALANCE_CARRY_PERCENT) {
            this.carryBalance -= amount;
            clan.addAmountToBalance(amount, this.name);
            return true;
        } else {
            return false;
        }
    }

    public void transferAmountToCarry(double amount, Clan clan) {
        this.carryBalance += clan.removeAmountFromBalance(amount, this.name);
    }

    public boolean payPlayerAmount(double amount, ClanMember player) {
        if (this.carryBalance - amount > 0) {
            player.addAmountToCarryBalance(amount);
            this.carryBalance -= amount;
            return true;
        } else {
            return false;
        }
    }

}
