package com.mimsswstats;

import com.gmail.goosius.siegewar.SiegeController;
import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.objects.BattleSession;
import com.gmail.goosius.siegewar.objects.Siege;
import com.gmail.goosius.siegewar.utils.SiegeWarDistanceUtil;
import com.palmergames.bukkit.towny.TownyUniverse;
import org.bukkit.Bukkit; // Import Bukkit
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Map; // Import Map
import java.util.UUID; // Import UUID


public class PlayerDeathListener implements Listener {
    private final SiegeStatsPlugin plugin;

    public PlayerDeathListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
    }

    private static Siege findNearbyActiveSiegeWherePlayerIsParticipant(Player player) {
        // ... (existing helper method - ensure null safe) ...
        for (Siege candidateSiege : SiegeController.getSieges()) {
            if (candidateSiege != null && candidateSiege.getStatus().isActive() &&
                    player != null && // Add null check for player
                    SiegeSide.getPlayerSiegeSide(candidateSiege, player) != SiegeSide.NOBODY &&
                    SiegeWarDistanceUtil.isInSiegeZone(player, candidateSiege)) {
                return candidateSiege;
            }
        }
        return null;
    }


    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (!BattleSession.getBattleSession().isActive()) {
            return;
        }

        Siege siege = findNearbyActiveSiegeWherePlayerIsParticipant(victim);

        if (siege != null) {
            String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);
            if (siegeId == null) {
                SiegeStats completedStats = plugin.getStatsManager().findCompletedSiegeStats(siege);
                if (completedStats != null) siegeId = completedStats.getSiegeId();
                else {
                    plugin.getLogger().warning("[PlayerDeathListener] Death for " + victim.getName() + " in non-tracked siege? Town: " + siege.getTown().getName());
                    return;
                }
            }

            // --- Record Victim's Death (Always if participating) ---
            plugin.getStatsManager().addSiegeParticipant(siege, victim); // Ensure participant
            SiegeSide victimSide = SiegeSide.getPlayerSiegeSide(siege, victim);
            if (victimSide != SiegeSide.NOBODY) {
                String cause = event.getDeathMessage() != null ? event.getDeathMessage() : "Unknown cause";
                plugin.getLogger().info("[DEBUG] Recording death in siege " + siegeId + ": Victim=" + victim.getName() + ", Side=" + victimSide + ", Cause: " + cause);
                plugin.getStatsManager().recordSiegeAction(siegeId, victim.getUniqueId(), 0, 1, 0, 0.0, 0); // Record death
            } else {
                plugin.getLogger().fine("[DEBUG] Player " + victim.getName() + " died near siege " + siegeId + " but was Side.NOBODY. No death recorded.");
            }

            // --- Record Killer's Kill & Potential Assists (Only if valid PvP) ---
            if (killer != null && killer != victim) {
                plugin.getStatsManager().addSiegeParticipant(siege, killer); // Ensure participant
                SiegeSide killerSide = SiegeSide.getPlayerSiegeSide(siege, killer);

                // Check if valid kill between opponents
                if (killerSide != SiegeSide.NOBODY && victimSide != SiegeSide.NOBODY && killerSide != victimSide) {
                    plugin.getLogger().info("[DEBUG] Recording PvP kill in siege " + siegeId + ": Killer=" + killer.getName() + ", Victim=" + victim.getName());
                    // Record Kill
                    plugin.getStatsManager().recordSiegeAction(siegeId, killer.getUniqueId(), 1, 0, 0, 0.0, 0);

                    // <<< NEW: Process Assists >>>
                    long assistTimeThreshold = System.currentTimeMillis() - SiegeStatsManager.ASSIST_WINDOW_MS;
                    Map<UUID, Long> potentialAssisters = plugin.getStatsManager().getRecentDamagersForAssist(victim.getUniqueId());

                    plugin.getLogger().info("[DEBUG] Checking " + potentialAssisters.size() + " potential assisters for kill on " + victim.getName());

                    int assistsAwarded = 0;
                    for (Map.Entry<UUID, Long> entry : potentialAssisters.entrySet()) {
                        UUID assisterUUID = entry.getKey();
                        long damageTimestamp = entry.getValue();

                        // Check timestamp and ensure assister is not the killer
                        if (damageTimestamp >= assistTimeThreshold && !assisterUUID.equals(killer.getUniqueId())) {
                            // Verify assister is still part of the siege and on the killer's side (or not victim's side)
                            OfflinePlayer assisterOffline = Bukkit.getOfflinePlayer(assisterUUID);
                            Player assisterOnline = assisterOffline.getPlayer(); // Check if online
                            if(assisterOnline != null){
                                SiegeSide assisterSide = SiegeSide.getPlayerSiegeSide(siege, assisterOnline);
                                if (assisterSide == killerSide) { // Must be on the same side as the killer
                                    plugin.getLogger().info("[DEBUG] Awarding assist to " + assisterOffline.getName() + " for kill on " + victim.getName());
                                    // Record Assist
                                    plugin.getStatsManager().recordSiegeAction(siegeId, assisterUUID, 0, 0, 0, 0.0, 1);
                                    assistsAwarded++;
                                } else {
                                    plugin.getLogger().fine("[DEBUG] Potential assister " + assisterOffline.getName() + " side ("+assisterSide+") != killer side ("+killerSide+"). No assist.");
                                }
                            } else {
                                plugin.getLogger().fine("[DEBUG] Potential assister " + assisterOffline.getName() + " is offline. Cannot verify side. No assist.");
                                // Optionally, you could award assist even if offline, but side check is safer.
                            }
                        }
                    }
                    plugin.getLogger().info("[DEBUG] Awarded " + assistsAwarded + " assists for kill on " + victim.getName());
                    // Optional: Clear recent damagers for the victim now that they're dead and assists processed
                    // plugin.getStatsManager().clearRecentDamagers(victim.getUniqueId()); // Need to add this method to manager if desired

                } else {
                    plugin.getLogger().fine("[DEBUG] Kill not recorded for assist check: Killer=" + killer.getName() + "(" + killerSide + "), Victim=" + victim.getName() + "(" + victimSide + ")");
                }
            }
        }
    }
}