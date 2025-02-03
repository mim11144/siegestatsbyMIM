    package com.mimsswstats;

    import com.mimsswstats.*;
    import org.bukkit.plugin.java.JavaPlugin;

    import java.io.File;

    public class SiegeStatsPlugin extends JavaPlugin {
        private SiegeStatsManager statsManager;
        private SiegeListener siegeListener;
        private static final long SAVE_INTERVAL = 6000L; // 5 minutes in ticks

        @Override
        public void onEnable() {
            // Initialize the stats manager first since other components depend on it
            statsManager = new SiegeStatsManager(this);

            // Create and store the siege listener - this is crucial for tracking siege IDs
            siegeListener = new SiegeListener(this);
statsManager.loadPlayerStats();
            // Register all event listeners
            getServer().getPluginManager().registerEvents(siegeListener, this);
            getServer().getPluginManager().registerEvents(new BannerControlListener(this), this);
            getServer().getPluginManager().registerEvents(new PlayerDeathListener(this), this);
            getServer().getPluginManager().registerEvents(new PlayerDamageListener(this), this);
            getServer().getPluginManager().registerEvents(new SiegeCompletionListener(this), this);
            // Register commands
            getCommand("siegestats").setExecutor(new SiegeStatsCommand(this));
            File statsDir = new File("plugins/SiegeStats");
            if (!statsDir.exists()) {
                if (!statsDir.mkdirs()) {
                    getLogger().severe("Failed to create SiegeStats directory!");
                    return;
                }
            }
            // Load existing stats

            // Schedule periodic saving
            getServer().getScheduler().scheduleSyncRepeatingTask(this,
                    () -> statsManager.savePlayerStats(),
                    SAVE_INTERVAL, SAVE_INTERVAL);

            getLogger().info("SiegeStats has been enabled!");
        }

        @Override
        public void onDisable() {
            if (statsManager != null) {
                statsManager.savePlayerStats();
            }
            getLogger().info("SiegeStats has been disabled!");
        }

        public SiegeStatsManager getStatsManager() {
            return statsManager;
        }

        public SiegeListener getSiegeListener() {
            return siegeListener;
        }
    }