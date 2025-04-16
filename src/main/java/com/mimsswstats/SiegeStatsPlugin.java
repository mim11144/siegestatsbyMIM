package com.mimsswstats;

// Adventure Imports
import net.kyori.adventure.platform.bukkit.BukkitAudiences; // Import BukkitAudiences

import org.bukkit.plugin.java.JavaPlugin;

public final class SiegeStatsPlugin extends JavaPlugin {

    private SiegeStatsManager statsManager;
    private SiegeListener siegeListener;
    private BannerControlListener bannerControlListener;

    // <<< NEW: Adventure Audiences Provider >>>
    private BukkitAudiences adventure;

    private static final long SAVE_INTERVAL = 6000L; // 5 minutes

    // <<< NEW: Getter for Adventure Audiences >>>
    public BukkitAudiences adventure() {
        if(this.adventure == null) {
            throw new IllegalStateException("Tried to access Adventure Audiences before plugin is enabled or after it is disabled!");
        }
        return this.adventure;
    }

    @Override
    public void onEnable() {
        getLogger().info("Enabling SiegeStats...");

        // <<< NEW: Initialize Adventure >>>
        this.adventure = BukkitAudiences.create(this);

        // Ensure data folder exists
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Initialize main components
        this.statsManager = new SiegeStatsManager(this);
        this.siegeListener = new SiegeListener(this);
        this.bannerControlListener = new BannerControlListener(this);

        // Register Event Listeners
        getServer().getPluginManager().registerEvents(siegeListener, this);
        getServer().getPluginManager().registerEvents(bannerControlListener, this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerDamageListener(this), this);
        getServer().getPluginManager().registerEvents(new SiegeCompletionListener(this), this);

        // Register Commands
        getCommand("siegestats").setExecutor(new SiegeStatsCommand(this)); // Pass plugin instance

        // Load existing stats (handled by StatsManager constructor)

        // Schedule periodic saving (Auto-save)
        getServer().getScheduler().runTaskTimer(this, this.statsManager::saveStats, SAVE_INTERVAL, SAVE_INTERVAL);

        getLogger().info("SiegeStats has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling SiegeStats...");
        // Save stats one last time
        if (statsManager != null) {
            statsManager.saveStats();
            getLogger().info("Final siege stats saved.");
        }

        // <<< NEW: Cleanup Adventure >>>
        if(this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }

        getLogger().info("SiegeStats has been disabled.");
    }

    // --- Getters ---
    public SiegeStatsManager getStatsManager() { return statsManager; }
    public SiegeListener getSiegeListener() { return siegeListener; }
    public BannerControlListener getBannerControlListener() { return bannerControlListener; }
}
