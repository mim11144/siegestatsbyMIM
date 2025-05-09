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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public class SiegeStatsManager {
    private final SiegeStatsPlugin plugin;
    public static final long ASSIST_WINDOW_MS = 15000; // 15 seconds assist window

    // --- Data Storage ---
    // Use UUID as key for player-specific data
    private ConcurrentHashMap<UUID, PlayerStats> playerStats;
    // Town counter can remain keyed by name as towns are less likely to change UUIDs mid-count?
    // Or change this to UUID too if town name changes are frequent and need robust counting. Let's keep String for now.
    private ConcurrentHashMap<String, Integer> townSiegeCounter;
    // Siege ID (generated string) -> SiegeStats object
    private ConcurrentHashMap<String, SiegeStats> activeSieges;
    private ConcurrentHashMap<String, SiegeStats> completedSieges;
    private final File statsFile;
    // Assist Tracking: Victim UUID -> Map<Attacker UUID, Timestamp>
    private final Map<UUID, Map<UUID, Long>> recentDamagers;

    public SiegeStatsManager(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
        this.statsFile = new File(plugin.getDataFolder(), "stats.json");
        this.recentDamagers = new ConcurrentHashMap<>();
        initializeData();
        loadStats();
    }

    private void initializeData() {
        this.playerStats = new ConcurrentHashMap<>();
        this.townSiegeCounter = new ConcurrentHashMap<>();
        this.activeSieges = new ConcurrentHashMap<>();
        this.completedSieges = new ConcurrentHashMap<>();
        this.recentDamagers.clear();
    }

    // --- Core Logic Methods ---

    public String startNewSiege(Siege siege) {
        plugin.getLogger().info("[DEBUG] Manager: startNewSiege called.");
        if (siege == null || siege.getTown() == null) {
            plugin.getLogger().severe("[DEBUG] Manager: Attempted to start siege with null Siege or Town object! Returning null.");
            return null;
        }
        plugin.getLogger().info("[DEBUG] Manager: Siege and Town objects are not null.");

        // Use Town Name for Siege ID generation and counter for now
        // If town name changes cause issues, this needs changing to use Town UUID
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
            return null; // Tracking won't work if put fails
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
            saveStats(); // Save stats when a siege completes
        } else {
            plugin.getLogger().warning("Tried to end siege with ID " + siegeId + ", but it wasn't found in activeSieges map.");
        }
        // Consider clearing recentDamagers map for players involved in this siege?
        // For now, relying on expiration within getRecentDamagersForAssist.
    }

    // --- Assist Tracking Methods ---

    public void addRecentDamager(UUID victimUUID, UUID damagerUUID) {
        if (victimUUID.equals(damagerUUID)) return;
        Map<UUID, Long> victimDamageMap = recentDamagers.computeIfAbsent(victimUUID, k -> new ConcurrentHashMap<>());
        victimDamageMap.put(damagerUUID, System.currentTimeMillis());
    }

    public Map<UUID, Long> getRecentDamagersForAssist(UUID victimUUID) {
        Map<UUID, Long> victimDamageMap = recentDamagers.get(victimUUID);
        if (victimDamageMap == null) {
            return Collections.emptyMap(); // Return immutable empty map
        }
        long cutoffTime = System.currentTimeMillis() - ASSIST_WINDOW_MS;
        Map<UUID, Long> validAssisters = new ConcurrentHashMap<>(); // Use concurrent for modification below

        // Iterate and clean up in one pass
        victimDamageMap.entrySet().removeIf(entry -> {
            if (entry.getValue() >= cutoffTime) {
                validAssisters.put(entry.getKey(), entry.getValue());
                return false; // Keep in validAssisters, don't remove from original yet
            } else {
                return true; // Mark for removal from original map
            }
        });

        if (victimDamageMap.isEmpty()) {
            recentDamagers.remove(victimUUID);
        }
        return validAssisters;
    }

    // Optional: Method to explicitly clear damagers for a player if needed elsewhere
    public void clearRecentDamagers(UUID victimUUID) {
        recentDamagers.remove(victimUUID);
    }

    // --- Stat Recording ---

    /** Primary method using UUID */
    public void recordSiegeAction(String siegeId, UUID playerUUID, int kills, int deaths, double damage, double controlTimeMinutes, int assists) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
        String currentName = offlinePlayer.getName();
        if (currentName == null) currentName = playerUUID.toString(); // Fallback

        SiegeStats siegeStats = activeSieges.get(siegeId);
        if (siegeStats == null) {
            plugin.getLogger().warning("Attempted to record action for non-active siege ID: " + siegeId + " for player " + currentName);
            return;
        }

        SiegeSide playerSide = getPlayerSide(siegeStats, playerUUID, currentName);

        plugin.getLogger().info(String.format("[DEBUG] Manager recording action for UUID %s (Name: %s, Side: %s), Siege: %s, K:%d, D:%d, A:%d, DMG:%.1f, CapT:%.4f",
                playerUUID, currentName, playerSide, siegeId, kills, deaths, assists, damage, controlTimeMinutes));

        // 1. Update siege-specific metrics (uses UUID key)
        siegeStats.recordPlayerAction(playerUUID, kills, deaths, damage, controlTimeMinutes, assists, playerSide);

        // 2. Update global player stats (uses UUID key)
        PlayerStats globalPlayerStats = getPlayerStats(playerUUID, currentName);
        if (globalPlayerStats != null) {
            globalPlayerStats.updateName(currentName); // Keep name somewhat updated

            // Logging updates
            if (controlTimeMinutes > 0.0001) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding CapT %.4f", currentName, controlTimeMinutes)); }
            if (assists > 0) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding Assist %d", currentName, assists)); }
            if (kills > 0) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding Kill %d", currentName, kills)); }
            if (deaths > 0) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding Death %d", currentName, deaths)); }
            if (damage > 0) { plugin.getLogger().info(String.format("[DEBUG] Updating GLOBAL stats for %s: Adding Damage %.1f", currentName, damage)); }


            // Add stats
            if (kills > 0) globalPlayerStats.addKills(kills);
            if (deaths > 0) globalPlayerStats.addDeaths(deaths);
            if (damage > 0) globalPlayerStats.addDamage(damage);
            if (controlTimeMinutes > 0.0001) globalPlayerStats.addCaptureTime(controlTimeMinutes);
            if (assists > 0) globalPlayerStats.addAssist(assists);
        } else {
            plugin.getLogger().severe("Failed to get/create global PlayerStats for " + currentName + " (UUID: " + playerUUID + ")!");
        }
    }

    // Overload without assists
    public void recordSiegeAction(String siegeId, UUID playerUUID, int kills, int deaths, double damage, double controlTimeMinutes) {
        recordSiegeAction(siegeId, playerUUID, kills, deaths, damage, controlTimeMinutes, 0);
    }

    // Helper to determine player side robustly
    private SiegeSide getPlayerSide(SiegeStats siegeStats, UUID playerUUID, String currentName) {
        SiegeSide playerSide = SiegeSide.NOBODY;
        Player onlinePlayer = Bukkit.getPlayer(playerUUID); // Check if online

        if (onlinePlayer != null) {
            Town town = TownyAPI.getInstance().getTown(siegeStats.getTownName());
            if (town != null) {
                Siege siege = SiegeController.getSiegeByTownUUID(town.getUUID()); // Use correct method
                if (siege != null) {
                    playerSide = SiegeSide.getPlayerSiegeSide(siege, onlinePlayer);
                }
            }
        }

        // If side couldn't be determined (offline or missing objects), try stored metrics
        if (playerSide == SiegeSide.NOBODY) {
            SiegeStats.ParticipantMetrics existingMetrics = siegeStats.getParticipantMetrics().get(playerUUID);
            if (existingMetrics != null && existingMetrics.getSide() != SiegeSide.NOBODY) {
                playerSide = existingMetrics.getSide();
            }
        }
        return playerSide;
    }

    // Updated addSiegeParticipant to use UUIDs
    public void addSiegeParticipant(Siege siege, Player player) {
        if (siege == null || player == null) return;
        String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);
        if (siegeId == null) return;

        SiegeStats siegeStats = activeSieges.get(siegeId);
        if (siegeStats == null) return;

        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();

        // Update global stats participation using UUID
        PlayerStats globalPlayerStats = getPlayerStats(playerUUID, playerName);
        if (globalPlayerStats != null) {
            globalPlayerStats.updateName(playerName);
            globalPlayerStats.addSiegeParticipation(siegeId);
        }

        // Update siege-specific metrics using UUID
        SiegeSide playerSide = SiegeSide.getPlayerSiegeSide(siege, player);
        SiegeStats.ParticipantMetrics metrics = siegeStats.getParticipantMetrics().computeIfAbsent(playerUUID, k -> new SiegeStats.ParticipantMetrics());
        metrics.setSide(playerSide);
        plugin.getLogger().fine("[DEBUG] Added/Updated participant " + playerName + " (UUID:" + playerUUID + ") for siege " + siegeId + " with side " + playerSide);
    }


    // --- Data Access Methods ---

    public PlayerStats getPlayerStats(UUID playerUUID, String currentName) {
        // Pass name only needed for the initial creation if the player is new
        return playerStats.computeIfAbsent(playerUUID, k -> new PlayerStats(k, currentName));
    }

    public PlayerStats getPlayerStatsByName(String playerName) {
        // Find UUID from name - uses deprecated method but necessary for lookup by name
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
        if (op == null || (!op.hasPlayedBefore() && !op.isOnline())) {
            return null;
        }
        UUID playerUUID = op.getUniqueId();
        // Get stats using UUID, providing name for potential creation/update
        PlayerStats stats = getPlayerStats(playerUUID, playerName);
        if (stats != null) {
            stats.updateName(playerName); // Ensure name is current
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
            pData.put("lastKnownName", stats.getLastKnownName()); // Store name
            pData.put("totalKills", stats.getTotalKills());
            pData.put("totalDeaths", stats.getTotalDeaths());
            pData.put("totalAssists", stats.getTotalAssists());
            pData.put("totalDamage", stats.getTotalDamage());
            pData.put("totalCaptureTime", stats.getTotalCaptureTime());
            pData.put("totalSiegesParticipated", stats.getTotalSiegesParticipated());
            pData.put("totalWins", stats.getTotalWins());
            pData.put("totalLosses", stats.getTotalLosses());
            playersJson.put(uuid.toString(), pData); // Use UUID String as key
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
            plugin.getLogger().severe("Could not create data folder: " + parentDir.getPath());
 return;
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
        stats.getParticipantMetrics().forEach((uuid, metrics) -> { // Key is UUID
            JSONObject mData = new JSONObject();
            mData.put("kills", metrics.getKills());
            mData.put("deaths", metrics.getDeaths());
            mData.put("assists", metrics.getAssists());
            mData.put("damage", metrics.getDamage());
            mData.put("controlTime", metrics.getControlTime());
            mData.put("side", metrics.getSide().name());
            participants.put(uuid.toString(), mData); // Use UUID String as key
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

            // Load Player Stats (keyed by UUID string)
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
                        // Use setters to load values
                        stats.setTotalKills(((Long) pData.getOrDefault("totalKills", 0L)).intValue());
                        stats.setTotalDeaths(((Long) pData.getOrDefault("totalDeaths", 0L)).intValue());
                        stats.setTotalAssists(((Long) pData.getOrDefault("totalAssists", 0L)).intValue());
                        stats.setTotalDamage((Double) pData.getOrDefault("totalDamage", 0.0));
                        stats.setTotalCaptureTime((Double) pData.getOrDefault("totalCaptureTime", 0.0));
                        stats.setTotalWins(((Long) pData.getOrDefault("totalWins", 0L)).intValue());
                        stats.setTotalLosses(((Long) pData.getOrDefault("totalLosses", 0L)).intValue());
                        stats.setTotalSiegesParticipated(((Long) pData.getOrDefault("totalSiegesParticipated", 0L)).intValue());
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().severe("Skipping invalid UUID in playerStats key: " + uuidString);
                    }
                });
            }
            // Load Town Counters
            JSONObject counters = (JSONObject) root.get("townSiegeCounter");
            if (counters != null) counters.forEach((t, c) -> townSiegeCounter.put((String)t, ((Long)c).intValue()));
            // Load Active Sieges
            JSONObject activeS = (JSONObject) root.get("activeSieges");
            if (activeS != null) activeS.forEach((id, sD) -> { SiegeStats s = deserializeSiegeStats((String)id, (JSONObject)sD); if (s != null) (s.isActive() ? activeSieges : completedSieges).put((String)id, s);
 });
            // Load Completed Sieges
            JSONObject completedS = (JSONObject) root.get("completedSieges");
            if (completedS != null) completedS.forEach((id, sD) -> { SiegeStats s = deserializeSiegeStats((String)id, (JSONObject)sD); if (s != null && !s.isActive()) completedSieges.putIfAbsent((String)id, s);
 });

            plugin.getLogger().info("Siege stats loaded successfully (UUID keys).");
        } catch (IOException | ParseException | ClassCastException | NullPointerException e) {
            plugin.getLogger().severe("Could not load siege stats: " + e.getMessage());
            initializeData();
        }
    }

    private SiegeStats deserializeSiegeStats(String siegeId, JSONObject sData) {
        try {
            String townName = (String) sData.get("townName");
            int siegeNumber = ((Long) sData.get("siegeNumber")).intValue();
            SiegeStats stats = new SiegeStats(townName, siegeNumber, siegeId);
            stats.setStartTime((Long) sData.getOrDefault("startTimeMillis", 0L));
            stats.setEndTime((Long) sData.getOrDefault("endTimeMillis", -1L));
            stats.setActive((Boolean) sData.getOrDefault("isActive", false));
            stats.setAttackersWon((Boolean) sData.getOrDefault("attackersWon", false));
            stats.setDefendersWon((Boolean) sData.getOrDefault("defendersWon", false));

            JSONObject participants = (JSONObject) sData.get("participantMetrics");
            if (participants != null) {
                participants.forEach((uuidString, mDataObj) -> { // Key is UUID String
                    JSONObject mData = (JSONObject) mDataObj;
                    try {
                        UUID playerUUID = UUID.fromString((String) uuidString);
                        SiegeStats.ParticipantMetrics metrics = stats.getParticipantMetrics().computeIfAbsent(playerUUID, k -> new SiegeStats.ParticipantMetrics());
                        // Load stats using adders
                        metrics.addKills(((Long) mData.getOrDefault("kills", 0L)).intValue());
                        metrics.addDeaths(((Long) mData.getOrDefault("deaths", 0L)).intValue());
                        metrics.addAssist(((Long) mData.getOrDefault("assists", 0L)).intValue());
                        metrics.addDamage((Double) mData.getOrDefault("damage", 0.0));
                        metrics.addControlTime((Double) mData.getOrDefault("controlTime", 0.0));
                        String sideName = (String) mData.getOrDefault("side", "NOBODY");
                        metrics.setSide(SiegeSide.valueOf(sideName.toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().severe("Skipping participant with invalid UUID/Side: " + uuidString + " / " + mData.get("side"));
                    }
                });
            }
            return stats;
        } catch (Exception e) {
            plugin.getLogger().severe("Error deserializing SiegeStats for ID " + siegeId + ": " + e.getMessage());
            return null;
        }
    }

    // --- Admin/Debug Methods ---
    public synchronized void resetAllStats() {
        plugin.getLogger().info("Resetting all siege stats...");
        initializeData();
        saveStats();
        plugin.getLogger().info("All siege stats have been reset.");
    }

    public void debugDumpStats(CommandSender sender) {
        sender.sendMessage("§e--- SiegeStats Debug Dump (UUID Based) ---");
        sender.sendMessage("§bActive Sieges (" + activeSieges.size() + ")");
        activeSieges.forEach((id, stats) -> sender.sendMessage("§7 - " + id + " (Town: " + stats.getTownName() + ", Parts: " + stats.getParticipantMetrics().size() + ")"));
        sender.sendMessage("§bCompleted Sieges (" + completedSieges.size() + ")");
        completedSieges.keySet().stream().limit(5).forEach(id -> sender.sendMessage("§7 - " + id));
        if (completedSieges.size() > 5) sender.sendMessage("§7   (...and " + (completedSieges.size() - 5) + " more)");
        sender.sendMessage("§bTown Counters (" + townSiegeCounter.size() + ")");
        townSiegeCounter.forEach((town, count) -> sender.sendMessage("§7 - " + town + ": " + count));
        sender.sendMessage("§bPlayer Stats (" + playerStats.size() + ")");
        playerStats.entrySet().stream().limit(10).forEach(entry -> {
            UUID uuid = entry.getKey();
            PlayerStats ps = entry.getValue();
            // Display last known name and UUID for clarity
            sender.sendMessage(String.format("§7 - %s [§e%s§7] (K:%d D:%d A:%d DMG:%.1f CapT:%.2fm W:%d L:%d KDA:%.2f)",
                    ps.getLastKnownName(), uuid.toString().substring(0, 6), // Short UUID
                    ps.getTotalKills(), ps.getTotalDeaths(), ps.getTotalAssists(), ps.getTotalDamage(), ps.getTotalCaptureTime(),
                    ps.getTotalWins(), ps.getTotalLosses(), ps.getKdaRatio()));
        });
        if (playerStats.size() > 10) sender.sendMessage("§7   (...and " + (playerStats.size() - 10) + " more players)");
        sender.sendMessage("§e--- End Debug Dump ---");
    }
}
