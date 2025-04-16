package com.mimsswstats;

import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.events.BattleSessionEndedEvent;
import com.gmail.goosius.siegewar.events.BannerControlSessionEndedEvent;
import com.gmail.goosius.siegewar.events.SiegeEndEvent;
import com.gmail.goosius.siegewar.objects.Siege;

import com.palmergames.bukkit.towny.object.Resident;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BannerControlListener implements Listener {
    private final SiegeStatsPlugin plugin;
    private final Map<String, Map<UUID, Long>> controllingPlayersBySiege;

    public BannerControlListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
        this.controllingPlayersBySiege = new ConcurrentHashMap<>();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onBannerControlSessionEnded(BannerControlSessionEndedEvent event) {
        Siege siege = event.getSiege();
        String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);
        long eventTime = System.currentTimeMillis();

        if (siegeId == null || siege == null) {
            plugin.getLogger().warning("[BannerControlListener] BannerControlSessionEndedEvent for non-tracked or null siege?");
            return;
        }
        plugin.getLogger().info("[DEBUG] BannerControlSessionEndedEvent triggered for siege " + siegeId);

        List<Resident> officialControllers = siege.getBannerControllingResidents();
        Map<UUID, Long> internalControllers = controllingPlayersBySiege.computeIfAbsent(siegeId, k -> new ConcurrentHashMap<>());

        if (officialControllers == null || officialControllers.isEmpty()) {
            // Official list empty - handle as loss of control.
            plugin.getLogger().info("[DEBUG] Official controller list EMPTY after BannerControlSessionEndedEvent for siege " + siegeId + ". Assuming control lost.");
            if (!internalControllers.isEmpty()) {
                finalizeAndClearSiegeControllers(siegeId, eventTime, null, null); // Log time for who we thought was controlling
            }
            // Ensure map entry is removed if it exists but became empty indirectly
            if (internalControllers.isEmpty() && controllingPlayersBySiege.containsKey(siegeId)) {
                controllingPlayersBySiege.remove(siegeId);
            }
            return;
        }

        // --- Someone successfully captured ---
        Resident lastControllerData = officialControllers.get(officialControllers.size() - 1);
        UUID newControllerUUID = lastControllerData.getUUID();
        OfflinePlayer newControllerOffline = Bukkit.getOfflinePlayer(newControllerUUID);
        String newControllerName = newControllerOffline.getName();
        // We need the Player object to check side, even if potentially offline fetching name first
        Player newController = lastControllerData.getPlayer(); // Might be null if offline

        if (newControllerName == null) {
            plugin.getLogger().severe("[BannerControlListener] Could not resolve player name for new controller UUID: " + newControllerUUID + " in siege " + siegeId);
            finalizeAndClearSiegeControllers(siegeId, eventTime, null, null);
            return;
        }

        // *** Get Side of New Controller ***
        // Ensure player object is available; if offline, cannot determine side easily
        if (newController == null) {
            plugin.getLogger().warning("[BannerControlListener] New controller " + newControllerName + " is offline. Cannot determine side. Treating as enemy capture for safety.");
            // Default to clearing previous controllers if side cannot be determined.
            if (!internalControllers.isEmpty() && !internalControllers.containsKey(newControllerUUID)) {
                finalizeAndClearSiegeControllers(siegeId, eventTime, newControllerUUID, newControllerName);
                internalControllers = controllingPlayersBySiege.computeIfAbsent(siegeId, k -> new ConcurrentHashMap<>()); // Re-fetch map
                internalControllers.put(newControllerUUID, eventTime);
                plugin.getLogger().info("[DEBUG] Added offline controller " + newControllerName + " after clearing previous.");
            } else if (internalControllers.isEmpty()) {
                internalControllers.put(newControllerUUID, eventTime);
                plugin.getLogger().info("[DEBUG] Added offline controller " + newControllerName + " as first controller.");
            }
            return;
        }
        // *** VERIFY *** Correct method to get player's side
        SiegeSide newControllerSide = SiegeSide.getPlayerSiegeSide(siege, newController);
        plugin.getLogger().info("[DEBUG] Siege " + siegeId + " official controller: " + newControllerName + " (" + newControllerSide + ")");


        if (internalControllers.isEmpty()) {
            // First capture for this siege
            plugin.getLogger().info("[DEBUG] First capture for siege " + siegeId + " by " + newControllerName);
            internalControllers.put(newControllerUUID, eventTime);
            plugin.getLogger().info("[DEBUG] Added " + newControllerName + " to controllingPlayers.");
        } else {
            // Someone was controlling before. Check if it's the same player or different side.
            if (internalControllers.containsKey(newControllerUUID)) {
                plugin.getLogger().info("[DEBUG] Player " + newControllerName + " was already tracked. No change.");
                // Optional: Clean up any other entries if somehow multiple were tracked before side check
                // internalControllers.entrySet().removeIf(entry -> !entry.getKey().equals(newControllerUUID));
                return; // No change needed
            }

            // It's a different player. Check sides.
            // Get side of *any* of the previous controllers (assuming allies share side)
            UUID previousControllerSampleUUID = internalControllers.keySet().iterator().next();
            Player previousControllerSample = Bukkit.getPlayer(previousControllerSampleUUID); // Might be null
            SiegeSide previousControllerSide = SiegeSide.NOBODY;
            if (previousControllerSample != null) {
                // *** VERIFY *** Correct method to get player's side
                previousControllerSide = SiegeSide.getPlayerSiegeSide(siege, previousControllerSample);
            } else {
                plugin.getLogger().warning("[BannerControlListener] Could not get Player object for previous controller " + previousControllerSampleUUID + " to check side. Assuming different sides.");
            }

            plugin.getLogger().info("[DEBUG] Comparing sides: New=" + newControllerSide + ", Previous=" + previousControllerSide);

            // If sides are different (and valid), finalize previous, then add new.
            if (newControllerSide != previousControllerSide && newControllerSide != SiegeSide.NOBODY && previousControllerSide != SiegeSide.NOBODY) {
                plugin.getLogger().info("[DEBUG] Enemy capture detected. Finalizing time for previous side.");
                finalizeAndClearSiegeControllers(siegeId, eventTime, newControllerUUID, newControllerName);
                // Add the new controller (map was cleared by finalize)
                controllingPlayersBySiege.computeIfAbsent(siegeId, k -> new ConcurrentHashMap<>())
                        .put(newControllerUUID, eventTime);
                plugin.getLogger().info("[DEBUG] Added enemy controller " + newControllerName);
            }
            // If sides are the same, just add the new controller without clearing.
            else if (newControllerSide == previousControllerSide && newControllerSide != SiegeSide.NOBODY) {
                plugin.getLogger().info("[DEBUG] Ally capture/reinforcement detected. Adding new controller without clearing previous.");
                internalControllers.put(newControllerUUID, eventTime); // Add to existing map for this siege
                plugin.getLogger().info("[DEBUG] Added ally controller " + newControllerName);
            } else {
                plugin.getLogger().warning("[DEBUG] Side comparison inconclusive (One side might be NOBODY or offline). Treating as enemy capture.");
                // Fallback: Finalize previous and add new if sides are uncertain
                finalizeAndClearSiegeControllers(siegeId, eventTime, newControllerUUID, newControllerName);
                controllingPlayersBySiege.computeIfAbsent(siegeId, k -> new ConcurrentHashMap<>())
                        .put(newControllerUUID, eventTime);
                plugin.getLogger().info("[DEBUG] Added controller " + newControllerName + " after inconclusive side check.");
            }
        }
    }

    // Renamed for clarity - this finalizes and clears for ONE siege
    private void finalizeAndClearSiegeControllers(String siegeId, long endTime, UUID newControllerUUID, String newControllerName) {
        Map<UUID, Long> previousControllers = controllingPlayersBySiege.remove(siegeId); // Remove the entry for this specific siege

        if (previousControllers != null && !previousControllers.isEmpty()) {
            String capturerName = (newControllerName != null) ? newControllerName : "NOBODY (Siege/Battle End or Lost)";
            plugin.getLogger().info("[DEBUG] Finalizing time for " + previousControllers.size() + " controller(s) in siege " + siegeId + ". Ended by: " + capturerName);

            for (Map.Entry<UUID, Long> entry : previousControllers.entrySet()) {
                UUID previousPlayerUUID = entry.getKey();
                long startTime = entry.getValue();
                String previousPlayerName = Bukkit.getOfflinePlayer(previousPlayerUUID).getName();

                if (newControllerUUID != null && previousPlayerUUID.equals(newControllerUUID)) {
                    continue;
                }

                double controlTimeMinutes = (endTime - startTime) / 60000.0;
                plugin.getLogger().info(String.format("[DEBUG] Attempting to record stats: SiegeID=%s, PlayerUUID=%s, PlayerName=%s, ControlTime=%.4f mins",
                        siegeId, previousPlayerUUID, previousPlayerName, controlTimeMinutes));

                if (controlTimeMinutes > 0.0001 && previousPlayerName != null) {
                    plugin.getLogger().info("[DEBUG] Logging " + String.format("%.4f", controlTimeMinutes) + " mins for controller " + previousPlayerName + " in siege " + siegeId);
                    plugin.getStatsManager().recordSiegeAction(siegeId, previousPlayerUUID, 0, 0, 0, controlTimeMinutes);
                } else {
                    plugin.getLogger().warning(String.format("[DEBUG] Skipping stat recording for %s in siege %s: ControlTime=%.4f mins (<= 0.0001) or Name=null",
                            previousPlayerName, siegeId, controlTimeMinutes));
                }
            }
            plugin.getLogger().info("[DEBUG] Cleared controller entry for siege " + siegeId + " after finalizing times.");
        }
    }


    @EventHandler
    public void onSiegeEnd(SiegeEndEvent event) {
        Siege siege = event.getSiege();
        String townName = (siege != null && siege.getTown() != null) ? siege.getTown().getName() : "Unknown Town";
        String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);
        long siegeEndTime = System.currentTimeMillis();

        if (siegeId == null && siege != null) {
            SiegeStats completedStats = plugin.getStatsManager().findCompletedSiegeStats(siege);
            if (completedStats != null) { siegeId = completedStats.getSiegeId(); }
            else { return; }
        } else if (siegeId == null) { return; }

        plugin.getLogger().info("[DEBUG] SiegeEndEvent processing cleanup for specific siege " + siegeId + " (" + townName + ")");
        finalizeAndClearSiegeControllers(siegeId, siegeEndTime, null, null);
    }


    @EventHandler
    public void onBattleSessionEnd(BattleSessionEndedEvent event) {
        long battleEndTime = System.currentTimeMillis();
        plugin.getLogger().info("[DEBUG] BattleSessionEndedEvent triggered. Finalizing capture time for ALL tracked sieges.");

        if (controllingPlayersBySiege.isEmpty()) {
            plugin.getLogger().info("[DEBUG] BattleSessionEndedEvent: No sieges currently being tracked.");
            return;
        }

        List<String> siegeIdsToProcess = new ArrayList<>(controllingPlayersBySiege.keySet());
        plugin.getLogger().info("[DEBUG] BattleSessionEndedEvent: Processing " + siegeIdsToProcess.size() + " siege(s).");

        for (String siegeId : siegeIdsToProcess) {
            plugin.getLogger().info("[DEBUG] BattleSessionEndedEvent: Finalizing controllers for siege ID: " + siegeId);
            finalizeAndClearSiegeControllers(siegeId, battleEndTime, null, null); // This now removes the entry
        }

        // The map should be empty now, but clear just in case.
        if (!controllingPlayersBySiege.isEmpty()) {
            plugin.getLogger().warning("[DEBUG] BattleSessionEndedEvent: Controller map was not empty after processing all siege IDs! Forcing clear.");
            controllingPlayersBySiege.clear();
        } else {
            plugin.getLogger().info("[DEBUG] BattleSessionEndedEvent: Controller map is now empty.");
        }
    }
}
