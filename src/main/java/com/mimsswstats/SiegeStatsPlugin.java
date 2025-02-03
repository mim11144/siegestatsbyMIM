    package com.mimsswstats;

    import com.mimsswstats.*;
    import org.bukkit.plugin.java.JavaPlugin;

    import java.io.File;

    public class SiegeStatsPlugin extends JavaPlugin {
        private SiegeStatsManager statsManager;
        private SiegeListener siegeListener;
        private static final long SAVE_INTERVAL = 6000L; 
        @Override
        public void onEnable() {
            statsManager = new SiegeStatsManager(this);

            siegeListener = new SiegeListener(this);
statsManager.loadPlayerStats();
            getServer().getPluginManager().registerEvents(siegeListener, this);
            getServer().getPluginManager().registerEvents(new BannerControlListener(this), this);
            getServer().getPluginManager().registerEvents(new PlayerDeathListener(this), this);
            getServer().getPluginManager().registerEvents(new PlayerDamageListener(this), this);
            getServer().getPluginManager().registerEvents(new SiegeCompletionListener(this), this);
            getCommand("siegestats").setExecutor(new SiegeStatsCommand(this));
            File statsDir = new File("plugins/SiegeStats");
            if (!statsDir.exists()) {
                if (!statsDir.mkdirs()) {
                    getLogger().severe("Failed to create SiegeStats directory!");
                    return;
                }
            }

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
