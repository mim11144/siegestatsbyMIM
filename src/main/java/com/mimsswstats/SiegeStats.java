package com.mimsswstats;

import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.mimsswstats.PlayerStats;

import java.util.concurrent.ConcurrentHashMap;

public class SiegeStats {
    private final String townName;
    private final int siegeNumber;
    private long startTime;
    private final ConcurrentHashMap<String, PlayerStats> participantStats;
    private final ConcurrentHashMap<String, SiegeSide> participantSides; // Track player sides
    private boolean isActive;

    public SiegeStats(String townName, int siegeNumber) {
        this.townName = townName.toLowerCase();
        this.siegeNumber = siegeNumber;
        this.startTime = System.currentTimeMillis();
        this.participantStats = new ConcurrentHashMap<>();
        this.participantSides = new ConcurrentHashMap<>();
        this.isActive = true;
    }

    public void recordPlayerSide(String playerName, SiegeSide side) {
        participantSides.put(playerName, side);
    }

    public SiegeSide getPlayerSide(String playerName) {
        return participantSides.getOrDefault(playerName, SiegeSide.NOBODY);
    }

    public void recordPlayerAction(String playerName, int kills, int deaths, double damage) {
        PlayerStats stats = participantStats.computeIfAbsent(playerName, k -> new PlayerStats(playerName));
        stats.addKills(kills);
        stats.addDeaths(deaths);
        stats.addDamage(damage);
    }

    // Getters
    public String getTownName() { return townName; }
    public int getSiegeNumber() { return siegeNumber; }
    public long getStartTime() { return startTime; }
    public ConcurrentHashMap<String, PlayerStats> getParticipantStats() { return participantStats; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
}