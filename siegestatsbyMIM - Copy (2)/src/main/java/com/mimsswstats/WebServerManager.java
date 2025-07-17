package com.mimsswstats;

import com.gmail.goosius.siegewar.SiegeWarAPI;
import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.objects.Siege;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import fi.iki.elonen.NanoHTTPD;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer; // Added import
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.BufferedReader;
import java.io.File; // Added
import java.io.FileInputStream; // Added
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection; // For type hint if Siege object provided direct collections
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class WebServerManager extends NanoHTTPD {

    private final SiegeStatsPlugin plugin;
    private final SiegeStatsManager statsManager;

    public WebServerManager(SiegeStatsPlugin plugin, int port) {
        super(port);
        this.plugin = plugin;
        this.statsManager = plugin.getStatsManager();
        // Removed the info log from here, as it's in SiegeStatsPlugin
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        // plugin.getLogger().info("Web server request: " + uri); // Optional: for verbose logging

        try {
            if (uri.equals("/") || uri.equals("/index.html")) {
                return newFixedLengthResponse(Response.Status.OK, "text/html", loadResource("web/index.html"));
            } else if (uri.equals("/style.css")) {
                return newFixedLengthResponse(Response.Status.OK, "text/css", loadResource("web/style.css"));
            } else if (uri.equals("/script.js")) {
                return newFixedLengthResponse(Response.Status.OK, "application/javascript", loadResource("web/script.js"));
            } else if (uri.equals("/api/playerstats")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", getPlayerStatsJson());
            } else if (uri.equals("/api/siegestats/completed")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", getCompletedSiegeStatsJson());
            } else if (uri.equals("/api/siegestats/active")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", getActiveSiegeStatsJson());
            } else if (uri.equals("/api/livesiegeinfo")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", getLiveSiegeInfoJson());
            }
            plugin.getLogger().warning("Web server: 404 Not Found for URI: " + uri);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Error 404: File not found");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading web resource for URI: " + uri, e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error 500: Internal Server Error");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Unexpected error serving request for URI: " + uri, e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error 500: Internal Server Error");
        }
    }

    /**
     * Loads a resource, prioritizing the external plugin data folder, then falling back to JAR.
     * @param resourceJarPath The path of the resource as it is in the JAR (e.g., "web/index.html")
     * @return The content of the resource as a String.
     * @throws IOException If the resource cannot be found or read.
     */
    private String loadResource(String resourceJarPath) throws IOException {
        // Path to the file in the plugin's data folder (e.g., plugins/SiegeStats/web/index.html)
        File externalFile = new File(plugin.getDataFolder(), resourceJarPath);

        if (externalFile.exists() && externalFile.isFile()) {
            plugin.getLogger().fine("Serving from external file: " + externalFile.getAbsolutePath());
            try (InputStream inputStream = new FileInputStream(externalFile);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } else {
            plugin.getLogger().fine("External file " + externalFile.getAbsolutePath() + " not found. Serving from JAR: " + resourceJarPath);
            InputStream inputStream = plugin.getResource(resourceJarPath);
            if (inputStream == null) {
                plugin.getLogger().warning("Web resource not found in JAR: " + resourceJarPath);
                throw new IOException("Resource not found in JAR: " + resourceJarPath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String getPlayerStatsJson() {
        JSONArray playersArray = new JSONArray();
        statsManager.getAllPlayerStats().forEach((uuid, stats) -> {
            JSONObject pData = new JSONObject();
            pData.put("uuid", uuid.toString());
            pData.put("lastKnownName", stats.getLastKnownName());
            pData.put("totalKills", stats.getTotalKills());
            pData.put("totalDeaths", stats.getTotalDeaths());
            pData.put("totalAssists", stats.getTotalAssists());
            pData.put("totalDamage", stats.getTotalDamage());
            pData.put("totalCaptureTime", stats.getTotalCaptureTime());
            pData.put("totalSiegesParticipated", stats.getTotalSiegesParticipated());
            pData.put("totalWins", stats.getTotalWins());
            pData.put("totalLosses", stats.getTotalLosses());
            pData.put("kdr", stats.getKillDeathRatio());
            pData.put("kda", stats.getKdaRatio());
            pData.put("winLossRatio", stats.getWinLossRatio());
            playersArray.add(pData);
        });
        return playersArray.toJSONString();
    }

    @SuppressWarnings("unchecked")
    private String getCompletedSiegeStatsJson() {
        JSONArray siegesArray = new JSONArray();
        statsManager.getCompletedSieges().forEach((id, stats) -> siegesArray.add(serializeSiegeStatsToJson(stats)));
        return siegesArray.toJSONString();
    }
    
    @SuppressWarnings("unchecked")
    private String getActiveSiegeStatsJson() {
        JSONArray siegesArray = new JSONArray();
        // Accessing the activeSieges map via its new getter from SiegeStatsManager
        statsManager.getActiveSiegesMap().forEach((id, stats) -> siegesArray.add(serializeSiegeStatsToJson(stats)));
        return siegesArray.toJSONString();
    }


    @SuppressWarnings("unchecked")
    private JSONObject serializeSiegeStatsToJson(SiegeStats stats) {
        JSONObject sData = new JSONObject();
        sData.put("siegeId", stats.getSiegeId());
        sData.put("townName", stats.getTownName());
        sData.put("siegeNumber", stats.getSiegeNumber());
        sData.put("startTimeMillis", stats.getStartTime());
        sData.put("endTimeMillis", stats.getEndTime());
        sData.put("isActive", stats.isActive());
        sData.put("attackersWon", stats.getAttackersWon());
        sData.put("defendersWon", stats.getDefendersWon());
        sData.put("durationMinutes", stats.getDurationMinutes());

        JSONArray participantsArray = new JSONArray();
        stats.getParticipantMetrics().forEach((uuid, metrics) -> {
            JSONObject pMetrics = new JSONObject();
            pMetrics.put("uuid", uuid.toString());
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            pMetrics.put("playerName", offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown (" + uuid.toString().substring(0,6) + ")");
            pMetrics.put("kills", metrics.getKills());
            pMetrics.put("deaths", metrics.getDeaths());
            pMetrics.put("assists", metrics.getAssists());
            pMetrics.put("damage", metrics.getDamage());
            pMetrics.put("controlTime", metrics.getControlTime());
            pMetrics.put("side", metrics.getSide().name());
            participantsArray.add(pMetrics);
        });
        sData.put("participants", participantsArray);
        return sData;
    }
    
    @SuppressWarnings("unchecked")
    private String getLiveSiegeInfoJson() {
        JSONArray liveSiegesArray = new JSONArray();

        if (TownyAPI.getInstance() == null || plugin.getServer().getPluginManager().getPlugin("SiegeWar") == null) {
            // plugin.getLogger().warning("TownyAPI or SiegeWar not available for live siege info. Returning empty array.");
            return liveSiegesArray.toJSONString();
        }

        Collection<Siege> currentSiegesFromAPI;
        try {
            currentSiegesFromAPI = SiegeWarAPI.getSieges();
             if (currentSiegesFromAPI == null) {
                // plugin.getLogger().warning("SiegeWarAPI.getSieges() returned null. No live siege info available.");
                return liveSiegesArray.toJSONString();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error calling SiegeWarAPI.getSieges()", e);
            return liveSiegesArray.toJSONString();
        }


        for (Siege currentSiege : currentSiegesFromAPI) {
            if (currentSiege != null && currentSiege.getStatus() != null && currentSiege.getStatus().isActive()) {
                JSONObject siegeInfo = new JSONObject();
                Town town = currentSiege.getTown();
                String townName = (town != null && town.getName() != null) ? town.getName() : "Unknown Town";
                
                String internalSiegeId = plugin.getSiegeListener().getActiveSiegeId(currentSiege);
                if (internalSiegeId == null) {
                    for(Map.Entry<String, SiegeStats> entry : statsManager.getActiveSiegesMap().entrySet()){
                        if(entry.getValue().getTownName().equalsIgnoreCase(townName)){
                            internalSiegeId = entry.getKey();
                            break;
                        }
                    }
                }

                siegeInfo.put("id", internalSiegeId != null ? internalSiegeId : "sw-" + townName.toLowerCase().replaceAll("[^a-z0-9_]", "") + "-" + (System.currentTimeMillis()%10000));
                siegeInfo.put("townName", townName);
                siegeInfo.put("balance", currentSiege.getSiegeBalance());

                String winningSideText;
                if (currentSiege.getSiegeBalance() >= 1) {
                    winningSideText = "ATTACKERS";
                } else {
                    winningSideText = "DEFENDERS";
                }
                siegeInfo.put("currentlyWinning", winningSideText);

                int attackersCount = 0;
                int defendersCount = 0;
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    if (player == null || !player.isOnline()) continue;
                    
                    SiegeSide side = SiegeSide.getPlayerSiegeSide(currentSiege, player);
                    if (side == SiegeSide.ATTACKERS) {
                        attackersCount++;
                    } else if (side == SiegeSide.DEFENDERS) {
                        defendersCount++;
                    }
                }
                siegeInfo.put("attackersCount", attackersCount);
                siegeInfo.put("defendersCount", defendersCount);

                liveSiegesArray.add(siegeInfo);
            }
        }
        return liveSiegesArray.toJSONString();
    }
}