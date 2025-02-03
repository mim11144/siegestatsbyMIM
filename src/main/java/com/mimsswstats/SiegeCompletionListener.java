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

        // Get the winning side
        SiegeSide winningSide = siege.getSiegeWinner();

        // Get all online players and check their participation
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Get player's side in the siege
            SiegeSide playerSide = SiegeSide.getPlayerSiegeSide(siege, player);

            if (playerSide != SiegeSide.NOBODY) {
                // Record participation first
                plugin.getStatsManager().addSiegeParticipant(siege, player);

                // Determine if player won
                boolean won = (playerSide == winningSide);

                // Create performance record
                SiegePerformance performance = new SiegePerformance(
                        won,
                        0, // These will be added from existing stats
                        0,
                        0,
                        siege
                );

                // Update player stats with the win/loss
                plugin.getStatsManager().updatePlayerStats(player.getName(), performance);

                // Explicitly update wins/losses
                PlayerStats stats = plugin.getStatsManager().getPlayerStats(player.getName());
                if (won) {
                    stats.addWin();
                } else {
                    stats.addLoss();
                }
            }
        }

        // End siege tracking
        plugin.getStatsManager().endSiege(siegeId);
    }}