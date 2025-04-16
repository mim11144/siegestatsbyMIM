package com.mimsswstats;

import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.events.SiegeEndEvent;
import com.gmail.goosius.siegewar.events.SiegeWarStartEvent;
import com.gmail.goosius.siegewar.objects.Siege;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.concurrent.ConcurrentHashMap;

public class SiegeListener implements Listener {
    private final SiegeStatsPlugin plugin;
    // Map: Siege Object -> Unique Siege ID (e.g., "townname_1")
    private final ConcurrentHashMap<Siege, String> activeSiegeIds;
private SiegeStats siegeStats;
    public SiegeListener(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
        this.activeSiegeIds = new ConcurrentHashMap<>();
        plugin.getLogger().info("[DEBUG] SiegeListener initialized");
    }

    @EventHandler(priority = EventPriority.MONITOR) // Monitor ensures SiegeWar has set up the siege first
    public void onStartSiege(SiegeWarStartEvent event) {
        plugin.getLogger().info("[DEBUG] SiegeWarStartEvent triggered");
        if (event == null) {
            plugin.getLogger().warning("[DEBUG] Event is null!");
            return;
        }

        Siege siege = event.getSiege();
        if (siege == null || siege.getTown() == null) {
            plugin.getLogger().warning("[DEBUG] Siege object or Town is null in start event!");
            return;
        }

        String townName = siege.getTown().getName();
        plugin.getLogger().info("[DEBUG] Siege starting for town: " + townName);

        if (activeSiegeIds.containsKey(siege)) {
            plugin.getLogger().warning("[DEBUG] Siege object for town " + townName + " is already being tracked with ID: " + activeSiegeIds.get(siege) + ". Ignoring duplicate start event.");
            return;
        }

        // Start tracking the siege in the manager, which returns the generated unique ID
        String siegeId = plugin.getStatsManager().startNewSiege(siege);

        if (siegeId == null || siegeId.isEmpty()) {
            plugin.getLogger().severe("[DEBUG] Failed to generate or store valid siege ID for town: " + townName + "! Stats may not be recorded.");
            return;
        }

        // Store the mapping from the Siege object to its unique ID
        activeSiegeIds.put(siege, siegeId);

        plugin.getLogger().info("[DEBUG] Siege started and now tracking. Town: " + townName + ", Assigned ID: " + siegeId);
        logActiveSiegesState(); // Log current state
    }

    @EventHandler(priority = EventPriority.MONITOR) // Monitor ensures SiegeWar's end logic might have run
    public void onSiegeEnd(SiegeEndEvent event) {
        plugin.getLogger().info("[DEBUG] SiegeEndEvent triggered");
        siegeStats.setActive(false) ;

        if (event == null || event.getSiege() == null) {
            plugin.getLogger().warning("[DEBUG] Event or siege is null in end event!");
            return;
        }

        Siege siege = event.getSiege();
        // Remove the siege object from tracking and get its ID
        String siegeId = activeSiegeIds.remove(siege);
if(siege.getSiegeWinner() == SiegeSide.ATTACKERS){
    siegeStats.setAttackersWon(true);
    siegeStats.setDefendersWon(false);
}
if(siege.getSiegeWinner() == SiegeSide.DEFENDERS){
    siegeStats.setDefendersWon(true);
    siegeStats.setAttackersWon(false);
}
        if (siegeId != null) {
            plugin.getLogger().info("[DEBUG] Siege object removed from active tracking. Town: " + siege.getTown().getName() + ", ID: " + siegeId);
            // The actual ending logic (moving stats, calculating duration etc.) is handled
            // by SiegeCompletionListener which calls statsManager.endSiege(siegeId)
            // We don't call endSiege here to avoid potential race conditions if completion listener runs later.
            Bukkit.broadcastMessage("§e[SiegeStats] Tracking ended for siege in " +
                    (siege.getTown() != null ? siege.getTown().getName() : "Unknown Town") + " (ID: " + siegeId + ")");
        } else {
            // This might happen if the plugin was reloaded mid-siege or if start event failed
            plugin.getLogger().warning("[DEBUG] SiegeEndEvent for a siege object that was not being tracked. Town: " +
                    (siege.getTown() != null ? siege.getTown().getName() : "Unknown Town") + ". Stats might be incomplete if it was active before.");
        }
        logActiveSiegesState(); // Log current state
    }

    /**
     * Reliably gets the unique ID for an actively tracked siege.
     * Returns null if the siege is not currently tracked by this listener.
     * @param siege The Siege object.
     * @return The unique siege ID (e.g., "townname_1") or null.
     */
    public String getActiveSiegeId(Siege siege) {
        if (siege == null) return null;
        return activeSiegeIds.get(siege); // Direct lookup using the siege object
    }

    // Helper to log the current state of tracked sieges
    private void logActiveSiegesState() {
        plugin.getLogger().info("[DEBUG] Current activeSiegeIds map state ("+ activeSiegeIds.size() +" entries):");
        activeSiegeIds.forEach((s, id) -> {
            String sTownName = s != null && s.getTown() != null ? s.getTown().getName() : "Unknown/Null Siege";
            plugin.getLogger().info("  - Siege Town: " + sTownName + " -> ID: " + id);
        });
    }
}
