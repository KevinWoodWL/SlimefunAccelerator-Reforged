package com.balugaq.slimefunaccelerator.core.services;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watches the server's TPS and decides whether each accelerator round
 * should run at full rate, half rate, or be skipped entirely. Also
 * tracks per-group round deadlines so a wedged group can recover.
 * <p>
 * Decision matrix (when {@code enabled}):
 * <ul>
 *   <li>tps &lt; skipTps        → {@link Decision#SKIP}</li>
 *   <li>tps &lt; throttleTps    → {@link Decision#HALVE} (every other call returns)</li>
 *   <li>otherwise               → {@link Decision#RUN}</li>
 * </ul>
 */
public final class LoadMonitor {

    public enum Decision { RUN, HALVE, SKIP }

    private final boolean enabled;
    private final double throttleTps;
    private final double skipTps;
    private final int groupTimeoutMultiplier;

    private final ConcurrentHashMap<String, AtomicLong> halveCounter = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> roundDeadlineNanos = new ConcurrentHashMap<>();

    // Server#getTPS() is a Paper-only addition not present in Spigot-api 1.17.
    // Resolve reflectively so we still build against vanilla Spigot.
    private static final Method GET_TPS_METHOD;
    static {
        Method m = null;
        try {
            m = Bukkit.getServer().getClass().getMethod("getTPS");
        } catch (Throwable ignored) {
            // Fall through: getTPS not available, monitor will report 20.
        }
        GET_TPS_METHOD = m;
    }

    public LoadMonitor(boolean enabled, double throttleTps, double skipTps, int groupTimeoutMultiplier) {
        this.enabled = enabled;
        this.throttleTps = throttleTps;
        this.skipTps = skipTps;
        this.groupTimeoutMultiplier = Math.max(2, groupTimeoutMultiplier);
    }

    public boolean isEnabled() { return enabled; }
    public double getThrottleTps() { return throttleTps; }
    public double getSkipTps() { return skipTps; }
    public int getGroupTimeoutMultiplier() { return groupTimeoutMultiplier; }

    public double currentTps() {
        if (GET_TPS_METHOD == null) {
            return 20.0;
        }
        try {
            Object result = GET_TPS_METHOD.invoke(Bukkit.getServer());
            if (result instanceof double[] tps && tps.length > 0) {
                return tps[0];
            }
            return 20.0;
        } catch (Throwable ignored) {
            return 20.0;
        }
    }

    @NotNull
    public Decision decide() {
        if (!enabled) {
            return Decision.RUN;
        }
        double tps = currentTps();
        if (tps < skipTps) return Decision.SKIP;
        if (tps < throttleTps) return Decision.HALVE;
        return Decision.RUN;
    }

    /** When decide() == HALVE, this returns true on every other call per group. */
    public boolean shouldHalveThisCall(@NotNull String group) {
        AtomicLong c = halveCounter.computeIfAbsent(group, k -> new AtomicLong());
        return (c.incrementAndGet() & 1L) == 1L;
    }

    /** Arm a deadline for the current round of {@code group}. */
    public void markRoundStart(@NotNull String group, long periodTicks) {
        long now = System.nanoTime();
        long deadline = now + periodTicks * groupTimeoutMultiplier * 50_000_000L;
        roundDeadlineNanos.computeIfAbsent(group, k -> new AtomicLong()).set(deadline);
    }

    /** Disarm the deadline for {@code group}. */
    public void clearRound(@NotNull String group) {
        AtomicLong d = roundDeadlineNanos.get(group);
        if (d != null) d.set(0L);
    }

    /** True iff the previous round of {@code group} has blown past its deadline. */
    public boolean isRoundOverdue(@NotNull String group) {
        AtomicLong d = roundDeadlineNanos.get(group);
        if (d == null) return false;
        long deadline = d.get();
        return deadline > 0L && System.nanoTime() > deadline;
    }

    public void reset() {
        halveCounter.clear();
        roundDeadlineNanos.clear();
    }
}
