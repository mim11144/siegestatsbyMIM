package com.mimsswstats;

import com.gmail.goosius.siegewar.objects.Siege;

public class SiegePerformance {
    private final boolean won;
    private final int kills;
    private final int deaths;
    private final double damage;
    private final Siege siege;
    private final long timestamp;

    public SiegePerformance(boolean won, int kills, int deaths, double damage, Siege siege) {
        this.won = won;
        this.kills = kills;
        this.deaths = deaths;
        this.damage = damage;
        this.siege = siege;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isWon() { return won; }
    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public double getDamage() { return damage; }
    public Siege getSiege() { return siege; }
    public long getTimestamp() { return timestamp; }
}
