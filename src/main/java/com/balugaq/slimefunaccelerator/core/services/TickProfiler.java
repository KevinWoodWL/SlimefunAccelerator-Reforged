package com.balugaq.slimefunaccelerator.core.services;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Per-item tick profiler with a circuit breaker.
 * <p>
 * The profiler measures the execution time of every per-block tick we run.
 * When a Slimefun item consistently overruns the configured threshold,
 * the breaker trips. Behaviour during a trip is governed by {@link Action}:
 * {@link Action#THROTTLE} lets every {@code throttleDivisor}-th call
 * through so machines keep working (just slower) while still relieving
 * the server, and {@link Action#SKIP} drops every call until the
 * cooldown expires. After cooldown a probe call decides whether to
 * re-trip immediately or recover.
 * <p>
 * All counters use atomic operations so {@link #measure(String, Runnable)}
 * can be called from any thread (region scheduler, async worker, or main).
 */
public final class TickProfiler {

    public enum Action { THROTTLE, SKIP }

    public static final class ItemStats {
        final AtomicLong emaNanos = new AtomicLong();
        final AtomicLong totalSamples = new AtomicLong();
        final AtomicLong totalNanos = new AtomicLong();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong throttleCounter = new AtomicLong();
        final AtomicLong trippedUntilNanos = new AtomicLong();
        final AtomicInteger lifetimeTripCount = new AtomicInteger();
        final AtomicInteger consecutiveOverruns = new AtomicInteger();
        final AtomicInteger consecutiveOk = new AtomicInteger();

        public long getEmaNanos() { return emaNanos.get(); }
        public long getTotalSamples() { return totalSamples.get(); }
        public long getTotalNanos() { return totalNanos.get(); }
        public long getMaxNanos() { return maxNanos.get(); }
        public long getSkipped() { return skipped.get(); }
        public int getLifetimeTripCount() { return lifetimeTripCount.get(); }
        public boolean isCurrentlyTripped(long nowNanos) { return trippedUntilNanos.get() > nowNanos; }
        public long getRemainingTripNanos(long nowNanos) {
            long until = trippedUntilNanos.get();
            return until > nowNanos ? until - nowNanos : 0L;
        }
        public double getAverageNanos() {
            long n = totalSamples.get();
            return n == 0 ? 0.0 : (double) totalNanos.get() / n;
        }
    }

    private final boolean enabled;
    private final long thresholdNanos;
    private final int overrunThreshold;
    private final long cooldownNanos;
    private final int recoverySamples;
    private final double emaAlpha;
    private final Action action;
    private final int throttleDivisor;
    private final Logger logger;

    private final ConcurrentHashMap<String, ItemStats> stats = new ConcurrentHashMap<>();

    public TickProfiler(boolean enabled,
                        long maxTickTimeMicros,
                        int overrunThreshold,
                        long cooldownTicks,
                        int recoverySamples,
                        double emaAlpha,
                        @NotNull Action action,
                        int throttleDivisor,
                        @NotNull Logger logger) {
        this.enabled = enabled;
        this.thresholdNanos = Math.max(1L, maxTickTimeMicros) * 1_000L;
        this.overrunThreshold = Math.max(1, overrunThreshold);
        this.cooldownNanos = Math.max(1L, cooldownTicks) * 50_000_000L; // 50ms per tick
        this.recoverySamples = Math.max(1, recoverySamples);
        // Clamp alpha to (0,1). Smaller alpha = smoother EMA.
        this.emaAlpha = Math.min(0.9, Math.max(0.01, emaAlpha));
        this.action = action;
        this.throttleDivisor = Math.max(2, throttleDivisor);
        this.logger = logger;
    }

    public boolean isEnabled() { return enabled; }
    public long getThresholdNanos() { return thresholdNanos; }
    public long getCooldownNanos() { return cooldownNanos; }
    public Action getAction() { return action; }
    public int getThrottleDivisor() { return throttleDivisor; }

    /**
     * Fast-path check for the hot loop. In THROTTLE mode this never short-
     * circuits (every Nth tripped call still runs), so callers should still
     * fall through to {@link #measure(String, Runnable)} for the actual
     * decision. Used by the accelerator to skip plumbing entirely when a
     * SKIP-mode item is hard-tripped.
     */
    public boolean isHardSkipped(@NotNull String itemId, long nowNanos) {
        if (!enabled || action != Action.SKIP) return false;
        ItemStats s = stats.get(itemId);
        return s != null && s.isCurrentlyTripped(nowNanos);
    }

    /**
     * Run {@code task} measured against the breaker. Behavior when the
     * breaker is open depends on {@link Action}:
     * <ul>
     *   <li>SKIP — task is dropped, skip counter incremented.</li>
     *   <li>THROTTLE — task runs every {@code throttleDivisor}-th call
     *       (so the machine still makes progress, just slower); the rest
     *       increment the skip counter.</li>
     * </ul>
     */
    public void measure(@NotNull String itemId, @NotNull Runnable task) {
        if (!enabled) {
            task.run();
            return;
        }

        long now = System.nanoTime();
        ItemStats s = stats.computeIfAbsent(itemId, k -> new ItemStats());
        if (s.isCurrentlyTripped(now)) {
            if (action == Action.SKIP) {
                s.skipped.incrementAndGet();
                return;
            }
            // THROTTLE: let every Nth call through. The let-through still
            // gets measured so EMA reflects the actual cost during throttle.
            long n = s.throttleCounter.incrementAndGet();
            if (n % throttleDivisor != 0L) {
                s.skipped.incrementAndGet();
                return;
            }
        }

        long start = System.nanoTime();
        try {
            task.run();
        } finally {
            long elapsed = System.nanoTime() - start;
            recordSample(itemId, s, elapsed, start);
        }
    }

    private void recordSample(String itemId, ItemStats s, long elapsed, long nowNanos) {
        s.totalSamples.incrementAndGet();
        s.totalNanos.addAndGet(elapsed);

        long prevMax;
        do {
            prevMax = s.maxNanos.get();
            if (elapsed <= prevMax) break;
        } while (!s.maxNanos.compareAndSet(prevMax, elapsed));

        long prevEma = s.emaNanos.get();
        long newEma = prevEma == 0
                ? elapsed
                : (long) (prevEma * (1 - emaAlpha) + elapsed * emaAlpha);
        s.emaNanos.set(newEma);

        if (elapsed > thresholdNanos) {
            s.consecutiveOk.set(0);
            int n = s.consecutiveOverruns.incrementAndGet();
            // First trip needs overrunThreshold; subsequent trips (post-cooldown
            // probe failed) trip on the first overrun.
            int needed = s.lifetimeTripCount.get() == 0 ? overrunThreshold : 1;
            if (n >= needed) {
                long until = nowNanos + cooldownNanos;
                long prevUntil = s.trippedUntilNanos.get();
                if (prevUntil <= nowNanos && s.trippedUntilNanos.compareAndSet(prevUntil, until)) {
                    s.lifetimeTripCount.incrementAndGet();
                    s.consecutiveOverruns.set(0);
                    s.throttleCounter.set(0L);
                    String mode = action == Action.SKIP
                            ? String.format("skipping for %.1fs", cooldownNanos / 1_000_000_000.0)
                            : String.format("throttling to 1/%d for %.1fs",
                                    throttleDivisor, cooldownNanos / 1_000_000_000.0);
                    logger.warning(String.format(
                            "[CircuitBreaker] Item '%s' tripped: last %.2fms, avg %.2fms (n=%d), max %.2fms. %s.",
                            itemId,
                            elapsed / 1_000_000.0,
                            s.getAverageNanos() / 1_000_000.0,
                            s.totalSamples.get(),
                            s.maxNanos.get() / 1_000_000.0,
                            mode));
                }
            }
        } else {
            s.consecutiveOverruns.set(0);
            int ok = s.consecutiveOk.incrementAndGet();
            // Once an item has settled for recoverySamples consecutive ticks
            // under threshold, treat it as fully healthy again.
            if (ok >= recoverySamples && s.lifetimeTripCount.get() > 0) {
                if (s.consecutiveOk.compareAndSet(ok, 0)) {
                    int prior = s.lifetimeTripCount.getAndSet(0);
                    if (prior > 0) {
                        logger.info(String.format(
                                "[CircuitBreaker] Item '%s' recovered after %d clean samples.",
                                itemId, recoverySamples));
                    }
                }
            }
        }
    }

    @NotNull
    public Map<String, ItemStats> snapshot() {
        return new HashMap<>(stats);
    }

    public void reset(@NotNull String itemId) {
        stats.remove(itemId);
    }

    public void resetAll() {
        stats.clear();
    }
}
