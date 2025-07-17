package com.mimsswstats;

// Adventure API Imports (Chat Components)
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences; // Not strictly needed here if fetched from plugin, but good for context
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

// Bukkit API Imports
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

// Java Util Imports
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// Towny & SiegeWar Imports
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import com.gmail.goosius.siegewar.SiegeWarAPI;
// Explicit import for com.gmail.goosius.siegewar.objects.Siege needed for type casting and method calls
import com.gmail.goosius.siegewar.objects.Siege;


public class SiegeStatsCommand implements CommandExecutor {
    private final SiegeStatsPlugin plugin;
    private final SiegeStatsManager statsManager;
    private final BukkitAudiences adventure; // Adventure provider

    // --- State Management for Siege Pagination ---
    private static class SiegePageView {
        final String siegeId;
        // Store the sorted list directly (Map Entry Key is UUID)
        final List<Map.Entry<UUID, SiegeStats.ParticipantMetrics>> sortedParticipants;
        int currentPage;
        final int totalPages;
        final int playersPerPage;

        // Constructor takes the already sorted list
        SiegePageView(String siegeId, List<Map.Entry<UUID, SiegeStats.ParticipantMetrics>> sortedParticipants, int playersPerPage) {
            this.siegeId = siegeId;
            // Ensure the passed list is not null, even if empty
            this.sortedParticipants = (sortedParticipants != null) ? sortedParticipants : Collections.emptyList();
            this.currentPage = 1;
            this.playersPerPage = playersPerPage;
            // Calculate total pages based on the size of the sorted list
            this.totalPages = Math.max(1, (int) Math.ceil((double) this.sortedParticipants.size() / playersPerPage));
        }
    }
    // Player UUID -> Current Siege Page View State
    private final Map<UUID, SiegePageView> playerSiegeViews = new ConcurrentHashMap<>();
    private final int PLAYERS_PER_PAGE = 7; // Players displayed per page
    // --- End State Management ---

    // Constructor
    public SiegeStatsCommand(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
        this.statsManager = plugin.getStatsManager();
        this.adventure = plugin.adventure(); // Get Adventure provider from main plugin class
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("siegestats")) {
            return false;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        // Route subcommands
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "player":
            case "p":
                handlePlayerStats(sender, args);
                break;
            case "top":
                handleTopPlayers(sender, args);
                break;
            case "siege":
            case "s":
                handleSiegeStats(sender, args); // Initial call for page 1
                break;
            case "move":
                handleSiegeMove(sender, args); // Handles pagination clicks
                break;
            case "reset":
                handleReset(sender, args);
                break;
            case "debug":
                handleDebug(sender, args);
                break;
            default:
                sender.sendMessage("§cUnknown subcommand '" + args[0] + "'. Use /ss for help.");
        }
        return true;
    }

    // --- Command Handlers ---

    private void sendHelpMessage(CommandSender sender){
        // Using legacy codes here is acceptable for simple help text
        sender.sendMessage("§6§l══════ SiegeStats Commands (/ss) ══════");
        sender.sendMessage("§e• §f/ss player <name> §7- View player's global stats");
        sender.sendMessage("§e• §f/ss top <kills|dmg|deaths|assists|kda|captime> [count] §7- Top players");
        sender.sendMessage("§e• §f/ss siege <town> [number] §7- View specific siege stats (Paginated)");
        if (sender.hasPermission("siegestats.admin")) {
            sender.sendMessage("§cAdmin Commands:");
            sender.sendMessage("§e• §f/ss reset §7- Reset ALL stats");
            sender.sendMessage("§e• §f/ss debug <check|load|save> §7- Debug commands");
        }
        sender.sendMessage("§6§l═══════════════════════════════════");
    }

    private void handlePlayerStats(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /ss player <name>"); return; }
        String playerName = args[1];
        plugin.getLogger().info("[DEBUG] handlePlayerStats: Looking up stats for '" + playerName + "'");

        PlayerStats playerStats = statsManager.getPlayerStatsByName(playerName);
        String displayName = (playerStats != null) ? playerStats.getLastKnownName() : playerName;

        if (playerStats == null || playerStats.getTotalSiegesParticipated() == 0) {
            sender.sendMessage("§c✖ Player " + displayName + " not found or has no recorded siege participation.");
            plugin.getLogger().info("[DEBUG] handlePlayerStats: Player '" + displayName + "' not found or no participation.");
            return;
        }
        plugin.getLogger().info("[DEBUG] handlePlayerStats: Found stats for '" + displayName + "'");

        // Calculate stats safely
        double kdRatio = 0, kdaRatio = 0, winRatePercent = 0;
        try {
            kdRatio = playerStats.getKillDeathRatio();
            kdaRatio = playerStats.getKdaRatio();
            winRatePercent = playerStats.getWinLossRatio() * 100.0;
            plugin.getLogger().info("[DEBUG] handlePlayerStats: Calculated ratios.");
        } catch (Exception e) {
            plugin.getLogger().severe("[DEBUG] handlePlayerStats: Error calculating ratios for " + displayName);
            sender.sendMessage("§cError calculating stats for this player.");
            return;
        }

        // Get Audience and send messages
        Audience audience = adventure.sender(sender);
        try {
            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending Header...");
            audience.sendMessage(Component.text("═ " + displayName + "'s Global Siege Stats ═", NamedTextColor.GOLD, TextDecoration.BOLD));

            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending Combat Header...");
            audience.sendMessage(Component.text(" Combat:", NamedTextColor.YELLOW));

            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending KDA Line...");
            audience.sendMessage(Component.text()
                    .append(Component.text("  • ", NamedTextColor.GRAY))
                    .append(Component.text("K/D/A: ", NamedTextColor.WHITE))
                    .append(Component.text(playerStats.getTotalKills(), NamedTextColor.GREEN))
                    .append(Component.text("/", NamedTextColor.GRAY))
                    .append(Component.text(playerStats.getTotalDeaths(), NamedTextColor.RED))
                    .append(Component.text("/", NamedTextColor.GRAY))
                    .append(Component.text(playerStats.getTotalAssists(), NamedTextColor.AQUA))
            );

            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending Ratio Line...");
            audience.sendMessage(Component.text()
                    .append(Component.text("  • ", NamedTextColor.GRAY))
                    .append(Component.text("K/D Ratio: ", NamedTextColor.WHITE))
                    .append(Component.text(String.format("%.2f", kdRatio), NamedTextColor.YELLOW))
                    .append(Component.text(" | ", NamedTextColor.GRAY))
                    .append(Component.text("KDA Ratio: ", NamedTextColor.WHITE))
                    .append(Component.text(String.format("%.2f", kdaRatio), NamedTextColor.YELLOW))
            );

            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending Damage Line...");
            audience.sendMessage(Component.text()
                    .append(Component.text("  • ", NamedTextColor.GRAY))
                    .append(Component.text("Damage Dealt: ", NamedTextColor.WHITE))
                    .append(Component.text(String.format("%.1f", playerStats.getTotalDamage()), NamedTextColor.GOLD))
            );

            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending Spacer...");
            audience.sendMessage(Component.text(" ")); // Spacer

            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending Sieges Header...");
            audience.sendMessage(Component.text(" Sieges:", NamedTextColor.YELLOW));

            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending Participation Line...");
            audience.sendMessage(Component.text()
                    .append(Component.text("  • ", NamedTextColor.GRAY))
                    .append(Component.text("Participated: ", NamedTextColor.WHITE))
                    .append(Component.text(playerStats.getTotalSiegesParticipated(), NamedTextColor.AQUA))
                    .append(Component.text(" | ", NamedTextColor.GRAY))
                    .append(Component.text("Wins: ", NamedTextColor.WHITE))
                    .append(Component.text(playerStats.getTotalWins(), NamedTextColor.GREEN))
                    .append(Component.text(" | ", NamedTextColor.GRAY))
                    .append(Component.text("Losses: ", NamedTextColor.WHITE))
                    .append(Component.text(playerStats.getTotalLosses(), NamedTextColor.RED))
                    .append(Component.text(String.format(" (Rate: %.1f%%)", winRatePercent), NamedTextColor.YELLOW))
            );

            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending Capture Time Line...");
            audience.sendMessage(Component.text()
                    .append(Component.text("  • ", NamedTextColor.GRAY))
                    .append(Component.text("Banner Control Time: ", NamedTextColor.WHITE))
                    .append(Component.text(String.format("%.2fm", playerStats.getTotalCaptureTime()), NamedTextColor.LIGHT_PURPLE))
            );

            plugin.getLogger().info("[DEBUG] handlePlayerStats: Sending Footer...");
            audience.sendMessage(Component.text("═══════════════════════════════════════════════", NamedTextColor.GOLD, TextDecoration.BOLD));
            plugin.getLogger().info("[DEBUG] handlePlayerStats: Finished sending all messages.");

        } catch (Exception e) {
            plugin.getLogger().severe("[DEBUG] handlePlayerStats: Error sending message components for " + displayName);
            sender.sendMessage("§cAn error occurred while displaying stats.");
        }
    }

    // Handles the initial /ss siege command and displays page 1
    private void handleSiegeStats(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command must be run by a player to support pagination.");
            // Consider adding non-paginated console output here if desired
            return;
        }
        Player player = (Player) sender;
        Audience audience = adventure.player(player); // Get audience for player

        if (args.length < 2) {
            audience.sendMessage(Component.text("Usage: /ss siege <townname> [number]", NamedTextColor.RED));
            return;
        }
        String townName = args[1].toLowerCase();
        int siegeNumber;
        if (args.length >= 3) {
            try {
                siegeNumber = Integer.parseInt(args[2]);
                if (siegeNumber <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                audience.sendMessage(Component.text("✖ Invalid siege number.", NamedTextColor.RED));
                return;
            }
        } else {
            siegeNumber = statsManager.getTownSiegeCount(townName);
            if (siegeNumber == 0) {
                audience.sendMessage(Component.text("✖ No sieges recorded for town: " + townName, NamedTextColor.RED));
                return;
            }
            audience.sendMessage(Component.text("(Showing latest siege: #" + siegeNumber + ")", NamedTextColor.GRAY));
        }

        // Generate Siege ID (still based on name for now)
        String targetSiegeId = townName + "_" + siegeNumber;
        SiegeStats siegeStats = statsManager.getSiegeStatsById(targetSiegeId);
        if (siegeStats == null) {
            audience.sendMessage(Component.text("✖ Siege data not found for " + townName + " #" + siegeNumber, NamedTextColor.RED));
            return;
        }

        // Fetch and sort participants by damage (using UUID keys)
        ConcurrentHashMap<UUID, SiegeStats.ParticipantMetrics> participants = siegeStats.getParticipantMetrics();
        List<Map.Entry<UUID, SiegeStats.ParticipantMetrics>> sortedList = participants.entrySet().stream()
                .sorted(Map.Entry.<UUID, SiegeStats.ParticipantMetrics>comparingByValue(
                        Comparator.comparingDouble(SiegeStats.ParticipantMetrics::getDamage).reversed()
                ))
                .collect(Collectors.toList());

        // Create and store the view state for the player
        SiegePageView view = new SiegePageView(targetSiegeId, sortedList, PLAYERS_PER_PAGE);
        playerSiegeViews.put(player.getUniqueId(), view);

        // Display the first page
        displaySiegePage(player, view); // Pass Player object for audience creation inside
    }

    // Displays a specific page of siege stats to a player
    private void displaySiegePage(Player player, SiegePageView view) {
        Audience audience = adventure.player(player); // Get audience for the specific player

        SiegeStats siegeStats = statsManager.getSiegeStatsById(view.siegeId);
        if (siegeStats == null) {
            audience.sendMessage(Component.text("Error: Could not retrieve siege data for ID: " + view.siegeId, NamedTextColor.RED));
            playerSiegeViews.remove(player.getUniqueId());
            return;
        }

        String townDisplayName = siegeStats.getTownName();
        int siegeNumber = siegeStats.getSiegeNumber();

        // --- Header ---
        audience.sendMessage(Component.text("═ Siege of " + townDisplayName + " #" + siegeNumber + " (" + view.siegeId + ") Page " + view.currentPage + "/" + view.totalPages + " ═", NamedTextColor.GOLD, TextDecoration.BOLD));
        String statusStr = siegeStats.isActive() ? "Active" : "Completed";
        NamedTextColor statusColor = siegeStats.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED;
        audience.sendMessage(Component.text("Status: ", NamedTextColor.YELLOW)
                .append(Component.text(statusStr, statusColor))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Duration: ", NamedTextColor.YELLOW))
                .append(Component.text(String.format("%.1f", siegeStats.getDurationMinutes()) + "m", NamedTextColor.WHITE)));

        // --- NEW: Display Current Siege Balance and Winning Side for Active Sieges ---
        if (siegeStats.isActive()) {
            Town town = TownyAPI.getInstance().getTown(townDisplayName); // TownyAPI is from com.palmergames.bukkit.towny
            if (town != null) {
                // Siege is from com.gmail.goosius.siegewar.objects.Siege
                Siege currentSiege = SiegeWarAPI.getSiegeOrNull(town); // SiegeWarAPI is from com.gmail.goosius.siegewar
                
                // Check if the siege from SiegeWar's perspective is also active and matches our tracked siege.
                // This ensures we are showing relevant real-time data.
                if (currentSiege != null && currentSiege.getStatus().isActive() && currentSiege.getTown().getName().equalsIgnoreCase(townDisplayName)) {
                    int balance = currentSiege.getSiegeBalance();
                    String currentWinningSideText;
                    NamedTextColor currentWinningSideColor;

                    if (balance >= 1) { // Attackers win if balance is 1 or more
                        currentWinningSideText = "ATTACKERS";
                        currentWinningSideColor = NamedTextColor.RED;
                    } else { // Defenders win if balance is 0 or negative
                        currentWinningSideText = "DEFENDERS";
                        currentWinningSideColor = NamedTextColor.BLUE;
                    }
                    audience.sendMessage(Component.text("Current Balance: ", NamedTextColor.YELLOW)
                        .append(Component.text(balance, NamedTextColor.WHITE))
                        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Currently Winning: ", NamedTextColor.YELLOW))
                        .append(Component.text(currentWinningSideText, Style.style(currentWinningSideColor, TextDecoration.BOLD)))
                    );
                } else {
                     audience.sendMessage(Component.text("Current Balance: ", NamedTextColor.YELLOW)
                        .append(Component.text("N/A (SiegeWar data mismatch or siege just ended)", NamedTextColor.GRAY)));
                }
            } else {
                 audience.sendMessage(Component.text("Current Balance: ", NamedTextColor.YELLOW)
                    .append(Component.text("N/A (Town not found)", NamedTextColor.GRAY)));
            }
        }
        // --- END NEW SECTION ---

        if (!siegeStats.isActive()) {
            int winner = siegeStats.whoWon(); // This is from your SiegeStats class
            Component winnerComp = switch (winner) {
                case 1 -> Component.text("ATTACKERS", NamedTextColor.RED, TextDecoration.BOLD); // Attackers won
                case 2 -> Component.text("DEFENDERS", NamedTextColor.BLUE, TextDecoration.BOLD); // Defenders won
                default -> Component.text("DRAW/UNKNOWN", NamedTextColor.GRAY);
            };
            audience.sendMessage(Component.text("Winner: ", NamedTextColor.YELLOW).append(winnerComp));
        }

        // --- Participants ---
        int totalParticipants = view.sortedParticipants.size();
        audience.sendMessage(Component.text("--- Participants (" + totalParticipants + " - Sorted by Damage) ---", NamedTextColor.GOLD));

        int startIndex = (view.currentPage - 1) * view.playersPerPage;
        int endIndex = Math.min(startIndex + view.playersPerPage, totalParticipants);

        if (totalParticipants == 0) {
            audience.sendMessage(Component.text("No participant stats recorded for this siege.", NamedTextColor.GRAY));
        } else if (startIndex >= totalParticipants) {
            audience.sendMessage(Component.text("No participants on this page.", NamedTextColor.GRAY)); 
        }
        else {
            for (int i = startIndex; i < endIndex; i++) {
                Map.Entry<UUID, SiegeStats.ParticipantMetrics> entry = view.sortedParticipants.get(i);
                UUID playerUUID = entry.getKey();
                SiegeStats.ParticipantMetrics metrics = entry.getValue();
                String playerName = Bukkit.getOfflinePlayer(playerUUID).getName();
                if (playerName == null) playerName = "Unknown (" + playerUUID.toString().substring(0, 6) + ")";

                double kda = (metrics.getDeaths() == 0) ? (metrics.getKills() + metrics.getAssists()) : ((double) (metrics.getKills() + metrics.getAssists()) / metrics.getDeaths());

                audience.sendMessage(Component.text(playerName + ":", NamedTextColor.WHITE));
                audience.sendMessage(Component.text()
                        .append(Component.text("  • ", NamedTextColor.GRAY))
                        .append(Component.text("K/D/A: ", NamedTextColor.WHITE))
                        .append(Component.text(metrics.getKills(), NamedTextColor.GREEN))
                        .append(Component.text("/", NamedTextColor.GRAY))
                        .append(Component.text(metrics.getDeaths(), NamedTextColor.RED))
                        .append(Component.text("/", NamedTextColor.GRAY))
                        .append(Component.text(metrics.getAssists(), NamedTextColor.AQUA))
                        .append(Component.text(String.format(" (KDA: %.2f)", kda), NamedTextColor.YELLOW))
                );
                audience.sendMessage(Component.text()
                        .append(Component.text("  • ", NamedTextColor.GRAY))
                        .append(Component.text("Damage: ", NamedTextColor.WHITE))
                        .append(Component.text(String.format("%.1f", metrics.getDamage()), NamedTextColor.GOLD))
                );
                audience.sendMessage(Component.text()
                        .append(Component.text("  • ", NamedTextColor.GRAY))
                        .append(Component.text("Capture Time: ", NamedTextColor.WHITE))
                        .append(Component.text(String.format("%.2fm", metrics.getControlTime()), NamedTextColor.LIGHT_PURPLE))
                );
                if (i < endIndex - 1) {
                    audience.sendMessage(Component.text("-------------------------", NamedTextColor.DARK_GRAY));
                }
            }
        }

        // --- Pagination Footer ---
        TextComponent.Builder footer = Component.text();
        footer.append(Component.text("═══ ", NamedTextColor.DARK_GRAY));
        if (view.currentPage > 1) {
            footer.append(
                    Component.text("[<< Back]", Style.style(NamedTextColor.RED, TextDecoration.BOLD))
                            .clickEvent(ClickEvent.runCommand("/ss move back"))
                            .hoverEvent(Component.text("Go to Page " + (view.currentPage - 1)))
            );
        } else {
            footer.append(Component.text("[<< Back]", Style.style(NamedTextColor.DARK_GRAY))); 
        }

        footer.append(Component.text(" Page " + view.currentPage + "/" + view.totalPages + " ", NamedTextColor.GRAY));

        if (view.currentPage < view.totalPages) {
            footer.append(
                    Component.text("[Next >>]", Style.style(NamedTextColor.GREEN, TextDecoration.BOLD))
                            .clickEvent(ClickEvent.runCommand("/ss move next"))
                            .hoverEvent(Component.text("Go to Page " + (view.currentPage + 1)))
            );
        } else {
            footer.append(Component.text("[Next >>]", Style.style(NamedTextColor.DARK_GRAY))); 
        }
        footer.append(Component.text(" ═══", NamedTextColor.DARK_GRAY));

        audience.sendMessage(footer.build());
    }

    // Handles /ss move <back|next>
    private void handleSiegeMove(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Pagination commands must be run by a player.");
            return;
        }
        Player player = (Player) sender;
        Audience audience = adventure.player(player); 
        UUID playerUUID = player.getUniqueId();

        if (args.length < 2) {
            audience.sendMessage(Component.text("Usage: /ss move <back|next>", NamedTextColor.RED));
            return;
        }

        SiegePageView currentView = playerSiegeViews.get(playerUUID);
        if (currentView == null) {
            audience.sendMessage(Component.text("Your siege view has expired. Please run '/ss siege <town> [num]' again.", NamedTextColor.RED));
            return;
        }

        String direction = args[1].toLowerCase();
        boolean changed = false;
        if (direction.equals("next")) {
            if (currentView.currentPage < currentView.totalPages) {
                currentView.currentPage++;
                changed = true;
            } else {
                audience.sendMessage(Component.text("You are already on the last page.", NamedTextColor.YELLOW));
            }
        } else if (direction.equals("back")) {
            if (currentView.currentPage > 1) {
                currentView.currentPage--;
                changed = true;
            } else {
                audience.sendMessage(Component.text("You are already on the first page.", NamedTextColor.YELLOW));
            }
        } else {
            audience.sendMessage(Component.text("Invalid direction. Use 'next' or 'back'.", NamedTextColor.RED));
        }

        if (changed) {
            displaySiegePage(player, currentView); 
        }
    }

    // Handles /ss top ...
    private void handleTopPlayers(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ss top <kills|dmg|deaths|assists|kda|captime> [count]");
            return;
        }
        String statType = args[1].toLowerCase();
        int topCount = 10;
        if (args.length >= 3) {
            try { topCount = Integer.parseInt(args[2]); if (topCount < 1 || topCount > 50) topCount = 10; }
            catch (NumberFormatException e) { sender.sendMessage("§cInvalid count."); return; }
        }

        ConcurrentHashMap<UUID, PlayerStats> allPlayerStats = statsManager.getAllPlayerStats();

        Comparator<PlayerStats> comparator;
        java.util.function.Function<PlayerStats, String> valueExtractor;
        String headerTitle;

        switch (statType) {
            case "kills": headerTitle = "Kills"; comparator = Comparator.comparingInt(PlayerStats::getTotalKills).reversed(); valueExtractor = stats -> ""+stats.getTotalKills(); break;
            case "damage": case "dmg": headerTitle = "Damage"; comparator = Comparator.comparingDouble(PlayerStats::getTotalDamage).reversed(); valueExtractor = stats -> String.format("%.1f", stats.getTotalDamage()); break;
            case "deaths": headerTitle = "Deaths"; comparator = Comparator.comparingInt(PlayerStats::getTotalDeaths).reversed(); valueExtractor = stats -> ""+stats.getTotalDeaths(); break;
            case "assists": headerTitle = "Assists"; comparator = Comparator.comparingInt(PlayerStats::getTotalAssists).reversed(); valueExtractor = stats -> ""+stats.getTotalAssists(); break;
            case "kda": headerTitle = "KDA"; comparator = Comparator.comparingDouble(PlayerStats::getKdaRatio).reversed(); valueExtractor = stats -> String.format("%.2f", stats.getKdaRatio()); break;
            case "captime": headerTitle = "CapTime"; comparator = Comparator.comparingDouble(PlayerStats::getTotalCaptureTime).reversed(); valueExtractor = stats -> String.format("%.2f", stats.getTotalCaptureTime()) + "m"; break;
            default: sender.sendMessage("§cInvalid stat type."); return;
        }

        sender.sendMessage("§6§l═ Top " + topCount + " Players by " + headerTitle + " ═");

        List<PlayerStats> sortedStats = allPlayerStats.values().stream()
                .filter(stats -> stats.getTotalSiegesParticipated() > 0) 
                .sorted(comparator)
                .limit(topCount)
                .collect(Collectors.toList());

        if (sortedStats.isEmpty()) { sender.sendMessage("§7No players found with recorded stats."); }
        else {
            for (int i = 0; i < sortedStats.size(); i++) {
                PlayerStats stats = sortedStats.get(i);
                sender.sendMessage(String.format("§e%d. §f%s §7» §6%s",
                        i + 1, stats.getLastKnownName(), valueExtractor.apply(stats)
                ));
            }
        }
        sender.sendMessage("§6§l═══════════════════════════");
    }

    // Handles /ss reset
    private void handleReset(CommandSender sender, String[] args) { 
        if (!sender.hasPermission("siegestats.admin")) {
            sender.sendMessage("§cYou do not have permission."); return;
        }
        statsManager.resetAllStats();
        sender.sendMessage("§a✔ All siege stats have been reset.");
        Audience broadcastAudience = adventure.permission("siegestats.admin");
        broadcastAudience.sendMessage(Component.text("[SiegeStats] All stats were reset by " + sender.getName(), NamedTextColor.YELLOW));
    }

    // Handles /ss debug ...
    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("siegestats.admin")) { /* ... */ return; }
        if (args.length < 2) { sender.sendMessage("§cUsage: /ss debug <check|load|save>"); return; }
        switch (args[1].toLowerCase()) {
            case "check": statsManager.debugDumpStats(sender); break;
            case "load": statsManager.loadStats(); sender.sendMessage("§aReloaded stats."); break;
            case "save": statsManager.saveStats(); sender.sendMessage("§aSaved stats."); break;
            default: sender.sendMessage("§cUnknown debug command.");
        }
    }

}