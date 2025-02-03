package com.mimsswstats;

import com.gmail.goosius.siegewar.events.SiegeWarStartEvent;
import com.gmail.goosius.siegewar.events.SiegeEndEvent;
import com.gmail.goosius.siegewar.objects.Siege;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Bukkit;

import java.util.concurrent.ConcurrentHashMap;

public class SiegeListener implements Listener {
    private final SiegeStatsPlugin plugin;
    private final ConcurrentHashMap<Siege, String> activeSiegeIds;

    public SiegeListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
        this.activeSiegeIds = new ConcurrentHashMap<>();
        plugin.getLogger().info("[DEBUG] SiegeListener initialized");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStartSiege(SiegeWarStartEvent event) {
        plugin.getLogger().info("[DEBUG] SiegeWarStartEvent triggered");

        if (event == null) {
            plugin.getLogger().warning("[DEBUG] Event is null!");
            return;
        }

        Siege siege = event.getSiege();
        if (siege == null) {
            plugin.getLogger().warning("[DEBUG] Siege object is null!");
            return;
        }

        String townName = siege.getTown() != null ? siege.getTown().getName() : "unknown";
        plugin.getLogger().info("[DEBUG] Siege started for town: " + townName);

        String siegeId = plugin.getStatsManager().startNewSiege(siege);

        if (siegeId == null || siegeId.isEmpty()) {
            plugin.getLogger().warning("[DEBUG] Failed to generate valid siege ID!");
            return;
        }

        activeSiegeIds.put(siege, siegeId);

        plugin.getLogger().info("[DEBUG] Active siege IDs after adding:");
        activeSiegeIds.forEach((s, id) -> {
            String sTownName = s.getTown() != null ? s.getTown().getName() : "unknown";
            plugin.getLogger().info("  - Siege ID: " + id + " for town: " + sTownName);
        });
        SiegeStats stats = plugin.getStatsManager().getSiegeStats(townName.toLowerCase(),
                plugin.getStatsManager().getTownSiegeCount(townName.toLowerCase()));

        if (stats != null) {
            plugin.getLogger().info("[DEBUG] Siege successfully stored with ID: " + siegeId);
            plugin.getLogger().info("[DEBUG] Stored siege details - Town: " + stats.getTownName() +
                    ", Number: " + stats.getSiegeNumber());
        } else {
            plugin.getLogger().warning("[DEBUG] Failed to store siege data!");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSiegeEnd(SiegeEndEvent event) {
        plugin.getLogger().info("[DEBUG] SiegeEndEvent triggered");

        if (event == null || event.getSiege() == null) {
            plugin.getLogger().warning("[DEBUG] Event or siege is null in end event!");
            return;
        }

        Siege siege = event.getSiege();
        String siegeId = activeSiegeIds.remove(siege);

        if (siegeId != null) {
            plugin.getLogger().info("[DEBUG] Ending siege with ID: " + siegeId);
            plugin.getStatsManager().endSiege(siegeId);
            Bukkit.broadcastMessage("§e[SiegeStats] Siege ended for " +
                    siege.getTown().getName() + " (ID: " + siegeId + ")");
        } else {
            plugin.getLogger().warning("[DEBUG] No siege ID found for ending siege in town: " +
                    siege.getTown().getName());
        }
    }

    public String getActiveSiegeId(Siege siege) {
        return siege.getTown().getName().toLowerCase() + "_" +
                plugin.getStatsManager().getTownSiegeCount(siege.getTown().getName().toLowerCase());
    }
    }
