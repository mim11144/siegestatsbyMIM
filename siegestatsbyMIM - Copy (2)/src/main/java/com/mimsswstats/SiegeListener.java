package com.mimsswstats;

// SiegeWar Imports
// import com.gmail.goosius.siegewar.enums.SiegeSide; // No longer directly used here
import com.gmail.goosius.siegewar.events.SiegeEndEvent;
import com.gmail.goosius.siegewar.events.SiegeWarStartEvent;
import com.gmail.goosius.siegewar.objects.Siege;

// Bukkit Imports
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

// Java Util Imports
import java.util.concurrent.ConcurrentHashMap;

public class SiegeListener implements Listener {
    private final SiegeStatsPlugin plugin;
    // Map: Siege Object -> Unique Siege ID (e.g., "townname_1")
    private final ConcurrentHashMap<Siege, String> activeSiegeIds;
    // private SiegeStats siegeStats; // REMOVED - This field was uninitialized and would cause NPEs.

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

        // Check if this siege object is already being tracked (shouldn't happen ideally)
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

    @EventHandler(priority = EventPriority.LOWEST) // PRIORITY CHANGED TO LOWEST
    public void onSiegeEnd(SiegeEndEvent event) {
        plugin.getLogger().info("[DEBUG] SiegeEndEvent triggered (SiegeListener - LOWEST priority)");
        
        if (event == null || event.getSiege() == null) {
            plugin.getLogger().warning("[DEBUG] SiegeEndEvent (SiegeListener) or its Siege object is null!");
            return;
        }

        Siege siege = event.getSiege();
        String townName = (siege.getTown() != null) ? siege.getTown().getName() : "Unknown Town";

        // Attempt to get the siegeId using the Siege object
        String siegeIdFromMap = activeSiegeIds.get(siege); 

        if (activeSiegeIds.containsKey(siege)) {
            // Remove the Siege object from the map, effectively stop tracking it by its object reference.
            String removedId = activeSiegeIds.remove(siege); // removedId should be same as siegeIdFromMap
            
            if (removedId != null) {
                plugin.getLogger().info("[DEBUG] Siege object for town " + townName + " (ID: " + removedId + ") removed from activeSiegeIds map by SiegeListener.");
                // The broadcast here is optional, as SiegeCompletionListener or other parts might handle user notifications.
                // Bukkit.broadcastMessage("§e[SiegeStats] Tracking via Siege object concluded for siege in " + townName + " (ID: " + removedId + ")");
            } else {
                 // This would be unusual if containsKey was true.
                plugin.getLogger().warning("[DEBUG] Siege object for " + townName + " was in activeSiegeIds map but remove() returned null ID.");
            }
        } else {
            plugin.getLogger().info("[DEBUG] SiegeEndEvent (SiegeListener - LOWEST): Siege for town " + townName + 
                                       " (Object Hash: " + siege.hashCode() + ") was not found in (or already removed from) activeSiegeIds map. " +
                                       "This is expected if it was processed and removed by higher priority listeners (e.g., via deletion logic).");
        }
        logActiveSiegesState(); // Log current state of the map
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