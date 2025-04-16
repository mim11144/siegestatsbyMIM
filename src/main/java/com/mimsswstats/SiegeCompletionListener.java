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

public class SiegeCompletionListener implements Listener {
    private final SiegeStatsPlugin plugin;

    public SiegeCompletionListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSiegeEnd(SiegeEndEvent event) {
        Siege siege = event.getSiege();
        String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);
        if (siegeId == null) {
            SiegeStats completedStats = plugin.getStatsManager().findCompletedSiegeStats(siege);
            if (completedStats != null) { siegeId = completedStats.getSiegeId(); }
            else {
                plugin.getLogger().warning("[SiegeCompletionListener] End event for non-tracked siege? Town: " + (siege != null && siege.getTown() != null ? siege.getTown().getName() : "Unknown"));
                return;
            }
        }

        SiegeSide winningSide = siege.getSiegeWinner();
        SiegeStats siegeStats = plugin.getStatsManager().getSiegeStatsById(siegeId);

        if (siegeStats != null) {
            siegeStats.setAttackersWon(winningSide == SiegeSide.ATTACKERS);
            siegeStats.setDefendersWon(winningSide == SiegeSide.DEFENDERS);

            for (Map.Entry<UUID, SiegeStats.ParticipantMetrics> entry : siegeStats.getParticipantMetrics().entrySet()) {
                UUID playerUUID = entry.getKey();
                SiegeStats.ParticipantMetrics metrics = entry.getValue();
                String playerName = Bukkit.getOfflinePlayer(playerUUID).getName(); // Get name for logging

                PlayerStats globalPlayerStats = plugin.getStatsManager().getPlayerStats(playerUUID, playerName);
                if (globalPlayerStats == null) {
                    plugin.getLogger().warning("[SiegeCompletionListener] Player UUID " + playerUUID + " in siege metrics but has no global stats object?");
                    continue;
                }
                globalPlayerStats.updateName(playerName); // Ensure name is up-to-date

                SiegeSide storedPlayerSide = metrics.getSide();

                // <<< NEW: Check Participation Criteria >>>
                boolean meetsParticipationCriteria =
                        metrics.getControlTime() >= 1.0 ||  // 1.0 minute or more capture time
                                metrics.getKills() >= 1 ||          // 1 or more kills
                                metrics.getDeaths() >= 1 ||         // 1 or more deaths
                                metrics.getAssists() >= 1;        // 1 or more assists

                if (meetsParticipationCriteria && storedPlayerSide != SiegeSide.NOBODY) {
                    plugin.getLogger().info("[DEBUG] Player " + playerName + " met participation criteria (Side: " + storedPlayerSide + "). Checking W/L vs Winner: " + winningSide);
                    if (storedPlayerSide == winningSide) {
                        globalPlayerStats.addWin();
                        plugin.getLogger().info("[DEBUG] Awarded WIN to " + playerName + " for siege " + siegeId);
                    } else {
                        globalPlayerStats.addLoss();
                        plugin.getLogger().info("[DEBUG] Awarded LOSS to " + playerName + " for siege " + siegeId);
                    }
                } else {
                    // Log why they didn't get W/L
                    String reason = (storedPlayerSide == SiegeSide.NOBODY) ? "Side was NOBODY" : "Did not meet participation criteria";
                    plugin.getLogger().info("[DEBUG] No Win/Loss for " + playerName + " (UUID: " + playerUUID + ") in siege " + siegeId + " ("+reason+")"
                            + String.format(" K:%d D:%d A:%d CapT:%.2fm", metrics.getKills(), metrics.getDeaths(), metrics.getAssists(), metrics.getControlTime()) );
                }
                // <<< End Criteria Check >>>
            }
        } else {
            plugin.getLogger().warning("[SiegeCompletionListener] Could not find SiegeStats for ended siege ID: " + siegeId + " when trying to award W/L.");
        }

        plugin.getStatsManager().endSiege(siegeId); // This should save the updated global stats
        plugin.getLogger().info("[SiegeStats] Siege completed and W/L processed (with criteria) for ID: " + siegeId);
    }
}
