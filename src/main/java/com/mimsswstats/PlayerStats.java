package com.mimsswstats;

import com.gmail.goosius.siegewar.objects.Siege;
import com.mimsswstats.SiegePerformance;
import com.mimsswstats.SiegeStats;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStats {
    private final String playerName;
    private int kills;
    private final Set<String> uniqueSiegeParticipations;
    private int deaths;
    private final Set<String> participatedSiegeIds; // Track unique siege participations
    private double totalDamage;
    private int totalSieges;
    private int totalWins;
    private int totalLosses;
    private final Set<String> participatedSieges; // Track unique siege participations
    private final List<SiegePerformance> recentPerformances;
    private int wins;
    private int losses;
    private ArrayList<SiegePerformance> lastPerformances;
    private ConcurrentHashMap<Siege, SiegeStats> siegeStats;

    public PlayerStats(String playerName) {
        this.playerName = playerName;
        this.kills = 0;
        this.totalSieges = 0;
        this.totalWins = 0;
        this.totalLosses = 0;
        this.participatedSiegeIds = new HashSet<>();
        this.participatedSieges = new HashSet<>();
        this.recentPerformances = new ArrayList<>();
        this.deaths = 0;
        this.uniqueSiegeParticipations = new HashSet<>();
        this.totalDamage = 0;
        this.lastPerformances = new ArrayList<>();
        this.siegeStats = new ConcurrentHashMap<>();
    }


    public void addKills(int kills) {
        this.kills += kills;
    }

    public void addDeaths(int deaths) {
        this.deaths += deaths;
    }

    public void addDamage(double damage) {
        this.totalDamage += damage;
    }

    public void addWin() {
        this.totalWins++;
    }

    public void addLoss() {
        this.totalLosses++;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getKills() {
        return kills;
    }

    public int getDeaths() {
        return deaths;
    }

    public double getTotalDamage() {
        return totalDamage;
    }

    public ArrayList<SiegePerformance> getLastPerformances() {
        return lastPerformances;
    }

    public ConcurrentHashMap<Siege, SiegeStats> getSiegeStats() {
        return siegeStats;
    }

    public void setTotalSieges(int totalSieges) {
        this.totalSieges = totalSieges;
    }

    public int getTotalLosses() {
        return totalLosses;
    }

    public int getTotalWins() {
        return totalWins;
    }

    public int getTotalSieges() {
        return totalSieges;
    }

    public void setTotalWins(int totalWins) {
        this.totalWins = totalWins;
    }

    public void setTotalLosses(int totalLosses) {
        this.totalLosses = totalLosses;
    }
    public void addLastPerformance(SiegePerformance performance) {
        recentPerformances.add(performance);
        if (recentPerformances.size() > 10) { // Keep only last 10 performances
            recentPerformances.remove(0);
        }
    }

    public void addSiegeParticipation(String siegeId) {
        if (uniqueSiegeParticipations.add(siegeId)) { // Returns true only if siegeId wasn't already present
            totalSieges++;
        }
    }

    public boolean hasParticipatedInSiege(String siegeId) {
        return uniqueSiegeParticipations.contains(siegeId);
    }
    public double getWinLossRatio() {
        return (wins + losses == 0) ? 0 : (double) wins / (wins + losses);
    }

    public double getAverageDamage() {
        return (totalDamage == 0) ? 0 : totalDamage / (kills + deaths);
    }
}