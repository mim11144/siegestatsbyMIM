package com.mimsswstats;

import com.gmail.goosius.siegewar.SiegeController;
import com.gmail.goosius.siegewar.enums.SiegeSide;
// Removed BannerControlSessionStartedEvent import (not used here)
import com.gmail.goosius.siegewar.objects.BattleSession;
import com.gmail.goosius.siegewar.objects.Siege;
import com.gmail.goosius.siegewar.utils.SiegeWarDistanceUtil;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile; // Keep Projectile import
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority; // Keep EventPriority import
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource; // Keep ProjectileSource import


public class PlayerDamageListener implements Listener {
    private final SiegeStatsPlugin plugin;

    public PlayerDamageListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
    }

    private static Siege findNearbyActiveSiegeWherePlayerIsParticipant(Player player) {
        // ... (existing helper method) ...
        for (Siege candidateSiege : SiegeController.getSieges()) {
            if (candidateSiege != null && candidateSiege.getStatus().isActive() &&
                    SiegeSide.getPlayerSiegeSide(candidateSiege, player) != SiegeSide.NOBODY &&
                    SiegeWarDistanceUtil.isInSiegeZone(player, candidateSiege)) {
                return candidateSiege;
            }
        }
        return null;
    }

    // Listen with MONITOR priority to get final damage value
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player damaged)) {
            return; // Victim is not a player
        }

        Player damager = null;
        // Determine damager if it's a player or a projectile shot by a player
        if (event.getDamager() instanceof Player) {
            damager = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile) {
            Projectile projectile = (Projectile) event.getDamager();
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player) {
                damager = (Player) shooter;
            }
        }

        if (damager == null || damager == damaged) {
            return; // Damager is not a player or it's self-damage
        }

        if (!BattleSession.getBattleSession().isActive()) {
            return; // Only track during active battle sessions
        }

        // Find siege based on the damaged player
        Siege siege = findNearbyActiveSiegeWherePlayerIsParticipant(damaged);

        if (siege != null) {
            String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);
            if (siegeId == null) {
                // Try fallback if ID missing (race condition)
                SiegeStats completedStats = plugin.getStatsManager().findCompletedSiegeStats(siege);
                if (completedStats != null) siegeId = completedStats.getSiegeId();
                else {
                    plugin.getLogger().warning("[PlayerDamageListener] Damage involving " + damaged.getName() + " in non-tracked siege? Town: " + siege.getTown().getName());
                    return;
                }
            }

            // Check sides
            SiegeSide damagerSide = SiegeSide.getPlayerSiegeSide(siege, damager);
            SiegeSide damagedSide = SiegeSide.getPlayerSiegeSide(siege, damaged);

            // Only record damage and track damagers if they are opponents in the siege
            if (damagerSide != SiegeSide.NOBODY && damagedSide != SiegeSide.NOBODY && damagerSide != damagedSide) {
                double finalDamage = event.getFinalDamage();
                plugin.getLogger().fine("[DEBUG] Recording damage in siege " + siegeId + ": " + damager.getName() +
                        " dealt " + finalDamage + " damage to " + damaged.getName());

                // Record the damage action using UUIDs
                plugin.getStatsManager().recordSiegeAction(siegeId, damager.getUniqueId(), 0, 0, finalDamage, 0.0, 0); // 0 assists here

                // <<< NEW: Track this damage event for potential assists >>>
                plugin.getStatsManager().addRecentDamager(damaged.getUniqueId(), damager.getUniqueId());
                plugin.getLogger().finest("[DEBUG] Added damager " + damager.getName() + " for victim " + damaged.getName() + " for assist tracking.");


                // Ensure both players are marked as participants (manager handles duplicates)
                plugin.getStatsManager().addSiegeParticipant(siege, damaged);
                plugin.getStatsManager().addSiegeParticipant(siege, damager);
            }
        }
    }
}
