package com.mimsswstats;

import com.gmail.goosius.siegewar.enums.SiegeSide;
import java.util.UUID; // Import UUID
import java.util.concurrent.ConcurrentHashMap;

public class SiegeStats {

    // ParticipantMetrics inner class remains the same (with side field added previously)
    public static class ParticipantMetrics { /* ... */
        private int kills = 0;
        private int deaths = 0;
        private double damage = 0.0;
        private double controlTime = 0.0;
        private int assists = 0;
        private SiegeSide side = SiegeSide.NOBODY;
        public int getKills() { return kills; }
        public int getDeaths() { return deaths; }
        public double getDamage() { return damage; }
        public double getControlTime() { return controlTime; }
        public int getAssists() { return assists; }
        public SiegeSide getSide() { return side; }
        void addKills(int k) { this.kills += k; }
        void addDeaths(int d) { this.deaths += d; }
        void addDamage(double dmg) { this.damage += dmg; }
        void addControlTime(double time) { this.controlTime += time; }
        void addAssist(int amount) { this.assists += amount; }
        void setSide(SiegeSide side) { if (this.side == SiegeSide.NOBODY && side != SiegeSide.NOBODY) this.side = side; }
    }

    // ... (SiegeStats fields: townName, siegeNumber, etc.) ...
    private final String townName;
    private final int siegeNumber;
    private final String siegeId;
    private long startTimeMillis;
    private long endTimeMillis = -1;
    private boolean isActive;
    private boolean attackersWon;
    private boolean defendersWon;

    // <<< Change Map Key from String to UUID >>>
    private final ConcurrentHashMap<UUID, ParticipantMetrics> participantMetrics;

    public SiegeStats(String townName, int siegeNumber, String siegeId) {
        this.townName = townName.toLowerCase();
        this.siegeNumber = siegeNumber;
        this.siegeId = siegeId;
        this.startTimeMillis = System.currentTimeMillis();
        this.participantMetrics = new ConcurrentHashMap<>(); // Initialize with UUID key type
        this.isActive = true;
        this.attackersWon = false;
        this.defendersWon = false;
    }

    /**
     * Records actions using UUID.
     */
    public void recordPlayerAction(UUID playerUUID, int kills, int deaths, double damage, double controlTimeMinutes, int assists, SiegeSide playerSide) {
        // <<< Use UUID to get metrics >>>
        ParticipantMetrics metrics = participantMetrics.computeIfAbsent(playerUUID, k -> new ParticipantMetrics());

        // Set the side if it's not already set
        metrics.setSide(playerSide);

        // Update stats
        if (kills > 0) metrics.addKills(kills);
        if (deaths > 0) metrics.addDeaths(deaths);
        if (damage > 0) metrics.addDamage(damage);
        if (controlTimeMinutes > 0.0001) metrics.addControlTime(controlTimeMinutes);
        if (assists > 0) metrics.addAssist(assists);
    }

    // ... (Getters, Setters, endSiege, whoWon, etc. remain mostly the same) ...
    // <<< Update getter for participant metrics map >>>
    public ConcurrentHashMap<UUID, ParticipantMetrics> getParticipantMetrics() { return participantMetrics; }

    // Getters for other fields
    public String getTownName() { return townName; }
    public int getSiegeNumber() { return siegeNumber; }
    public String getSiegeId() { return siegeId; }
    public long getStartTime() { return startTimeMillis; }
    public long getEndTime() { return endTimeMillis; }
    public boolean isActive() { return isActive; }
    public boolean getAttackersWon(){return attackersWon;}
    public boolean getDefendersWon(){return defendersWon;}
    public double getDurationMinutes() { /* ... */ return (isActive ? System.currentTimeMillis() - startTimeMillis : endTimeMillis - startTimeMillis) / 60000.0;}
    public void setStartTime(long startTimeMillis) { this.startTimeMillis = startTimeMillis; }
    public void setEndTime(long endTimeMillis) { this.endTimeMillis = endTimeMillis; }
    public void setAttackersWon(boolean attackersWon){ this.attackersWon = attackersWon; }
    public void setDefendersWon(boolean defendersWon) { this.defendersWon = defendersWon; }
    public void setActive(boolean active) { isActive = active; }
    public void endSiege() { this.isActive = false; 
this.endTimeMillis = System.currentTimeMillis();}
    public int whoWon(){ return defendersWon ? 2 : (attackersWon ? 1 : 0); }
}
