package com.mimsswstats;

import com.gmail.goosius.siegewar.objects.Siege;
import com.mimsswstats.PlayerStats;
import com.mimsswstats.SiegeStatsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SiegeStatsCommand implements CommandExecutor {
    private final SiegeStatsPlugin plugin;


    public SiegeStatsCommand(SiegeStatsPlugin plugin) {
        this.plugin = plugin;
    }


    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (label.equalsIgnoreCase("siegestats")) {
            if (args.length == 0) {
                sender.sendMessage("§6§l══════ SiegeStats Commands ══════");
                sender.sendMessage("§e• §f/siegestats player <name> §7- View player's stats");
                sender.sendMessage("§e• §f/siegestats top <kills/damage/deaths> [count] §7- View top players");
                sender.sendMessage("§e• §f/siegestats siege <townname> [number] §7- View siege stats");
                if (sender.isOp()) {
                    sender.sendMessage("§e• §f/siegestats reset §7- Reset all stats (OP only)");
                }
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "player":
                    handlePlayerStats(sender, args);
                    break;
                case "top":
                    handleTopPlayers(sender, args);
                    break;
                case "siege":
                    handleSiegeStats(sender, args);
                    break;
                case "reset":
                    handleReset(sender);
                    break;
                default:
                    sender.sendMessage("§cUnknown subcommand. Use /siegestats for help.");
            }
        }
        if (args[0].equalsIgnoreCase("debug")) {
            if (!sender.isOp()) {
                sender.sendMessage("§cThis command is only for operators.");
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage("§cUsage: /siegestats debug <check/clear>");
                return true;
            }

            switch (args[1].toLowerCase()) {
                case "check":
                    plugin.getStatsManager().debugActiveSieges(sender);
                    break;
                case "clear":
                    plugin.getStatsManager().resetAllStats();
                    sender.sendMessage("§aAll stats have been reset.");
                    break;
                default:
                    sender.sendMessage("§cUnknown debug command.");
            }
            return true;
        }
        return true;

    }


    public void handlePlayerStats(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c/siegestats player <name>");
            return;
        }

        String playerName = args[1];
        PlayerStats playerStats = plugin.getStatsManager().getPlayerStats(playerName);

        if (playerStats == null) {
            sender.sendMessage("§c✖ Player " + playerName + " not found.");
            return;
        }

        double kdRatio = playerStats.getDeaths() == 0 ?
                playerStats.getKills() :
                (double) playerStats.getKills() / playerStats.getDeaths();


        double winRate = playerStats.getTotalSieges() == 0 ? 0 :
                (double) playerStats.getTotalWins() / playerStats.getTotalSieges() * 100;

        sender.sendMessage("§6§l══════ " + playerName + "'s Siege Stats ══════");
        sender.sendMessage("§e⚔ Combat Statistics:");
        sender.sendMessage("  §7• §fKills: §a" + playerStats.getKills());
        sender.sendMessage("  §7• §fDeaths: §c" + playerStats.getDeaths());
        sender.sendMessage("  §7• §fK/D Ratio: §e" + String.format("%.2f", kdRatio));
        sender.sendMessage("  §7• §fTotal Damage: §6" + String.format("%.1f", playerStats.getTotalDamage()));
        sender.sendMessage("");
        sender.sendMessage("§e🏰 Siege Participation:");
        sender.sendMessage("  §7• §fTotal Sieges: §b" + playerStats.getTotalSieges());
        sender.sendMessage("  §7• §fVictories: §a" + playerStats.getTotalWins());
        sender.sendMessage("  §7• §fDefeats: §c" + playerStats.getTotalLosses());
        sender.sendMessage("  §7• §fWin Rate: §e" + String.format("%.1f", winRate) + "%");
    }
    private void handleSiegeStats(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c/siegestats siege <townname> [number]");
            return;
        }

        String townName = args[1].toLowerCase();
        SiegeStatsManager statsManager = plugin.getStatsManager();

        sender.sendMessage("§7[Debug] Searching for siege data for town: " + townName);

        Integer siegeCount = statsManager.getTownSiegeCount(townName);
        sender.sendMessage("§7[Debug] Town siege count: " + (siegeCount != null ? siegeCount : "none"));

        int siegeNumber;
        if (args.length >= 3) {
            try {
                siegeNumber = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§c✖ Invalid siege number format");
                return;
            }
        } else {
            siegeNumber = siegeCount != null ? siegeCount : 0;
        }

        sender.sendMessage("§7[Debug] Looking up siege ID: " + townName + "_" + siegeNumber);

        SiegeStats siegeStats = statsManager.getSiegeStats(townName, siegeNumber);

        if (siegeStats == null) {
            sender.sendMessage("§c✖ No siege found for " + townName + " #" + siegeNumber);
            statsManager.debugActiveSieges(sender);
            return;
        }

        sender.sendMessage("§6§l══════ Siege of " + townName + " #" + siegeNumber + " ══════");

        ConcurrentHashMap<String, PlayerStats> participants = siegeStats.getParticipantStats();
        if (participants.isEmpty()) {
            sender.sendMessage("§7No participants recorded for this siege.");
            return;
        }

        sender.sendMessage("§eParticipant Statistics (Sorted by Damage):");
        participants.entrySet().stream()
                .filter(entry -> entry.getValue().getTotalDamage() > 0 ||
                        entry.getValue().getKills() > 0 ||
                        entry.getValue().getDeaths() > 0)
                .sorted((e1, e2) -> Double.compare(e2.getValue().getTotalDamage(),
                        e1.getValue().getTotalDamage()))
                .forEach(entry -> {
                    PlayerStats stats = entry.getValue();
                    sender.sendMessage(String.format("§f%s §7» §eDMG: §6%.1f §7| §aKills: §f%d §7| §cDeaths: §f%d",
                            entry.getKey(),
                            stats.getTotalDamage(),
                            stats.getKills(),
                            stats.getDeaths()
                    ));
                });

        if (!siegeStats.isActive()) {
            long duration = System.currentTimeMillis() - siegeStats.getStartTime();
            String durationStr = String.format("%.1f minutes", duration / (1000.0 * 60));
            sender.sendMessage("§eSiege Duration: §f" + durationStr);
        } else {
            sender.sendMessage("§eSiege Status: §aActive");
        }
    }
    private void showSiegeHistory(CommandSender sender, String[] args) {
        if(args.length < 2) {
            sender.sendMessage("§cUsage: /siegestats history <player>");
            return;
        }

        PlayerStats stats = plugin.getStatsManager().getPlayerStats(args[1]);
        List<SiegePerformance> history = stats.getLastPerformances();

        sender.sendMessage("§6§lLast 5 Sieges for " + args[1]);
        history.stream().limit(5).forEach(perf -> {
            String result = perf.isWon() ? "§aWIN" : "§cLOSS";
            sender.sendMessage(String.format("%s §7- K:%d D:%d DMG:%.1f",
                    result, perf.getKills(), perf.getDeaths(), perf.getDamage()));
        });
    }
    private void handleReset(CommandSender sender) {
        if (!sender.isOp()) {
            sender.sendMessage("§c✖ This command is only available to server operators.");
            return;
        }

        plugin.getStatsManager().resetAllStats();
        sender.sendMessage("§a✔ All siege stats have been reset successfully.");

        plugin.getServer().getOnlinePlayers().forEach(player -> {
            if (player.isOp() && player != sender) {
                player.sendMessage("§e[SiegeStats] §7All stats were reset by " + sender.getName());
            }
        });
    }

    private void handleTopPlayers(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c/siegestats top <kills/damage/deaths> [count]");
            return;
        }

        String statType = args[1].toLowerCase();
        int topCount = 10; 

        if (args.length >= 3) {
            try {
                topCount = Integer.parseInt(args[2]);
                if (topCount < 1) {
                    sender.sendMessage("§cCount must be greater than 0.");
                    return;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid number format.");
                return;
            }
        }

        SiegeStatsManager statsManager = plugin.getStatsManager();
        ConcurrentHashMap<String, PlayerStats> playerStats = statsManager.getPlayerStats();

        String headerTitle = switch (statType) {
            case "kills" -> "Kills";
            case "damage" -> "Damage";
            case "deaths" -> "Deaths";
            default -> null;
        };

        if (headerTitle == null) {
            sender.sendMessage("§cInvalid stat type. Use kills, damage, or deaths.");
            return;
        }

        sender.sendMessage("§6§l══════ Top " + topCount + " Players by " + headerTitle + " ══════");

        List<PlayerStats> sortedStats = playerStats.values().stream()
                .filter(stats -> {
                    return switch (statType) {
                        case "kills" -> stats.getKills() > 0;
                        case "damage" -> stats.getTotalDamage() > 0;
                        case "deaths" -> stats.getDeaths() > 0;
                        default -> false;
                    };
                })
                .sorted((s1, s2) -> {
                    return switch (statType) {
                        case "kills" -> Integer.compare(s2.getKills(), s1.getKills());
                        case "damage" -> Double.compare(s2.getTotalDamage(), s1.getTotalDamage());
                        case "deaths" -> Integer.compare(s2.getDeaths(), s1.getDeaths());
                        default -> 0;
                    };
                })
                .limit(topCount)
                .collect(Collectors.toList());

        // Display results
        if (sortedStats.isEmpty()) {
            sender.sendMessage("§7No players found with " + headerTitle.toLowerCase() + " greater than 0.");
            return;
        }

        for (int i = 0; i < sortedStats.size(); i++) {
            PlayerStats stats = sortedStats.get(i);
            String value = switch (statType) {
                case "kills" -> String.valueOf(stats.getKills());
                case "damage" -> String.format("%.1f", stats.getTotalDamage());
                case "deaths" -> String.valueOf(stats.getDeaths());
                default -> "0";
            };

            sender.sendMessage(String.format("§e%d. §f%s §7» §6%s %s",
                    i + 1,
                    stats.getPlayerName(),
                    value,
                    statType.equals("damage") ? "damage" : ""));
        }
    }
}
