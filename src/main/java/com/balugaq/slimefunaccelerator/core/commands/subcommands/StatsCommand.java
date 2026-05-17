package com.balugaq.slimefunaccelerator.core.commands.subcommands;

import com.balugaq.slimefunaccelerator.api.utils.Lang;
import com.balugaq.slimefunaccelerator.core.commands.SubCommand;
import com.balugaq.slimefunaccelerator.core.listeners.Accelerator;
import com.balugaq.slimefunaccelerator.core.services.LoadMonitor;
import com.balugaq.slimefunaccelerator.core.services.TickProfiler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /sfa stats — show TPS and the top per-item tick-time stats. Useful
 * for spotting which Slimefun item is dragging the accelerator down
 * before it trips the circuit breaker.
 */
public class StatsCommand extends SubCommand {

    private static final int DEFAULT_TOP_N = 10;

    public StatsCommand(@NotNull JavaPlugin plugin) {
        super(plugin);
    }

    @Override
    public @NotNull String getName() {
        return "stats";
    }

    @Override
    public boolean canCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (args.length == 0) {
            return false;
        }
        return getName().equalsIgnoreCase(args[0]);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        int topN = DEFAULT_TOP_N;
        if (args.length >= 2) {
            try {
                topN = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }

        LoadMonitor monitor = Accelerator.getLoadMonitor();
        TickProfiler profiler = Accelerator.getTickProfiler();

        sender.sendMessage(Lang.getMessage("commands.stats.header"));
        if (monitor != null) {
            sender.sendMessage(Lang.getMessage("commands.stats.tps",
                    "tps", String.format(Locale.ROOT, "%.2f", monitor.currentTps())));
        }

        if (profiler == null || !profiler.isEnabled()) {
            sender.sendMessage(Lang.getMessage("commands.stats.profiler-off"));
            return true;
        }

        Map<String, TickProfiler.ItemStats> snap = profiler.snapshot();
        if (snap.isEmpty()) {
            sender.sendMessage(Lang.getMessage("commands.stats.no-samples"));
            return true;
        }

        long now = System.nanoTime();
        long trippedCount = snap.values().stream().filter(s -> s.isCurrentlyTripped(now)).count();
        sender.sendMessage(Lang.getMessage("commands.stats.tripped-summary",
                "tripped", String.valueOf(trippedCount),
                "total", String.valueOf(snap.size())));

        List<Map.Entry<String, TickProfiler.ItemStats>> sorted = snap.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, TickProfiler.ItemStats>>comparingLong(
                        e -> e.getValue().getEmaNanos()).reversed())
                .limit(topN)
                .toList();

        for (Map.Entry<String, TickProfiler.ItemStats> entry : sorted) {
            String id = entry.getKey();
            TickProfiler.ItemStats s = entry.getValue();
            String suffix = s.isCurrentlyTripped(now)
                    ? Lang.getMessage("commands.stats.row-tripped",
                        "cooldown", String.format(Locale.ROOT, "%.1f", s.getRemainingTripNanos(now) / 1_000_000_000.0))
                    : "";
            sender.sendMessage(Lang.getMessage("commands.stats.row",
                    "id", id,
                    "ema", String.format(Locale.ROOT, "%.2f", s.getEmaNanos() / 1_000_000.0),
                    "avg", String.format(Locale.ROOT, "%.2f", s.getAverageNanos() / 1_000_000.0),
                    "max", String.format(Locale.ROOT, "%.2f", s.getMaxNanos() / 1_000_000.0),
                    "samples", String.valueOf(s.getTotalSamples()),
                    "trips", String.valueOf(s.getLifetimeTripCount()),
                    "skipped", String.valueOf(s.getSkipped()),
                    "suffix", suffix));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return null;
    }
}
