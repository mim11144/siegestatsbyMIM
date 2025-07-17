package com.mimsswstats;

// SiegeWar Imports
import com.gmail.goosius.siegewar.SiegeController;
import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.objects.Siege;
// Towny Imports
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
// Bukkit API Imports
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
// JSON Imports
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
// Java Util Imports
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Iterator;


public class SiegeStatsManager {
    private final SiegeStatsPlugin plugin;
    public static final long ASSIST_WINDOW_MS = 20000; // 20 seconds assist window (as per original, though comment said 15)
    public static final double MIN_ASSIST_DAMAGE_PERCENTAGE = 0.40; // 40% minimum damage for assist

    // --- Data Storage ---
    private ConcurrentHashMap<UUID, PlayerStats> playerStats;
    private ConcurrentHashMap<String, Integer> townSiegeCounter;
    private ConcurrentHashMap<String, SiegeStats> activeSieges;
    private ConcurrentHashMap<String, SiegeStats> completedSieges;
    private final File statsFile;

    // Add this getter method inside SiegeStatsManager class

public ConcurrentHashMap<String, SiegeStats> getActiveSiegesMap() {
    return activeSieges;
}

    // Assist Tracking: Victim UUID -> Map<Attacker UUID, List<DamageLog>>
    // Each DamageLog contains the damage amount and timestamp of a single hit.
    private final Map<UUID, Map<UUID, List<DamageLog>>> recentDamagers;

    // Simple class to store damage amount and timestamp for assist calculation
    private static class DamageLog {
        final double damage;
        final long timestamp;

        DamageLog(double damage, long timestamp) {
            this.damage = damage;
            this.timestamp = timestamp;
        }
    }

    public SiegeStatsManager(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
        this.statsFile = new File(plugin.getDataFolder(), "stats.json");
        this.recentDamagers = new ConcurrentHashMap<>(); // Initialize here
        initializeData();
        loadStats();
    }

    private void initializeData() {
        this.playerStats = new ConcurrentHashMap<>();
        this.townSiegeCounter = new ConcurrentHashMap<>();
        this.activeSieges = new ConcurrentHashMap<>();
        this.completedSieges = new ConcurrentHashMap<>();
        this.recentDamagers.clear(); // Cleared here as well
    }

    // --- Core Logic Methods ---

    public String startNewSiege(Siege siege) {
        plugin.getLogger().info("[DEBUG] Manager: startNewSiege called.");
        if (siege == null || siege.getTown() == null) {
            plugin.getLogger().severe("[DEBUG] Manager: Attempted to start siege with null Siege or Town object! Returning null.");
            return null;
        }
        plugin.getLogger().info("[DEBUG] Manager: Siege and Town objects are not null.");

        String townName = siege.getTown().getName().toLowerCase();
        plugin.getLogger().info("[DEBUG] Manager: Processing town: " + townName);

        int siegeNumber = -1;
        try {
            siegeNumber = townSiegeCounter.compute(townName, (key, currentCount) -> (currentCount == null) ? 1 : currentCount + 1);
            plugin.getLogger().info("[DEBUG] Manager: Computed siege number for " + townName + ": " + siegeNumber);
        } catch (Exception e) {
            plugin.getLogger().severe("[DEBUG] Manager: Exception during townSiegeCounter.compute for " + townName + ": " + e.getMessage());
            return null;
        }

        String siegeId = townName + "_" + siegeNumber;
        plugin.getLogger().info("[DEBUG] Manager: Generated siegeId: " + siegeId);

        SiegeStats newSiegeStats = null;
        try {
            newSiegeStats = new SiegeStats(townName, siegeNumber, siegeId);
            newSiegeStats.setStartTime(System.currentTimeMillis());
            plugin.getLogger().info("[DEBUG] Manager: Created SiegeStats object for " + siegeId);
        } catch (Exception e) {
            plugin.getLogger().severe("[DEBUG] Manager: Exception during SiegeStats creation for " + siegeId + ": " + e.getMessage());
            return null;
        }

        try {
            plugin.getLogger().info("[DEBUG] Manager: Attempting to put siege " + siegeId + " into activeSieges map.");
            activeSieges.put(siegeId, newSiegeStats);
            plugin.getLogger().info("[DEBUG] Manager: Successfully put siege " + siegeId + " into activeSieges map.");
        } catch (Exception e) {
            plugin.getLogger().severe("[DEBUG] Manager: Exception during activeSieges.put for " + siegeId + ": " + e.getMessage());
            return null;
        }

        plugin.getLogger().info("Started tracking new siege: ID=" + siegeId + ", Town=" + townName + ", Number=" + siegeNumber);
        plugin.getLogger().info("[DEBUG] Manager: Returning valid siegeId: " + siegeId);
        return siegeId;
    }

    public void endSiege(String siegeId) {
        SiegeStats stats = activeSieges.remove(siegeId);
        if (stats != null) {
            stats.endSiege();
            completedSieges.put(siegeId, stats);
            plugin.getLogger().info("Ended tracking for siege ID: " + siegeId + ". Moved to completed.");
            saveStats();
        } else {
            plugin.getLogger().warning("Tried to end siege with ID " + siegeId + ", but it wasn't found in activeSieges map.");
        }
        // Victim-specific entries in recentDamagers are cleared upon their death processing.
    }

    // --- Assist Tracking Methods ---

    /**
     * Records a damage event. This data is used by processAndRecordAssists.
     * Call this method whenever a player damages another player in a monitored context (e.g., during a siege).
     *
     * @param victimUUID   The UUID of the player taking damage.
     * @param damagerUUID  The UUID of the player dealing damage.
     * @param damageAmount The amount of damage dealt in this specific event.
     */
    public void addRecentDamager(UUID victimUUID, UUID damagerUUID, double damageAmount) {
        if (victimUUID.equals(damagerUUID) || damageAmount <= 0) {
            return; // Self-damage or no damage doesn't count for assists by others
        }

        Map<UUID, List<DamageLog>> victimDamageMap = recentDamagers.computeIfAbsent(victimUUID, k -> new ConcurrentHashMap<>());
        List<DamageLog> attackerLogs = victimDamageMap.computeIfAbsent(damagerUUID, k -> new CopyOnWriteArrayList<>());

        attackerLogs.add(new DamageLog(damageAmount, System.currentTimeMillis()));

        // Optional: Implement pruning for attackerLogs if lists become excessively long
        // e.g., remove logs older than ASSIST_WINDOW_MS + some buffer, but this could remove
        // data prematurely if a player is damaged over a slightly longer period before dying.
        // Pruning is currently handled more definitively in processAndRecordAssists by clearing
        // the victim's entire entry.
    }


    /**
     * Processes and records assists based on damage contribution after a player's death.
     * Call this from your PlayerDeathEvent listener.
     *
     * @param siegeId           The ID of the siege this death occurred in.
     * @param victimUUID        The UUID of the player who died.
     * @param killerUUID        The UUID of the player who got the kill.
     * @param killingBlowDamage The damage amount of the final hit that killed the victim.
     */
    public void processAndRecordAssists(String siegeId, UUID victimUUID, UUID killerUUID, double killingBlowDamage) {
        if (siegeId == null || victimUUID == null || killerUUID == null) {
            plugin.getLogger().warning("[ASSIST] processAndRecordAssists called with null parameters.");
            return;
        }

        Map<UUID, List<DamageLog>> victimAllDamagersMap = recentDamagers.get(victimUUID);
        if (victimAllDamagersMap == null || victimAllDamagersMap.isEmpty()) {
            // No other damagers recorded for this victim, or already processed.
            recentDamagers.remove(victimUUID); // Ensure cleanup if called with no data
            return;
        }

        long deathTime = System.currentTimeMillis();
        long windowStartTime = deathTime - ASSIST_WINDOW_MS;

        Map<UUID, Double> potentialAssistersTotalDamage = new ConcurrentHashMap<>();
        double totalDamageByAllPotentialAssistersInWindow = 0;

        for (Map.Entry<UUID, List<DamageLog>> entry : victimAllDamagersMap.entrySet()) {
            UUID attackerUUID = entry.getKey();
            List<DamageLog> damageLogs = entry.getValue();

            if (attackerUUID.equals(killerUUID)) {
                continue; // Skip the killer; their contribution is via killingBlowDamage for the pool.
            }

            double currentAttackerDamageInWindow = 0;
            Iterator<DamageLog> logIterator = damageLogs.iterator(); // Use iterator for potential removal
            while(logIterator.hasNext()){
                DamageLog log = logIterator.next();
                if (log.timestamp >= windowStartTime && log.timestamp <= deathTime) { // Damage within window
                    currentAttackerDamageInWindow += log.damage;
                }
                // Optional: remove very old logs from the list if not already handled by victim map removal
                // else if (log.timestamp < windowStartTime - SOME_OLDER_BUFFER) { logIterator.remove(); }
            }

            if (currentAttackerDamageInWindow > 0) {
                potentialAssistersTotalDamage.put(attackerUUID, currentAttackerDamageInWindow);
                totalDamageByAllPotentialAssistersInWindow += currentAttackerDamageInWindow;
            }
        }

        // If no potential assisters (other than killer) dealt damage in the window
        if (potentialAssistersTotalDamage.isEmpty()) {
            recentDamagers.remove(victimUUID); // Clean up this victim's damage logs
            return;
        }

        // Total damage pool for assist calculation: killing blow + sum of damage by all valid assisters in window
        double totalDamagePool = killingBlowDamage + totalDamageByAllPotentialAssistersInWindow;

        // Avoid division by zero or negative pool; ensure pool is meaningfully positive.
        if (totalDamagePool <= 0.001) {
            plugin.getLogger().info(String.format("[ASSIST] Total damage pool for victim %s is %.2f, too low for assist calculation.", victimUUID, totalDamagePool));
            recentDamagers.remove(victimUUID); // Clean up
            return;
        }

        for (Map.Entry<UUID, Double> assistEntry : potentialAssistersTotalDamage.entrySet()) {
            UUID assisterUUID = assistEntry.getKey();
            double assisterDamageInWindow = assistEntry.getValue();

            double assisterPercentage = assisterDamageInWindow / totalDamagePool;

            if (assisterPercentage >= MIN_ASSIST_DAMAGE_PERCENTAGE) {
                plugin.getLogger().info(String.format("[ASSIST] Granting assist to %s for kill on %s. Damage: %.2f / %.2f (%.2f%%). Required: %.0f%%",
                        assisterUUID.toString().substring(0,8), victimUUID.toString().substring(0,8),
                        assisterDamageInWindow, totalDamagePool,
                        assisterPercentage * 100, MIN_ASSIST_DAMAGE_PERCENTAGE * 100));

                // Record the assist. Damage dealt by the assister is already accounted for
                // globally when their actual damage events were recorded.
                // The 'damage' field in recordSiegeAction here can be 0 for the "assist action" itself.
                recordSiegeAction(siegeId, assisterUUID, 0, 0, 0, 0, 1);
            }
        }

        // Clean up all damage logs for the victim after processing assists for their death.
        recentDamagers.remove(victimUUID);
    }


    /**
     * Retrieves recent damagers for a victim based on timestamp.
     * Note: This method is NOT used for the new 40% damage assist rule, which is handled by processAndRecordAssists.
     * It's kept for potential other uses or backward compatibility.
     *
     * @param victimUUID The UUID of the victim.
     * @return A map of attacker UUIDs to their last damage timestamp if within the assist window.
     */
    public Map<UUID, Long> getRecentDamagersForAssist(UUID victimUUID) {
        Map<UUID, List<DamageLog>> victimDamageMap = recentDamagers.get(victimUUID);
        if (victimDamageMap == null) {
            return Collections.emptyMap();
        }

        long cutoffTime = System.currentTimeMillis() - ASSIST_WINDOW_MS;
        Map<UUID, Long> validAssistersTimestamps = new ConcurrentHashMap<>();

        victimDamageMap.forEach((attackerUUID, damageLogs) -> {
            if (damageLogs != null && !damageLogs.isEmpty()) {
                // Get the timestamp of the most recent damage log from this attacker
                DamageLog lastLog = damageLogs.get(damageLogs.size() - 1);
                if (lastLog.timestamp >= cutoffTime) {
                    validAssistersTimestamps.put(attackerUUID, lastLog.timestamp);
                }
            }
        });
        // This method does not clear from the primary recentDamagers map;
        // cleanup is primarily handled by processAndRecordAssists or clearRecentDamagers.
        return validAssistersTimestamps;
    }


    public void clearRecentDamagers(UUID victimUUID) {
        recentDamagers.remove(victimUUID);
    }

    // --- Stat Recording ---

    public void recordSiegeAction(String siegeId, UUID playerUUID, int kills, int deaths, double damage, double controlTimeMinutes, int assists) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
        String currentName = offlinePlayer.getName();
        if (currentName == null) currentName = playerUUID.toString();

        SiegeStats siegeStats = activeSieges.get(siegeId);
        if (siegeStats == null) {
            plugin.getLogger().warning("Attempted to record action for non-active siege ID: " + siegeId + " for player " + currentName);
            return;
        }

        SiegeSide playerSide = getPlayerSide(siegeStats, playerUUID, currentName);

        plugin.getLogger().info(String.format("[DEBUG] Manager recording action for UUID %s (Name: %s, Side: %s), Siege: %s, K:%d, D:%d, A:%d, DMG:%.1f, CapT:%.4f",
                playerUUID, currentName, playerSide, siegeId, kills, deaths, assists, damage, controlTimeMinutes));

        siegeStats.recordPlayerAction(playerUUID, kills, deaths, damage, controlTimeMinutes, assists, playerSide);

        PlayerStats globalPlayerStats = getPlayerStats(playerUUID, currentName);
        if (globalPlayerStats != null) {
            globalPlayerStats.updateName(currentName);

            if (controlTimeMinutes > 0.0001) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding CapT %.4f", currentName, controlTimeMinutes)); }
            if (assists > 0) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding Assist %d", currentName, assists)); }
            if (kills > 0) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding Kill %d", currentName, kills)); }
            if (deaths > 0) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding Death %d", currentName, deaths)); }
            if (damage > 0) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding Damage %.1f", currentName, damage)); }

            if (kills > 0) globalPlayerStats.addKills(kills);
            if (deaths > 0) globalPlayerStats.addDeaths(deaths);
            if (damage > 0) globalPlayerStats.addDamage(damage);
            if (controlTimeMinutes > 0.0001) globalPlayerStats.addCaptureTime(controlTimeMinutes);
            if (assists > 0) globalPlayerStats.addAssist(assists);
        } else {
            plugin.getLogger().severe("Failed to get/create global PlayerStats for " + currentName + " (UUID: " + playerUUID + ")!");
        }
    }

    public void recordSiegeAction(String siegeId, UUID playerUUID, int kills, int deaths, double damage, double controlTimeMinutes) {
        recordSiegeAction(siegeId, playerUUID, kills, deaths, damage, controlTimeMinutes, 0);
    }

    private SiegeSide getPlayerSide(SiegeStats siegeStats, UUID playerUUID, String currentName) {
        SiegeSide playerSide = SiegeSide.NOBODY;
        Player onlinePlayer = Bukkit.getPlayer(playerUUID);

        if (onlinePlayer != null) {
            Town town = TownyAPI.getInstance().getTown(siegeStats.getTownName());
            if (town != null) {
                Siege siege = SiegeController.getSiegeByTownUUID(town.getUUID());
                if (siege != null) {
                    playerSide = SiegeSide.getPlayerSiegeSide(siege, onlinePlayer);
                }
            }
        }

        if (playerSide == SiegeSide.NOBODY) {
            SiegeStats.ParticipantMetrics existingMetrics = siegeStats.getParticipantMetrics().get(playerUUID);
            if (existingMetrics != null && existingMetrics.getSide() != SiegeSide.NOBODY) {
                playerSide = existingMetrics.getSide();
            }
        }
        return playerSide;
    }

    public void addSiegeParticipant(Siege siege, Player player) {
        if (siege == null || player == null) return;
        String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);
        if (siegeId == null) return;

        SiegeStats siegeStats = activeSieges.get(siegeId);
        if (siegeStats == null) return;

        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();

        PlayerStats globalPlayerStats = getPlayerStats(playerUUID, playerName);
        if (globalPlayerStats != null) {
            globalPlayerStats.updateName(playerName);
            globalPlayerStats.addSiegeParticipation(siegeId);
        }

        SiegeSide playerSide = SiegeSide.getPlayerSiegeSide(siege, player);
        SiegeStats.ParticipantMetrics metrics = siegeStats.getParticipantMetrics().computeIfAbsent(playerUUID, k -> new SiegeStats.ParticipantMetrics());
        metrics.setSide(playerSide);
        plugin.getLogger().fine("[DEBUG] Added/Updated participant " + playerName + " (UUID:" + playerUUID + ") for siege " + siegeId + " with side " + playerSide);
    }

    // --- Data Access Methods ---

    public PlayerStats getPlayerStats(UUID playerUUID, String currentName) {
        return playerStats.computeIfAbsent(playerUUID, k -> new PlayerStats(k, currentName));
    }

    public PlayerStats getPlayerStatsByName(String playerName) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerName); // Deprecated, but common for name lookup
        if (op == null || (!op.hasPlayedBefore() && !op.isOnline())) {
            // Attempt to iterate playerStats for a name match if offline player lookup fails or is ambiguous
            for (PlayerStats stats : playerStats.values()) {
                if (stats.getLastKnownName().equalsIgnoreCase(playerName)) {
                    return stats; // Found by last known name
                }
            }
            return null; // Not found by UUID via Bukkit, and not in current stats by name
        }
        UUID playerUUID = op.getUniqueId();
        PlayerStats stats = getPlayerStats(playerUUID, playerName);
        if (stats != null) {
            stats.updateName(playerName);
        }
        return stats;
    }

    public ConcurrentHashMap<UUID, PlayerStats> getAllPlayerStats() {
        return playerStats;
    }

    public SiegeStats getSiegeStatsById(String siegeId) {
        return activeSieges.getOrDefault(siegeId, completedSieges.get(siegeId));
    }
    public ConcurrentHashMap<String, SiegeStats> getCompletedSieges() { return completedSieges; }
    public int getTownSiegeCount(String townName) { return townSiegeCounter.getOrDefault(townName.toLowerCase(), 0); }

    public SiegeStats findCompletedSiegeStats(Siege siege) {
        if (siege == null || siege.getTown() == null) return null;
        String townName = siege.getTown().getName().toLowerCase();
        SiegeStats latestMatch = null;
        long latestEndTime = -1;
        for (SiegeStats stats : completedSieges.values()) {
            if (stats.getTownName().equalsIgnoreCase(townName) && !stats.isActive()) {
                if (stats.getEndTime() > latestEndTime) {
                    latestEndTime = stats.getEndTime();
                    latestMatch = stats;
                }
            }
        }
        return latestMatch;
    }

    // --- Persistence Methods (Updated for UUIDs) ---

    public synchronized void saveStats() {
        JSONObject root = new JSONObject();
        JSONObject playersJson = new JSONObject();

        playerStats.forEach((uuid, stats) -> {
            JSONObject pData = new JSONObject();
            pData.put("lastKnownName", stats.getLastKnownName());
            pData.put("totalKills", stats.getTotalKills());
            pData.put("totalDeaths", stats.getTotalDeaths());
            pData.put("totalAssists", stats.getTotalAssists());
            pData.put("totalDamage", stats.getTotalDamage());
            pData.put("totalCaptureTime", stats.getTotalCaptureTime());
            pData.put("totalSiegesParticipated", stats.getTotalSiegesParticipated());
            pData.put("totalWins", stats.getTotalWins());
            pData.put("totalLosses", stats.getTotalLosses());
            playersJson.put(uuid.toString(), pData);
        });
        root.put("playerStats", playersJson);

        JSONObject counters = new JSONObject();
        townSiegeCounter.forEach(counters::put);
        root.put("townSiegeCounter", counters);

        JSONObject activeS = new JSONObject();
        activeSieges.forEach((id, stats) -> activeS.put(id, serializeSiegeStats(stats)));
        root.put("activeSieges", activeS);

        JSONObject completedS = new JSONObject();
        completedSieges.forEach((id, stats) -> completedS.put(id, serializeSiegeStats(stats)));
        root.put("completedSieges", completedS);

        File parentDir = statsFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            plugin.getLogger().severe("Could not create data folder: " + parentDir.getPath()); return;
        }
        try (FileWriter fw = new FileWriter(statsFile)) {
            fw.write(root.toJSONString());
            plugin.getLogger().info("Siege stats saved (UUID keys).");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save stats: " + e.getMessage());
        }
    }

    private JSONObject serializeSiegeStats(SiegeStats stats) {
        JSONObject sData = new JSONObject();
        sData.put("townName", stats.getTownName());
        sData.put("siegeNumber", stats.getSiegeNumber());
        sData.put("siegeId", stats.getSiegeId());
        sData.put("startTimeMillis", stats.getStartTime());
        sData.put("endTimeMillis", stats.getEndTime());
        sData.put("isActive", stats.isActive());
        sData.put("attackersWon", stats.getAttackersWon());
        sData.put("defendersWon", stats.getDefendersWon());

        JSONObject participants = new JSONObject();
        stats.getParticipantMetrics().forEach((uuid, metrics) -> {
            JSONObject mData = new JSONObject();
            mData.put("kills", metrics.getKills());
            mData.put("deaths", metrics.getDeaths());
            mData.put("assists", metrics.getAssists());
            mData.put("damage", metrics.getDamage());
            mData.put("controlTime", metrics.getControlTime());
            mData.put("side", metrics.getSide().name());
            participants.put(uuid.toString(), mData);
        });
        sData.put("participantMetrics", participants);
        return sData;
    }

    public synchronized void loadStats() {
        initializeData(); 
        if (!statsFile.exists()) { return; }
        JSONParser parser = new JSONParser();
        try (FileReader reader = new FileReader(statsFile)) {
            JSONObject root = (JSONObject) parser.parse(reader);

            JSONObject playersJson = (JSONObject) root.get("playerStats");
            if (playersJson != null) {
                playersJson.forEach((key, value) -> {
                    String uuidString = (String) key;
                    JSONObject pData = (JSONObject) value;
                    try {
                        UUID playerUUID = UUID.fromString(uuidString);
                        String name = (String) pData.getOrDefault("lastKnownName", uuidString);
                        PlayerStats stats = playerStats.computeIfAbsent(playerUUID, k -> new PlayerStats(k, name));
                        stats.updateName(name);
                        stats.setTotalKills(((Long) pData.getOrDefault("totalKills", 0L)).intValue());
                        stats.setTotalDeaths(((Long) pData.getOrDefault("totalDeaths", 0L)).intValue());
                        stats.setTotalAssists(((Long) pData.getOrDefault("totalAssists", 0L)).intValue());
                        stats.setTotalDamage((Double) pData.getOrDefault("totalDamage", 0.0));
                        stats.setTotalCaptureTime((Double) pData.getOrDefault("totalCaptureTime", 0.0));
                        stats.setTotalWins(((Long) pData.getOrDefault("totalWins", 0L)).intValue());
                        stats.setTotalLosses(((Long) pData.getOrDefault("totalLosses", 0L)).intValue());
                        int participations = ((Long) pData.getOrDefault("totalSiegesParticipated", 0L)).intValue();
                        stats.setTotalSiegesParticipated(participations); 
                        // Note: uniqueSiegeParticipations set itself isn't typically saved/loaded directly
                        // as totalSiegesParticipated is the key metric. If it were, it'd need JSONArray handling.

                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Skipping invalid UUID in playerStats key: " + uuidString + ". Error: " + e.getMessage());
                    } catch (ClassCastException e) {
                        plugin.getLogger().warning("Type mismatch loading player stat for " + uuidString + ". Error: " + e.getMessage());
                    }
                });
            }

            JSONObject counters = (JSONObject) root.get("townSiegeCounter");
            if (counters != null) {
                counters.forEach((t, c) -> {
                    if (t instanceof String && c instanceof Long) {
                        townSiegeCounter.put((String)t, ((Long)c).intValue());
                    } else {
                        plugin.getLogger().warning("Skipping invalid townSiegeCounter entry: Key=" + t + ", Value=" +c);
                    }
                });
            }

            JSONObject activeS = (JSONObject) root.get("activeSieges");
            if (activeS != null) {
                activeS.forEach((id, sD) -> {
                    // ... (rest of the code remains the same)
                });
            }

            JSONObject completedS = (JSONObject) root.get("completedSieges");
            if (completedS != null) {
                completedS.forEach((id, sD) -> {
                    // ... (rest of the code remains the same)
                });
            }
            plugin.getLogger().info("Siege stats loaded successfully (UUID keys).");
        } catch (IOException | ParseException | ClassCastException | NullPointerException e) {
            plugin.getLogger().severe("Could not load siege stats: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for detailed error
            initializeData(); // Reset data on critical error
        }
    }

    // ... (rest of the code remains the same)

    public void debugDumpStats(CommandSender sender) {
        // ... (rest of the code remains the same)
    }

    /**
     * Deletes a siege entirely from tracking and reverts its contributions to global player stats.
     * This is typically used for sieges that are admin-removed or end without a clear outcome
     * and should not be part of the historical record or player W/L stats.
     * @param siegeId The ID of the siege to delete.
     */
    /**
     * Resets all statistics and clears all data.
     * This includes player stats, siege records, and town counters.
     */
    public synchronized void resetAllStats() {
        plugin.getLogger().info("Resetting all siege stats...");
        initializeData();
        // Optionally delete the stats file
        if (statsFile.exists()) {
            if (!statsFile.delete()) {
                plugin.getLogger().warning("Could not delete old stats file: " + statsFile.getPath());
            }
        }
        saveStats(); // This will save an empty state
        plugin.getLogger().info("All siege stats have been reset.");
    }

    /**
     * Deletes a siege entirely from tracking, reverts its contributions to global player stats,
     * and decrements the town siege counter.
     * Assumes that if a siege is being deleted, it was the latest one for that town
     * due to the constraint of one active siege per town.
     * @param siegeId The ID of the siege to delete.
     */
    public synchronized void deleteSiegeAndRevertPlayerStats(String siegeId) {
        if (siegeId == null || siegeId.isEmpty()) {
            plugin.getLogger().warning("[StatsManager] Attempted to delete siege with null or empty ID.");
            return;
        }

        plugin.getLogger().info("[StatsManager] Attempting to delete siege " + siegeId + " and revert associated stats.");

        SiegeStats siegeToRemove = activeSieges.remove(siegeId);

        if (siegeToRemove == null) {
            if (completedSieges.containsKey(siegeId)) {
                plugin.getLogger().warning("[StatsManager] Siege " + siegeId + " was found in completedSieges. This method is intended for active sieges being removed without completion. No changes made to completed siege.");
                return;
            }
            plugin.getLogger().warning("[StatsManager] Siege " + siegeId + " not found in activeSieges map for deletion. It might have been already processed or never tracked.");
            return;
        }

        plugin.getLogger().info("[StatsManager] Successfully removed siege " + siegeId + " from activeSieges. Now reverting town siege counter and player stats.");

        // Revert town siege counter
        String townName = siegeToRemove.getTownName().toLowerCase();

        townSiegeCounter.compute(townName, (key, currentCount) -> {
            if (currentCount != null && currentCount > 0) {
                // Since only one active siege per town is allowed, and this siege was active (and thus the latest for the town),
                // we decrement the count.
                int newCount = currentCount - 1;
                plugin.getLogger().info("[StatsManager] Decrementing siege counter for town " + townName + " from " + currentCount + " to " + newCount + " due to deletion of siege " + siegeId);
                return newCount; // This will be 0 if it was the first siege.
            }
            // If currentCount is null or 0, it means the town wasn't in the counter map or its count was already 0.
            // This is unusual if a siege was active for it, but setting/keeping it at 0 is safe.
            plugin.getLogger().info("[StatsManager] Siege counter for town " + townName + " was " + (currentCount == null ? "null" : currentCount) + ". Ensuring count is 0 after deletion of " + siegeId + " (if it was 1).");
            return 0; 
        });

        // Clean up the counter entry if the town's count is now 0
        if (townSiegeCounter.getOrDefault(townName, 0) == 0) {
            townSiegeCounter.remove(townName);
            plugin.getLogger().info("[StatsManager] Removed town " + townName + " from siege counter map as its count is now 0.");
        }

        // Revert player stats
        plugin.getLogger().info("[StatsManager] Reverting player stats for deleted siege " + siegeId + " affecting " + siegeToRemove.getParticipantMetrics().size() + " participants.");
        for (Map.Entry<UUID, SiegeStats.ParticipantMetrics> entry : siegeToRemove.getParticipantMetrics().entrySet()) {
            UUID playerUUID = entry.getKey();
            SiegeStats.ParticipantMetrics siegePerformance = entry.getValue();
            
            PlayerStats globalStats = playerStats.get(playerUUID); 

            if (globalStats != null) {
                String playerNameForLog = globalStats.getLastKnownName() != null ? globalStats.getLastKnownName() : playerUUID.toString();
                plugin.getLogger().fine("[StatsManager] Reverting stats for player " + playerNameForLog + " (UUID: " + playerUUID + ") from deleted siege " + siegeId);
                
                globalStats.subtractStats(siegePerformance); 
                globalStats.removeSiegeParticipation(siegeId); 
            } else {
                plugin.getLogger().warning("[StatsManager] Could not find global stats for player UUID " + playerUUID + " to revert stats from deleted siege " + siegeId);
            }
        }
        
        plugin.getLogger().info("[StatsManager] Player stats reverted for deleted siege " + siegeId + ".");
        saveStats(); 
        plugin.getLogger().info("[StatsManager] Siege " + siegeId + " fully deleted and current stats saved.");
    }
}