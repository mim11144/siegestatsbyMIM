package com.mimsswstats;

import com.gmail.goosius.siegewar.SiegeController;
import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.objects.BattleSession;
import com.gmail.goosius.siegewar.objects.Siege;
import com.gmail.goosius.siegewar.utils.SiegeWarDistanceUtil;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class PlayerDeathListener implements Listener {
    private final SiegeStatsPlugin plugin;

    public PlayerDeathListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) throws NotRegisteredException {
        Player victim = event.getEntity();

        Entity killerentity = event.getEntity().getKiller();
        Player killer = event.getEntity().getKiller();

        if (!BattleSession.getBattleSession().isActive()) {

            return;
        }
        if (killer == null) {
            return;
        }
        if (killer instanceof Player) {
            Resident victimResident = TownyUniverse.getInstance().getResident(victim.getName());
            Town victimTown = victimResident.getTown();
            Siege siege = findNearbyActiveSiegeWherePlayerIsParticipant(victim, victimTown);
            SiegeSide damagerSide = SiegeSide.getPlayerSiegeSide(siege, killer);
            SiegeSide damagedSide = SiegeSide.getPlayerSiegeSide(siege, victim);
            if (siege != null) {
                String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);

                if (siegeId != null) {
                    if (damagerSide != damagedSide && damagerSide != SiegeSide.NOBODY && damagedSide != SiegeSide.NOBODY) {
                        // Record kill/death stats with siege ID
                        plugin.getStatsManager().recordSiegeAction(siegeId, killer.getName(), 1, 0, 0);
                        plugin.getStatsManager().recordSiegeAction(siegeId, victim.getName(), 0, 1, 0);

                        System.out.println("[DEBUG] Recorded kill in siege " + siegeId +
                                ": " + killer.getName() + " killed " + victim.getName());
                    }
                }
            }
            if (!(killerentity instanceof Player)){
            if (siege != null) {
                String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);

                if (siegeId != null) {
                        // Record kill/death stats with siege ID
                        plugin.getStatsManager().recordSiegeAction(siegeId, victim.getName(), 0, 1, 0);

                        System.out.println("[DEBUG] Recorded kill in siege " + siegeId +
                                ": " + killerentity.getUniqueId() + " killed " + victim.getName());
                    }
                }
            }
        }
    }

    private static Siege findNearbyActiveSiegeWherePlayerIsParticipant(Player deadPlayer, Town deadResidentTown) {
            Siege nearestSiege = null;
            double smallestDistanceToSiege = 0;

            //Find nearest eligible siege
            for (Siege candidateSiege : SiegeController.getSieges()) {

                //Skip if siege is not active
                if (!candidateSiege.getStatus().isActive())
                    continue;

                //Skip if player is not is siege-zone
                if (!SiegeWarDistanceUtil.isInSiegeZone(deadPlayer, candidateSiege))
                    continue;

                //Skip if player is not an official attacker or defender in siege
                if (SiegeSide.getPlayerSiegeSide(candidateSiege, deadPlayer) == SiegeSide.NOBODY)
                    continue;

                //Set nearestSiege if it is 1st viable one OR closer than smallestDistanceToSiege.
                double candidateSiegeDistanceToPlayer = deadPlayer.getLocation().distance(candidateSiege.getFlagLocation());
                if (nearestSiege == null || candidateSiegeDistanceToPlayer < smallestDistanceToSiege) {
                    nearestSiege = candidateSiege;
                    smallestDistanceToSiege = candidateSiegeDistanceToPlayer;
                }
            }
            return nearestSiege;
        }
        }