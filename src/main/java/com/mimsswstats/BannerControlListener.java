package com.mimsswstats;

import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.events.BannerControlSessionStartedEvent;
import com.gmail.goosius.siegewar.objects.Siege;
import com.mimsswstats.SiegeStatsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class BannerControlListener implements Listener {
    private final SiegeStatsPlugin plugin;

    public BannerControlListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBannerControlSessionStarted(BannerControlSessionStartedEvent event) {
        Siege siege = event.getSiege();
        String siegeId = plugin.getSiegeListener().getActiveSiegeId(siege);

        if (siegeId == null) return;

        // Get all online players and check if they're participating
        for (Player player : Bukkit.getOnlinePlayers()) {
            SiegeSide playerSide = SiegeSide.getPlayerSiegeSide(siege, player);

            // If player is on either side, record participation
            if (playerSide != SiegeSide.NOBODY) {
                plugin.getStatsManager().addSiegeParticipant(siege, player);
            }
        }
    }
}