package com.mimsswstats;

import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.objects.Siege;
import com.mimsswstats.PlayerStats;
import com.mimsswstats.SiegeStats;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SiegeStatsManager {
    private final SiegeStatsPlugin plugin;
    private ConcurrentHashMap<String, Integer> townSiegeCounter;
    private ConcurrentHashMap<String, SiegeStats> activeSieges;
    private ConcurrentHashMap<String, SiegeStats> completedSieges;
    private ConcurrentHashMap<String, PlayerStats> playerStats;
    private Map<Siege, List<Player>> siegeParticipants;
    public SiegeStatsManager(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
        this.townSiegeCounter = new ConcurrentHashMap<>();
        this.activeSieges = new ConcurrentHashMap<>();
        this.completedSieges = new ConcurrentHashMap<>();
        this.playerStats = new ConcurrentHashMap<>();
        loadPlayerStats();
    }


    public void savePlayerStats() {
        JSONObject rootStats = new JSONObject();

        JSONObject playerStatsJson = new JSONObject();
        for (Map.Entry<String, PlayerStats> entry : playerStats.entrySet()) {
            JSONObject stats = new JSONObject();
            PlayerStats ps = entry.getValue();
            stats.put("kills", ps.getKills());
            stats.put("deaths", ps.getDeaths());
            stats.put("damage", ps.getTotalDamage());
            stats.put("totalSieges", ps.getTotalSieges());
            stats.put("totalWins", ps.getTotalWins());
            stats.put("totalLosses", ps.getTotalLosses());
            playerStatsJson.put(entry.getKey(), stats);
        }
        rootStats.put("playerStats", playerStatsJson);

        JSONObject siegeCountersJson = new JSONObject();
        townSiegeCounter.forEach((town, count) ->
                siegeCountersJson.put(town, count));
        rootStats.put("siegeCounters", siegeCountersJson);

        JSONObject siegesJson = new JSONObject();
        JSONObject activeSiegesJson = new JSONObject();
        JSONObject completedSiegesJson = new JSONObject();
        for (Map.Entry<String, SiegeStats> entry : activeSieges.entrySet()) {
            activeSiegesJson.put(entry.getKey(), serializeSiegeStats(entry.getValue()));
        }

        for (Map.Entry<String, SiegeStats> entry : completedSieges.entrySet()) {
            completedSiegesJson.put(entry.getKey(), serializeSiegeStats(entry.getValue()));
        }

        siegesJson.put("active", activeSiegesJson);
        siegesJson.put("completed", completedSiegesJson);
        rootStats.put("sieges", siegesJson);

        File pluginDir = new File("plugins/SiegeStats");
        pluginDir.mkdirs();

        try (FileWriter fw = new FileWriter(new File(pluginDir, "stats.json"))) {
            fw.write(rootStats.toJSONString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void resetAllStats() {
        this.playerStats.clear();
        this.townSiegeCounter.clear();
        this.activeSieges.clear();
        this.completedSieges.clear();

        savePlayerStats();
    }
    private JSONObject serializeSiegeStats(SiegeStats siegeStats) {
        JSONObject stats = new JSONObject();
        stats.put("townName", siegeStats.getTownName());
        stats.put("siegeNumber", siegeStats.getSiegeNumber());
        stats.put("startTime", siegeStats.getStartTime());
        stats.put("active", siegeStats.isActive());

        JSONObject participantsJson = new JSONObject();
        siegeStats.getParticipantStats().forEach((playerName, playerStats) -> {
            JSONObject participantStats = new JSONObject();
            participantStats.put("kills", playerStats.getKills());
            participantStats.put("deaths", playerStats.getDeaths());
            participantStats.put("damage", playerStats.getTotalDamage());
            participantsJson.put(playerName, participantStats);
        });
        stats.put("participants", participantsJson);

        return stats;
    }

    private SiegeStats deserializeSiegeStats(JSONObject statsJson) {
        String townName = (String) statsJson.get("townName");
        int siegeNumber = ((Long) statsJson.get("siegeNumber")).intValue();
        SiegeStats siegeStats = new SiegeStats(townName, siegeNumber);

        siegeStats.setStartTime(((Long) statsJson.get("startTime")));
        siegeStats.setActive((Boolean) statsJson.get("active"));

        JSONObject participantsJson = (JSONObject) statsJson.get("participants");
        if (participantsJson != null) {
            participantsJson.forEach((playerName, statsObj) -> {
                JSONObject participantStats = (JSONObject) statsObj;
                String playerNameStr = (String) playerName;
                PlayerStats stats = new PlayerStats(playerNameStr);
                stats.addKills(((Long) participantStats.get("kills")).intValue());
                stats.addDeaths(((Long) participantStats.get("deaths")).intValue());
                stats.addDamage((Double) participantStats.get("damage"));
                siegeStats.getParticipantStats().put(playerNameStr, stats);
            });
        }

        return siegeStats;
    }
    public void recordSiegeAction(String siegeId, String playerName, int kills, int deaths, double damage) {
        plugin.getLogger().info("[DEBUG] Recording action for " + playerName + " in siege " + siegeId +
                " - Kills: " + kills + ", Deaths: " + deaths + ", Damage: " + damage);

        SiegeStats siegeStats = activeSieges.get(siegeId);
        if (siegeStats != null) {
            siegeStats.recordPlayerAction(playerName, kills, deaths, damage);
            plugin.getLogger().info("[DEBUG] Updated siege stats for " + playerName);
            plugin.getLogger().info("[DEBUG] Current participants: " + siegeStats.getParticipantStats().keySet());
        } else {
            plugin.getLogger().warning("[DEBUG] Failed to find siege with ID: " + siegeId);
            siegeStats = completedSieges.get(siegeId);
            if (siegeStats != null) {
                siegeStats.recordPlayerAction(playerName, kills, deaths, damage);
                plugin.getLogger().info("[DEBUG] Updated completed siege stats for " + playerName);
            } else {
                plugin.getLogger().warning("[DEBUG] Siege not found in active or completed sieges!");
            }
        }

        PlayerStats playerStats = this.playerStats.computeIfAbsent(playerName,
                k -> new PlayerStats(playerName));

        synchronized (playerStats) {
            playerStats.addKills(kills);
            playerStats.addDeaths(deaths);
            playerStats.addDamage(damage);
        }

        savePlayerStats();
    }

    public void endSiege(String siegeId) {
        SiegeStats stats = activeSieges.remove(siegeId);
        if (stats != null) {
            stats.setActive(false);
            completedSieges.put(siegeId, stats);
        }
    }

    private String generateSiegeId(String townName, int siegeNumber) {
        return townName.toLowerCase() + "_" + siegeNumber;
    }

    public SiegeStats getSiegeStats(String townName, int siegeNumber) {
        String siegeId = townName.toLowerCase() + "_" + siegeNumber;

        SiegeStats stats = activeSieges.get(siegeId);
        if (stats != null) {
            return stats;
        }

        return completedSieges.get(siegeId);
    }

    public PlayerStats getPlayerStats(String playerName) {
        return playerStats.get(playerName);
    }
    public Integer getTownSiegeCount(String townName) {
        return townSiegeCounter.get(townName.toLowerCase());
    }

    public void debugActiveSieges(CommandSender sender) {
        sender.sendMessage("§7[Debug] Active Sieges:");
        if (activeSieges.isEmpty()) {
            sender.sendMessage("§7  - No active sieges");
        } else {
            activeSieges.forEach((id, stats) -> {
                sender.sendMessage(String.format("§7  - ID: %s, Town: %s, Number: %d",
                        id, stats.getTownName(), stats.getSiegeNumber()));
            });
        }

        sender.sendMessage("§7[Debug] Completed Sieges:");
        if (completedSieges.isEmpty()) {
            sender.sendMessage("§7  - No completed sieges");
        } else {
            completedSieges.forEach((id, stats) -> {
                sender.sendMessage(String.format("§7  - ID: %s, Town: %s, Number: %d",
                        id, stats.getTownName(), stats.getSiegeNumber()));
            });
        }

        sender.sendMessage("§7[Debug] Town Siege Counters:");
        if (townSiegeCounter.isEmpty()) {
            sender.sendMessage("§7  - No town siege counts");
        } else {
            townSiegeCounter.forEach((town, count) -> {
                sender.sendMessage(String.format("§7  - Town: %s, Count: %d", town, count));
            });
        }
    }

    public ConcurrentHashMap<String, PlayerStats> getPlayerStats() {
        return playerStats;
    }
    public void addSiegeParticipant(Siege siege, Player player) {
        if (siege == null || player == null) return;

        siegeParticipants.computeIfAbsent(siege, k -> new ArrayList<>())
                .add(player);

        PlayerStats stats = playerStats.computeIfAbsent(player.getName(),
                k -> new PlayerStats(player.getName()));

        synchronized (stats) {
            if (!stats.hasParticipatedInSiege(String.valueOf(siege))) {
                stats.addSiegeParticipation(String.valueOf(siege));
                savePlayerStats();
            }
        }
    }

    public void updatePlayerStats(String playerName, SiegePerformance performance) {
        PlayerStats stats = getPlayerStats(playerName);

        if (stats == null) {
            stats = new PlayerStats(playerName);
            playerStats.put(playerName, stats);
        }

        stats.addKills(performance.getKills());
        stats.addDeaths(performance.getDeaths());
        stats.addDamage(performance.getDamage());

        if (performance.isWon()) {
            stats.addWin();
        } else {
            stats.addLoss();
        }

        stats.addLastPerformance(performance);
        savePlayerStats();
    }

    public String startNewSiege(Siege siege) {
        if (siege == null || siege.getTown() == null) {
            plugin.getLogger().warning("[DEBUG] Invalid siege or town object in startNewSiege");
            return null;
        }

        String townName = siege.getTown().getName().toLowerCase();
        plugin.getLogger().info("[DEBUG] Starting new siege for town: " + townName);

        int siegeNumber = townSiegeCounter.compute(townName, (k, v) -> v == null ? 1 : v + 1);
        plugin.getLogger().info("[DEBUG] Assigned siege number: " + siegeNumber);

        String siegeId = generateSiegeId(townName, siegeNumber);
        plugin.getLogger().info("[DEBUG] Generated siege ID: " + siegeId);

        SiegeStats stats = new SiegeStats(townName, siegeNumber);
        if (stats == null) {  plugin.getLogger().warning("[DEBUG] Failed to create SiegeStats object!");
            return null;
        }

        activeSieges.put(siegeId, stats);

        if (!activeSieges.containsKey(siegeId)) {
            plugin.getLogger().warning("[DEBUG] Failed to store siege in activeSieges map!");
            return null;
        }

        plugin.getLogger().info("[DEBUG] Siege registered successfully. Active sieges: " +
                activeSieges.size());
        return siegeId;
    }

    public void loadPlayerStats() {
        File statsFile = new File("plugins/SiegeStats", "stats.json");
        if (!statsFile.exists()) return;

        try {
            JSONParser parser = new JSONParser();
            JSONObject rootStats = (JSONObject) parser.parse(new FileReader(statsFile));
            JSONObject playerStatsJson = (JSONObject) rootStats.get("playerStats");
            if (playerStatsJson != null) {
                for (Object key : playerStatsJson.keySet()) {
                    String playerName = (String) key;
                    JSONObject stats = (JSONObject) playerStatsJson.get(playerName);

                    PlayerStats ps = new PlayerStats(playerName);
                    ps.addKills(((Long) stats.get("kills")).intValue());
                    ps.addDeaths(((Long) stats.get("deaths")).intValue());
                    ps.addDamage((Double) stats.get("damage"));
                    ps.setTotalSieges(((Long) stats.get("totalSieges")).intValue());
                    ps.setTotalWins(((Long) stats.get("totalWins")).intValue());
                    ps.setTotalLosses(((Long) stats.get("totalLosses")).intValue());

                    this.playerStats.put(playerName, ps);
                }
            }

            JSONObject siegeCountersJson = (JSONObject) rootStats.get("siegeCounters");
            if (siegeCountersJson != null) {
                siegeCountersJson.forEach((town, count) ->
                        townSiegeCounter.put((String) town, ((Long) count).intValue()));
            }
            JSONObject siegesJson = (JSONObject) rootStats.get("sieges");
            if (siegesJson != null) {
                JSONObject activeSiegesJson = (JSONObject) siegesJson.get("active");
                if (activeSiegesJson != null) {
                    activeSiegesJson.forEach((siegeId, statsObj) ->
                            activeSieges.put((String)siegeId, deserializeSiegeStats((JSONObject)statsObj)));
                }

                JSONObject completedSiegesJson = (JSONObject) siegesJson.get("completed");
                if (completedSiegesJson != null) {
                    completedSiegesJson.forEach((siegeId, statsObj) ->
                            completedSieges.put((String)siegeId, deserializeSiegeStats((JSONObject)statsObj)));
                }
            }
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
    }
}
