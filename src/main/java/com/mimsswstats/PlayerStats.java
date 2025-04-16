package com.mimsswstats;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID; // Import UUID

public class PlayerStats {
    private final UUID playerUUID; // <<< Use UUID as the identifier
    private String lastKnownName; // Store for display/logging convenience

    private int totalKills;
    private int totalDeaths;
    private double totalDamage;
    private double totalCaptureTime;
    private int totalSiegesParticipated;
    private int totalWins;
    private int totalLosses;
    private int totalAssists;

    private final Set<String> uniqueSiegeParticipations; // Keep siege IDs as Strings for now

    // Constructor now takes UUID and Name
    public PlayerStats(UUID playerUUID, String initialName) {
        this.playerUUID = playerUUID;
        this.lastKnownName = initialName;
        // ... initialize other stats to 0 ...
        this.totalKills = 0;
        this.totalDeaths = 0;
        this.totalDamage = 0.0;
        this.totalCaptureTime = 0.0;
        this.totalSiegesParticipated = 0;
        this.totalWins = 0;
        this.totalLosses = 0;
        this.totalAssists = 0;
        this.uniqueSiegeParticipations = new HashSet<>();
    }

    // --- Getters ---
    public UUID getPlayerUUID() { return playerUUID; } // <<< Getter for UUID

    // Update name if needed, e.g., during loading or periodically
    public void updateName(String name) { this.lastKnownName = name; }
    public String getLastKnownName() { return lastKnownName; } // <<< Getter for Name

    // Other getters remain the same...
    public int getTotalKills() { return totalKills; }
    public int getTotalDeaths() { return totalDeaths; }
    public double getTotalDamage() { return totalDamage; }
    public double getTotalCaptureTime() { return totalCaptureTime; }
    public int getTotalSiegesParticipated() { return totalSiegesParticipated; }
    public int getTotalWins() { return totalWins; }
    public int getTotalLosses() { return totalLosses; }
    public int getTotalAssists() { return totalAssists; }

    // --- Modifiers ---
    // Adders remain the same...
    public void addKills(int kills) { this.totalKills += kills; }
    public void addDeaths(int deaths) { this.totalDeaths += deaths; }
    public void addDamage(double damage) { this.totalDamage += damage; }
    public void addCaptureTime(double capTimeMinutes) { this.totalCaptureTime += capTimeMinutes; }
    public void addWin() { this.totalWins++; }
    public void addLoss() { this.totalLosses++; }
    public void addAssist(int amount) { this.totalAssists += amount; }
    public void addSiegeParticipation(String siegeId) {
        if (uniqueSiegeParticipations.add(siegeId)) {
            this.totalSiegesParticipated++;
        }
    }

    // --- Calculated Getters ---
    // (No changes needed here)
    public double getKillDeathRatio() { /* ... */ return (totalDeaths==0)?totalKills:((double)totalKills/totalDeaths); }
    public double getKdaRatio() { /* ... */ return (totalDeaths==0)?(totalKills+totalAssists):((double)(totalKills+totalAssists)/totalDeaths); }
    public double getWinLossRatio() { /* ... */ int tg = totalWins+totalLosses; return (tg==0)?0.0:((double)totalWins/tg); }

    // --- Setters (for loading) ---
    // Setters remain the same...
    public void setTotalKills(int totalKills) { this.totalKills = totalKills; }
    public void setTotalDeaths(int totalDeaths) { this.totalDeaths = totalDeaths; }
    public void setTotalDamage(double totalDamage) { this.totalDamage = totalDamage; }
    public void setTotalCaptureTime(double totalCaptureTime) { this.totalCaptureTime = totalCaptureTime; }
    public void setTotalSiegesParticipated(int totalSiegesParticipated) { this.totalSiegesParticipated = totalSiegesParticipated; }
    public void setTotalWins(int totalWins) { this.totalWins = totalWins; }
    public void setTotalLosses(int totalLosses) { this.totalLosses = totalLosses; }
    public void setTotalAssists(int totalAssists) { this.totalAssists = totalAssists; }
}
