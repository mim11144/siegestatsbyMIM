package com.mimsswstats;

import com.gmail.goosius.siegewar.SiegeController;
import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.objects.BattleSession;
import com.gmail.goosius.siegewar.objects.Siege;
import com.gmail.goosius.siegewar.utils.SiegeWarDistanceUtil;
import com.mimsswstats.SiegeStatsPlugin;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class PlayerDamageListener implements Listener {
    private final SiegeStatsPlugin plugin;

    public PlayerDamageListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
    }

    private static Siege findNearbyActiveSiegeWherePlayerIsParticipant(Player deadPlayer, Town deadResidentTown) {
        Siege nearestSiege = null;
        double smallestDistanceToSiege = 0;

        for (Siege candidateSiege : SiegeController.getSieges()) {

            if (!candidateSiege.getStatus().isActive())
                continue;

            if (!SiegeWarDistanceUtil.isInSiegeZone(deadPlayer, candidateSiege))
                continue;

            if (SiegeSide.getPlayerSiegeSide(candidateSiege, deadPlayer) == SiegeSide.NOBODY)
                continue;

            double candidateSiegeDistanceToPlayer = deadPlayer.getLocation().distance(candidateSiege.getFlagLocation());
            if (nearestSiege == null || candidateSiegeDistanceToPlayer < smallestDistanceToSiege) {
                nearestSiege = candidateSiege;
                smallestDistanceToSiege = candidateSiegeDistanceToPlayer;
            }
        }
        return nearestSiege;
    }
    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        try {
            if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) {
                return;
            }

            Player damager = (Player) event.getDamager();
            Player damaged = (Player) event.getEntity();

            if (!BattleSession.getBattleSession().isActive()) {
                return;
            }

            Resident damagedResident = TownyUniverse.getInstance().getResident(damaged.getName());
            Town damagedTown = damagedResident.getTown();
            Siege siege = findNearbyActiveSiegeWherePlayerIsParticipant(damaged, damagedTown);

            if (siege != null) {
                String siegeId = siege.getTown().getName().toLowerCase() + "_" +
                        plugin.getStatsManager().getTownSiegeCount(siege.getTown().getName().toLowerCase());

                plugin.getLogger().info("[DEBUG] Processing damage in siege: " + siegeId);

                SiegeSide damagerSide = SiegeSide.getPlayerSiegeSide(siege, damager);
                SiegeSide damagedSide = SiegeSide.getPlayerSiegeSide(siege, damaged);

                if (damagerSide != damagedSide && damagerSide != SiegeSide.NOBODY && damagedSide != SiegeSide.NOBODY) {
                    double finalDamage = event.getFinalDamage();

                    plugin.getLogger().info("[DEBUG] Recording damage: " + damager.getName() +
                            " dealt " + finalDamage + " damage to " + damaged.getName());

                    plugin.getStatsManager().recordSiegeAction(siegeId, damager.getName(), 0, 0, finalDamage);

                    plugin.getStatsManager().addSiegeParticipant(siege, damaged);
                    plugin.getStatsManager().addSiegeParticipant(siege, damager);
                }
            }
        } catch (NotRegisteredException e) {
            plugin.getLogger().warning("Error processing damage event: " + e.getMessage());
        }
    }}
