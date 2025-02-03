package com.mimsswstats;

import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.events.SiegeEndEvent;
import com.gmail.goosius.siegewar.objects.Siege;
import com.mimsswstats.SiegePerformance;
import com.mimsswstats.SiegeStatsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class SiegeCompletionListener implements Listener {
    private final SiegeStatsPlugin plugin;

    public SiegeCompletionListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSiegeEnd(SiegeEndEvent event) {
        if (event == null || event.getSiege() == null) {
            return;
        }

        Siege siege = event.getSiege();
        String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);

        if (siegeId == null) {
            return;
        }

  
        SiegeSide winningSide = siege.getSiegeWinner();


        for (Player player : Bukkit.getOnlinePlayers()) {

            SiegeSide playerSide = SiegeSide.getPlayerSiegeSide(siege, player);

            if (playerSide != SiegeSide.NOBODY) {
 
                plugin.getStatsManager().addSiegeParticipant(siege, player);

         
                boolean won = (playerSide == winningSide);

                SiegePerformance performance = new SiegePerformance(
                        won,
                        0, 
                        0,
                        0,
                        siege
                );

                plugin.getStatsManager().updatePlayerStats(player.getName(), performance);

                PlayerStats stats = plugin.getStatsManager().getPlayerStats(player.getName());
                if (won) {
                    stats.addWin();
                } else {
                    stats.addLoss();
                }
            }
        }

        plugin.getStatsManager().endSiege(siegeId);
    }}
