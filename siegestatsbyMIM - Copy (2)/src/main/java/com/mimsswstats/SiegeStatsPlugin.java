package com.mimsswstats;

// Adventure Imports
import net.kyori.adventure.platform.bukkit.BukkitAudiences;

import org.bukkit.configuration.file.FileConfiguration; // Import FileConfiguration
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File; // Added
import java.io.IOException; // Import IOException
import java.io.InputStream; // Added
import java.nio.file.Files; // Added
import java.nio.file.StandardCopyOption; // Added

public final class SiegeStatsPlugin extends JavaPlugin {

    private SiegeStatsManager statsManager;
    private SiegeListener siegeListener;
    private BannerControlListener bannerControlListener;
    private WebServerManager webServerManager; // Added

    private BukkitAudiences adventure;
    // private static final long SAVE_INTERVAL_TICKS = 20L * 300L; // Using config value

    public BukkitAudiences adventure() {
        if(this.adventure == null) {
            throw new IllegalStateException("Tried to access Adventure Audiences before plugin is enabled or after it is disabled!");
        }
        return this.adventure;
    }

    @Override
    public void onEnable() {
        getLogger().info("Enabling SiegeStats...");

        this.adventure = BukkitAudiences.create(this);

        saveDefaultConfig(); // Ensure config.yml exists
        FileConfiguration config = getConfig(); // Load config

        if (!getDataFolder().exists()) {
            if (!getDataFolder().mkdirs()) {
                getLogger().severe("Could not create plugin data folder!");
                // Potentially disable plugin or parts of it if data folder is crucial
            }
        }

        // Copy web resources if they don't exist externally
        copyDefaultWebResources(); // <<< NEW METHOD CALL

        this.statsManager = new SiegeStatsManager(this);
        this.siegeListener = new SiegeListener(this);
        this.bannerControlListener = new BannerControlListener(this);

        getServer().getPluginManager().registerEvents(siegeListener, this);
        getServer().getPluginManager().registerEvents(bannerControlListener, this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerDamageListener(this), this);
        getServer().getPluginManager().registerEvents(new SiegeCompletionListener(this), this);

        getCommand("siegestats").setExecutor(new SiegeStatsCommand(this));

        long saveIntervalFromConfig = config.getLong("storage.save-interval", 300) * 20L;
        getServer().getScheduler().runTaskTimer(this, this.statsManager::saveStats, saveIntervalFromConfig, saveIntervalFromConfig);

        // Start Web Server
        if (config.getBoolean("web-server.enabled", true)) {
            int port = config.getInt("web-server.port", 8080);
            this.webServerManager = new WebServerManager(this, port);
            try {
                this.webServerManager.start();
                getLogger().info("SiegeStats Web Server started on port " + port);
            } catch (IOException e) {
                getLogger().severe("Could not start SiegeStats Web Server: " + e.getMessage());
                this.webServerManager = null; // Ensure it's null if start failed
            }
        } else {
            getLogger().info("SiegeStats Web Server is disabled in config.yml.");
        }

        getLogger().info("SiegeStats has been enabled successfully!");
    }

    private void copyDefaultWebResources() {
        String[] resourcesToCopy = {"index.html", "style.css", "script.js"};
        File webAppDir = new File(getDataFolder(), "web");

        if (!webAppDir.exists()) {
            if (!webAppDir.mkdirs()) {
                getLogger().severe("Could not create web resources directory: " + webAppDir.getAbsolutePath());
                return;
            }
            getLogger().info("Created web resources directory: " + webAppDir.getAbsolutePath());
        }

        for (String resourceName : resourcesToCopy) {
            File targetFile = new File(webAppDir, resourceName);
            if (!targetFile.exists()) {
                getLogger().info("Web resource " + resourceName + " not found in " + webAppDir.getPath() + ". Copying from JAR...");
                try (InputStream inputStream = getResource("web/" + resourceName)) {
                    if (inputStream == null) {
                        getLogger().warning("Could not find default web resource " + resourceName + " in JAR.");
                        continue;
                    }
                    Files.copy(inputStream, targetFile.toPath()); // Don't use REPLACE_EXISTING to preserve user changes if file somehow exists
                    getLogger().info("Copied " + resourceName + " to " + targetFile.getAbsolutePath());
                } catch (IOException e) {
                    getLogger().severe("Could not copy web resource " + resourceName + ": " + e.getMessage());
                }
            } else {
                 // getLogger().info("Web resource " + resourceName + " already exists in " + webAppDir.getPath() + ". Skipping copy.");
            }
        }
    }


    @Override
    public void onDisable() {
        getLogger().info("Disabling SiegeStats...");

        // Stop Web Server
        if (this.webServerManager != null && this.webServerManager.isAlive()) {
            this.webServerManager.stop();
            getLogger().info("SiegeStats Web Server stopped.");
        }

        if (statsManager != null) {
            statsManager.saveStats();
            getLogger().info("Final siege stats saved.");
        }

        if(this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }

        getLogger().info("SiegeStats has been disabled.");
    }

    public SiegeStatsManager getStatsManager() { return statsManager; }
    public SiegeListener getSiegeListener() { return siegeListener; }
    public BannerControlListener getBannerControlListener() { return bannerControlListener; }
}