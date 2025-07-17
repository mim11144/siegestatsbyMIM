package com.mimsswstats;

import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.events.SiegeEndEvent;
import com.gmail.goosius.siegewar.objects.Siege;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.ArrayList; // For potentially creating a new list from map values if needed
import java.util.stream.Collectors; // If more complex stream operations are needed

public class SiegeCompletionListener implements Listener {
    private final SiegeStatsPlugin plugin;

    public SiegeCompletionListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR) // Runs after most other listeners on Normal, before LOWEST
    public void onSiegeEnd(SiegeEndEvent event) {
        plugin.getLogger().info("[DEBUG] SiegeEndEvent triggered (SiegeCompletionListener - MONITOR priority)");
        Siege siege = event.getSiege();
        String townNameDisplay = (siege != null && siege.getTown() != null) ? siege.getTown().getName() : "Unknown Town";

        if (siege == null) {
            plugin.getLogger().warning("[SiegeCompletionListener] SiegeEndEvent with null Siege object.");
            return;
        }
        
        // Attempt to get siegeId from SiegeListener's active map first
        String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);

        if (siegeId == null) {
            plugin.getLogger().info("[SiegeCompletionListener] siegeId is null from SiegeListener for town " + townNameDisplay + ". Attempting fallback via StatsManager active sieges.");
            // Fallback: Try to find the siegeId from the manager's activeSieges map
            // This is crucial if SiegeListener hasn't processed it yet or if it was removed early.
            // We need to match based on the Siege object's properties if possible, or town name as a last resort.
            // A direct lookup via Siege object is not possible in StatsManager, so we use town name + active status.
             SiegeStats statsFromManager = plugin.getStatsManager().getActiveSiegesMap().values().stream()
                .filter(s -> s.getTownName().equalsIgnoreCase(siege.getTown().getName()) && s.isActive())
                // Potentially add a time check here if startNewSiege in StatsManager stores the SiegeWar Siege object's start time
                // .filter(s -> Math.abs(s.getStartTime() - siege.getStartTime()) < SOME_THRESHOLD_MS) // Example
                .findFirst().orElse(null);
            
            if (statsFromManager != null) {
                siegeId = statsFromManager.getSiegeId();
                plugin.getLogger().info("[SiegeCompletionListener] Obtained siegeId '" + siegeId + "' from active statsManager lookup for town " + townNameDisplay);
            } else {
                 // If not in active, try completed (though it shouldn't be if we are to complete it now)
                SiegeStats completedStats = plugin.getStatsManager().findCompletedSiegeStats(siege);
                if (completedStats != null) {
                    siegeId = completedStats.getSiegeId();
                     plugin.getLogger().info("[SiegeCompletionListener] SiegeId '" + siegeId + "' obtained from COMPLETED statsManager lookup for town " + townNameDisplay + ". Siege was already processed.");
                    // If it's already completed, no further win/loss/end processing needed.
                    return; 
                } else {
                    plugin.getLogger().warning("[SiegeCompletionListener] Critical: Could not determine siegeId for ended siege at " + townNameDisplay + " (Siege object hash: " + siege.hashCode() + "). Stats might be lost or incomplete.");
                    return; 
                }
            }
        }
        
        plugin.getLogger().info("[SiegeCompletionListener] Processing end for siegeId: " + siegeId + " in town " + townNameDisplay);

        SiegeSide winningSide = siege.getSiegeWinner(); // This can be ATTACKERS, DEFENDERS, or NOBODY/null
        plugin.getLogger().info("[SiegeCompletionListener] SiegeWar API reports winner for " + siegeId + " as: " + (winningSide != null ? winningSide.name() : "NULL"));

        if (winningSide == null || winningSide == SiegeSide.NOBODY) {
            plugin.getLogger().info("[SiegeCompletionListener] No winner declared for siege " + siegeId + " (possibly admin removed or draw). Deleting siege traces and reverting player stats.");
            plugin.getStatsManager().deleteSiegeAndRevertPlayerStats(siegeId);
            // No further processing (W/L, moving to completed) is needed as it's deleted.
        } else {
            // There is a clear winner (ATTACKERS or DEFENDERS)
            plugin.getLogger().info("[SiegeCompletionListener] Siege " + siegeId + " has a clear winner: " + winningSide.name() + ". Processing normal completion.");
            SiegeStats siegeStats = plugin.getStatsManager().getSiegeStatsById(siegeId);

            if (siegeStats != null) {
                if (!siegeStats.isActive()) {
                    plugin.getLogger().info("[SiegeCompletionListener] Siege " + siegeId + " is already marked as inactive by StatsManager. Winner was: " + winningSide.name() + ". Re-affirming W/L if necessary.");
                }

                siegeStats.setAttackersWon(winningSide == SiegeSide.ATTACKERS);
                siegeStats.setDefendersWon(winningSide == SiegeSide.DEFENDERS);
                plugin.getLogger().info("[SiegeCompletionListener] SiegeStats for " + siegeId + " updated. AttackersWon: " + siegeStats.getAttackersWon() + ", DefendersWon: " + siegeStats.getDefendersWon());

                for (Map.Entry<UUID, SiegeStats.ParticipantMetrics> entry : new ArrayList<>(siegeStats.getParticipantMetrics().entrySet())) { // Iterate a copy
                    UUID playerUUID = entry.getKey();
                    SiegeStats.ParticipantMetrics metrics = entry.getValue();
                    String playerName = Bukkit.getOfflinePlayer(playerUUID).getName();
                    if (playerName == null) playerName = "UUID_" + playerUUID.toString().substring(0,8);

                    PlayerStats globalPlayerStats = plugin.getStatsManager().getPlayerStats(playerUUID, playerName);
                    if (globalPlayerStats == null) {
                        plugin.getLogger().warning("[SiegeCompletionListener] Player UUID " + playerUUID + " ("+playerName+") in siege metrics but no global stats for siege " + siegeId);
                        continue;
                    }
                    globalPlayerStats.updateName(playerName); 

                    SiegeSide storedPlayerSide = metrics.getSide();
                    boolean meetsParticipationCriteria = metrics.getControlTime() >= 1.0 || metrics.getKills() >= 1 || metrics.getDeaths() >= 1 || metrics.getAssists() >= 1;

                    if (meetsParticipationCriteria && storedPlayerSide != SiegeSide.NOBODY && storedPlayerSide != null) {
                        plugin.getLogger().info("[DEBUG] Player " + playerName + " (Side: " + storedPlayerSide + ") met participation for siege " + siegeId + ". Siege winner: " + winningSide.name());
                        if (storedPlayerSide == winningSide) {
                            globalPlayerStats.addWin();
                            plugin.getLogger().info("[DEBUG] Awarded WIN to " + playerName + " for siege " + siegeId);
                        } else {
                            globalPlayerStats.addLoss();
                            plugin.getLogger().info("[DEBUG] Awarded LOSS to " + playerName + " for siege " + siegeId);
                        }
                    } else {
                        String reason = (storedPlayerSide == SiegeSide.NOBODY || storedPlayerSide == null) ? "Side was NOBODY/null" : "Did not meet participation criteria";
                        plugin.getLogger().info("[DEBUG] No Win/Loss for " + playerName + " (UUID: " + playerUUID + ") in siege " + siegeId + " ("+reason+")."
                                + String.format(" K:%d D:%d A:%d CapT:%.2fm", metrics.getKills(), metrics.getDeaths(), metrics.getAssists(), metrics.getControlTime()) );
                    }
                }
                plugin.getStatsManager().endSiege(siegeId); // Finalize normally: move to completed, save.
                plugin.getLogger().info("[SiegeStats] Siege " + siegeId + " completed normally by SiegeCompletionListener. Stats processed and saved.");

            } else {
                plugin.getLogger().warning("[SiegeCompletionListener] Could not find SiegeStats for normally ended siege ID: " + siegeId + ". Town: " + townNameDisplay + ". Cannot update W/L or finalize normally through this path.");
                // If siegeStats is null, but there was a winner, this is an anomaly.
                // We might still call endSiege if the ID is confirmed valid to ensure it's at least moved from active.
                // However, the logic above tries to ensure siegeId is valid and linked to an actual siege.
            }
        }
    }
}