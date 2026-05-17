package com.balugaq.slimefunaccelerator.core.listeners;

import com.balugaq.slimefunaccelerator.api.AcceleratorSettings;
import com.balugaq.slimefunaccelerator.api.utils.Accelerates;
import com.balugaq.slimefunaccelerator.api.utils.ReflectionUtil;
import com.balugaq.slimefunaccelerator.core.managers.AcceleratesLoader;
import com.balugaq.slimefunaccelerator.core.managers.ConfigManager;
import com.balugaq.slimefunaccelerator.core.services.LoadMonitor;
import com.balugaq.slimefunaccelerator.core.services.RegionTaskScheduler;
import com.balugaq.slimefunaccelerator.core.services.TickProfiler;
import com.balugaq.slimefunaccelerator.implementation.SlimefunAccelerator;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.events.BlockPlacerPlaceEvent;
import io.github.thebusybiscuit.slimefun4.api.events.SlimefunItemRegistryFinalizedEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemState;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public class Accelerator implements Listener {
    public static final int EXTRA_TICKER_FLAG = 0b00000001;
    private static final String SLIMEFUN_TIMEIT_TICKER = "com.balugaq.sftimeit.api.MonitoringBlockTicker";
    private static final ClassValue<Boolean> TIMEIT_CLASS_CACHE = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                if (SLIMEFUN_TIMEIT_TICKER.equals(c.getName())) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    };
    private static final Map<Class<?>, Object> TIMEIT_CLASS_LOCKS = new ConcurrentHashMap<>(8);
    private static final Map<String, Integer> chunkBucketHint = new ConcurrentHashMap<>(16);
    private static volatile TickProfiler tickProfiler;
    private static volatile LoadMonitor loadMonitor;
    public static final Map<String, Set<Location>> allTickerLocations = new ConcurrentHashMap<>(16);
    public static final Map<SlimefunItem, BlockTicker> originalTickers = new ConcurrentHashMap<>(16);
    public static final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>(16);
    public static final Map<String, Set<Location>> tickLocations = new ConcurrentHashMap<>(16);
    public static final Map<String, Object> asyncLocks = new ConcurrentHashMap<>(16);
    public static final Set<String> extraTickers = ConcurrentHashMap.newKeySet(16);
    private static final AtomicBoolean enabled = new AtomicBoolean(false);
    private static final AtomicInteger lifecycleId = new AtomicInteger(0);
    private static volatile Boolean cnSlimefunCached;

    public static boolean isCNSlimefun() {
        Boolean cached = cnSlimefunCached;
        if (cached != null) {
            return cached;
        }
        SlimefunAccelerator plugin = SlimefunAccelerator.getInstance();
        if (plugin == null) {
            return false;
        }
        boolean resolved = plugin.getIntegrationManager().isCNSlimefun();
        cnSlimefunCached = resolved;
        return resolved;
    }

    public static TickProfiler getTickProfiler() {
        TickProfiler local = tickProfiler;
        if (local != null) {
            return local;
        }
        // Build on demand from current config — first caller wins.
        SlimefunAccelerator plugin = SlimefunAccelerator.getInstance();
        if (plugin == null) {
            return null;
        }
        synchronized (Accelerator.class) {
            if (tickProfiler != null) {
                return tickProfiler;
            }
            ConfigManager cm = plugin.getConfigManager();
            TickProfiler.Action action;
            try {
                action = TickProfiler.Action.valueOf(cm.getCircuitBreakerAction().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown circuit-breaker.action '"
                        + cm.getCircuitBreakerAction() + "', falling back to THROTTLE.");
                action = TickProfiler.Action.THROTTLE;
            }
            tickProfiler = new TickProfiler(
                    cm.isCircuitBreakerEnabled(),
                    cm.getCircuitBreakerMaxMicros(),
                    cm.getCircuitBreakerOverrunThreshold(),
                    cm.getCircuitBreakerCooldownTicks(),
                    cm.getCircuitBreakerRecoverySamples(),
                    cm.getCircuitBreakerEmaAlpha(),
                    action,
                    cm.getCircuitBreakerThrottleDivisor(),
                    plugin.getLogger());
            return tickProfiler;
        }
    }

    /** Drop the cached profiler so a subsequent get rebuilds from config. */
    public static void resetTickProfiler() {
        synchronized (Accelerator.class) {
            tickProfiler = null;
        }
    }

    public static LoadMonitor getLoadMonitor() {
        LoadMonitor local = loadMonitor;
        if (local != null) {
            return local;
        }
        SlimefunAccelerator plugin = SlimefunAccelerator.getInstance();
        if (plugin == null) {
            return null;
        }
        synchronized (Accelerator.class) {
            if (loadMonitor != null) {
                return loadMonitor;
            }
            ConfigManager cm = plugin.getConfigManager();
            loadMonitor = new LoadMonitor(
                    cm.isLoadAwareEnabled(),
                    cm.getLoadAwareThrottleTps(),
                    cm.getLoadAwareSkipTps(),
                    cm.getGroupTimeoutMultiplier());
            return loadMonitor;
        }
    }

    public static void resetLoadMonitor() {
        synchronized (Accelerator.class) {
            loadMonitor = null;
        }
    }

    private static void finishRound(String group, AtomicBoolean groupRunning) {
        LoadMonitor monitor = loadMonitor;
        if (monitor != null) {
            monitor.clearRound(group);
        }
        groupRunning.set(false);
    }

    private static void accelerate(String group, Set<SlimefunItem> items, int expectedLifecycleId) {
        if (!isActiveLifecycle(expectedLifecycleId)) {
            return;
        }

        AtomicBoolean groupRunning = running.get(group);
        if (groupRunning == null) {
            return;
        }

        AcceleratorSettings settings = Accelerates.getAccelerateSettings().get(group);
        if (settings == null) {
            return;
        }

        LoadMonitor monitor = getLoadMonitor();

        // Force-recover a wedged group: if the previous round is still
        // marked as running well past its expected completion, drop the
        // queued work and let the next round take over from scratch.
        if (monitor != null && groupRunning.get() && monitor.isRoundOverdue(group)) {
            tickLocations.put(group, ConcurrentHashMap.newKeySet());
            monitor.clearRound(group);
            groupRunning.set(false);
            SlimefunAccelerator.getInstance().getLogger().warning(
                    "Group '" + group + "' round overdue (> " + monitor.getGroupTimeoutMultiplier()
                            + "x period). Dropping queued ticks and resuming.");
        }

        // TPS-aware throttle.
        if (monitor != null) {
            LoadMonitor.Decision decision = monitor.decide();
            if (decision == LoadMonitor.Decision.SKIP) {
                return;
            }
            if (decision == LoadMonitor.Decision.HALVE && monitor.shouldHalveThisCall(group)) {
                return;
            }
        }

        if (!groupRunning.compareAndSet(false, true)) {
            return;
        }

        if (monitor != null) {
            monitor.markRoundStart(group, settings.getPeriod());
        }

        try {

            for (SlimefunItem slimefunItem : items) {
                if (slimefunItem.isDisabled()) {
                    continue;
                }
                BlockTicker blockTicker = Accelerates.getTickers().get(slimefunItem.getId());
                if (blockTicker == null) {
                    continue;
                }

                getExecutionTicker(settings, blockTicker).uniqueTick();
            }

            Set<Location> queue = tickLocations.put(group, ConcurrentHashMap.newKeySet());
            if (queue == null || queue.isEmpty()) {
                finishRound(group, groupRunning);
                return;
            }

            int hint = chunkBucketHint.getOrDefault(group, 16);
            // HashMap needs ~1.5x capacity for load factor 0.75.
            Map<ChunkPosition, Set<Location>> locationsByChunk = new HashMap<>(Math.max(16, hint + (hint >> 1)));
            for (Location location : queue) {
                if (location == null || location.getWorld() == null) {
                    continue;
                }

                locationsByChunk
                        .computeIfAbsent(ChunkPosition.from(location), ignored -> new HashSet<>())
                        .add(location);
            }

            if (locationsByChunk.isEmpty()) {
                finishRound(group, groupRunning);
                return;
            }

            chunkBucketHint.put(group, locationsByChunk.size());
            SlimefunAccelerator plugin = SlimefunAccelerator.getInstance();
            AtomicInteger pendingTasks = new AtomicInteger(locationsByChunk.size());
            for (Map.Entry<ChunkPosition, Set<Location>> entry : locationsByChunk.entrySet()) {
                ChunkPosition chunk = entry.getKey();
                Set<Location> locations = entry.getValue();
                try {
                    RegionTaskScheduler.execute(plugin, chunk.world(), chunk.chunkX(), chunk.chunkZ(), () -> {
                        try {
                            if (isActiveLifecycle(expectedLifecycleId)) {
                                tickChunk(group, settings, locations, pendingTasks, groupRunning, expectedLifecycleId);
                            }
                        } finally {
                            if (pendingTasks.decrementAndGet() == 0) {
                                finishRound(group, groupRunning);
                            }
                        }
                    });
                } catch (RuntimeException scheduleFailure) {
                    // RegionTaskScheduler.execute rejected synchronously: balance the counter so
                    // the next round can run. Swallow so other chunks still get scheduled.
                    if (pendingTasks.decrementAndGet() == 0) {
                        finishRound(group, groupRunning);
                    }
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Failed to schedule chunk tick for group '" + group + "'", scheduleFailure);
                }
            }
        } catch (RuntimeException exception) {
            finishRound(group, groupRunning);
            throw exception;
        }
    }

    private static void tickChunk(String group, AcceleratorSettings settings, Set<Location> locations, AtomicInteger pendingTasks, AtomicBoolean groupRunning, int expectedLifecycleId) {
        List<Runnable> asyncTasks = new ArrayList<>();
        for (Location location : locations) {
            if (!isActiveLifecycle(expectedLifecycleId)) {
                return;
            }
            tickLocation(settings, location, asyncTasks);
        }

        runAsyncTickerBatch(group, asyncTasks, pendingTasks, groupRunning, expectedLifecycleId);
    }

    private static void tickLocation(AcceleratorSettings settings, Location location, List<Runnable> asyncTasks) {
        World world = location.getWorld();
        if (world == null || (!settings.isTickUnload() && !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))) {
            return;
        }

        SlimefunItem item = isCNSlimefun() ? StorageCacheUtils.getSfItem(location) : BlockStorage.check(location);
        if (item == null || item.isDisabledIn(world)) {
            return;
        }

        BlockTicker ticker = Accelerates.getTickers().get(item.getId());
        if (ticker == null) {
            return;
        }
        // Cheap fast-path: in SKIP mode if the breaker is open we can skip
        // the BlockStorage lookup entirely. In THROTTLE mode we still need
        // to enter measure() so it can let every Nth tripped call through.
        String itemId = item.getId();
        TickProfiler profiler = getTickProfiler();
        if (profiler != null && profiler.isHardSkipped(itemId, System.nanoTime())) {
            return;
        }
        BlockTicker executionTicker = getExecutionTicker(settings, ticker);

        Block block = location.getBlock();
        if (isCNSlimefun()) {
            SlimefunBlockData config = StorageCacheUtils.getBlock(location);
            if (config == null) {
                removeExtraTickerLocation(itemId, location);
                return;
            }

            runTicker(settings, executionTicker,
                    profiledRunnable(profiler, itemId, () -> executionTicker.tick(block, item, config)),
                    asyncTasks);
        } else {
            Config config = BlockStorage.getLocationInfo(location);
            if (config == null) {
                removeExtraTickerLocation(itemId, location);
                return;
            }

            runTicker(settings, executionTicker,
                    profiledRunnable(profiler, itemId, () -> executionTicker.tick(block, item, config)),
                    asyncTasks);
        }
    }

    private static Runnable profiledRunnable(TickProfiler profiler, String itemId, Runnable task) {
        if (profiler == null || !profiler.isEnabled()) {
            return task;
        }
        return () -> profiler.measure(itemId, task);
    }

    private static BlockTicker getExecutionTicker(AcceleratorSettings settings, BlockTicker ticker) {
        if (!settings.isAsync() || !isSlimefunTimeitTicker(ticker)) {
            return ticker;
        }

        BlockTicker unwrapped = unwrapTicker(ticker, new HashSet<>());
        if (unwrapped != null && unwrapped != ticker) {
            return unwrapped;
        }

        return ticker;
    }

    private static void runTicker(AcceleratorSettings settings, BlockTicker ticker, Runnable task, List<Runnable> asyncTasks) {
        if (settings.isAsync() && !ticker.isSynchronized()) {
            asyncTasks.add(() -> executeTicker(ticker, task));
            return;
        }

        executeTicker(ticker, task);
    }

    private static void executeTicker(BlockTicker ticker, Runnable task) {
        Class<?> clazz = ticker.getClass();
        if (TIMEIT_CLASS_CACHE.get(clazz)) {
            Object lock = TIMEIT_CLASS_LOCKS.computeIfAbsent(clazz, k -> new Object());
            synchronized (lock) {
                task.run();
            }
            return;
        }

        task.run();
    }

    private static boolean isSlimefunTimeitTicker(BlockTicker ticker) {
        return TIMEIT_CLASS_CACHE.get(ticker.getClass());
    }

    private static BlockTicker unwrapTicker(BlockTicker ticker, Set<Object> visited) {
        if (!visited.add(ticker)) {
            return ticker;
        }

        Class<?> clazz = ticker.getClass();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!BlockTicker.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(ticker);
                    if (value instanceof BlockTicker nested && nested != ticker) {
                        if (isSlimefunTimeitTicker(nested)) {
                            return unwrapTicker(nested, visited);
                        }
                        return nested;
                    }
                } catch (IllegalAccessException ignored) {
                    // Keep the wrapped ticker if the monitor implementation changes.
                }
            }
            clazz = clazz.getSuperclass();
        }

        return ticker;
    }

    private static void runAsyncTickerBatch(String group, List<Runnable> asyncTasks, AtomicInteger pendingTasks, AtomicBoolean groupRunning, int expectedLifecycleId) {
        if (asyncTasks.isEmpty()) {
            return;
        }

        pendingTasks.incrementAndGet();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(SlimefunAccelerator.getInstance(), () -> {
                try {
                    if (!isActiveLifecycle(expectedLifecycleId)) {
                        return;
                    }

                    Object lock = asyncLocks.computeIfAbsent(group, ignored -> new Object());
                    synchronized (lock) {
                        for (Runnable asyncTask : asyncTasks) {
                            if (!isActiveLifecycle(expectedLifecycleId)) {
                                return;
                            }
                            asyncTask.run();
                        }
                    }
                } finally {
                    completeTask(group, pendingTasks, groupRunning);
                }
            });
        } catch (RuntimeException exception) {
            completeTask(group, pendingTasks, groupRunning);
            throw exception;
        }
    }

    private static void completeTask(String group, AtomicInteger pendingTasks, AtomicBoolean groupRunning) {
        if (pendingTasks.decrementAndGet() == 0) {
            finishRound(group, groupRunning);
        }
    }

    private static void removeExtraTickerLocation(String itemId, Location location) {
        if (((int) location.getYaw() & EXTRA_TICKER_FLAG) == 0) {
            return;
        }

        Set<Location> extra = allTickerLocations.get(itemId);
        if (extra == null) {
            return;
        }

        Location storedLocation = location.clone();
        storedLocation.setYaw(0);
        storedLocation.setPitch(0);
        extra.remove(storedLocation);
    }

    private record ChunkPosition(World world, int chunkX, int chunkZ) {
        private static ChunkPosition from(Location location) {
            return new ChunkPosition(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
    }

    public static boolean isRunning() {
        return enabled.get();
    }

    private static boolean isActiveLifecycle(int expectedLifecycleId) {
        return enabled.get() && lifecycleId.get() == expectedLifecycleId;
    }

    public static boolean load() {
        if (!enabled.compareAndSet(false, true)) {
            return false;
        }

        try {
            loadInternal();
            return true;
        } catch (RuntimeException exception) {
            shutdownInternal();
            Accelerates.shutdown();
            enabled.set(false);
            throw exception;
        }
    }

    private static void loadInternal() {
        SlimefunAccelerator.getInstance().getLogger().info("Loading accelerates...");
        int currentLifecycleId = lifecycleId.incrementAndGet();
        Accelerates.shutdown();
        AcceleratesLoader.loadAccelerates();

        Map<String, AcceleratorSettings> allSettings = new HashMap<>();
        Map<String, Set<SlimefunItem>> accelerates = Accelerates.getAccelerates();
        for (Map.Entry<String, Set<SlimefunItem>> entry : accelerates.entrySet()) {
            String group = entry.getKey();
            tickLocations.put(group, ConcurrentHashMap.newKeySet());
            running.put(group, new AtomicBoolean(false));
            Set<SlimefunItem> items = entry.getValue();
            AcceleratorSettings settings = Accelerates.getAccelerateSettings().get(group);
            if (settings == null) {
                settings = new AcceleratorSettings();
            }

            allSettings.put(group, settings);
            if (settings.isEnabledExtraTicker()) {
                for (SlimefunItem slimefunItem : items) {
                    extraTickers.add(slimefunItem.getId());
                }
            } else if (settings.isRemoveOriginalTicker()) {
                SlimefunAccelerator.getInstance().getLogger().warning(
                        "Group '" + group + "': remove-original-ticker=true with extra-ticker.enabled=false. "
                                + "Placed blocks of this group will NEVER tick — set extra-ticker.enabled=true "
                                + "or remove-original-ticker=false.");
            }

            for (SlimefunItem slimefunItem : items) {
                BlockTicker blockTicker = slimefunItem.getBlockTicker();
                if (blockTicker == null) {
                    continue;
                }
                originalTickers.put(slimefunItem, blockTicker);
                ItemState state = slimefunItem.getState();
                ReflectionUtil.setValue(slimefunItem, SlimefunItem.class, "state", ItemState.UNREGISTERED);

                if (isCNSlimefun()) {
                    if (settings.isRemoveOriginalTicker()) {
                        slimefunItem.addItemHandler(new BlockTicker() {
                            @Override
                            public boolean isSynchronized() {
                                return false;
                            }

                            @Override
                            public void tick(Block block, SlimefunItem slimefunItem, SlimefunBlockData config) {
                                // do nothing
                            }
                        });
                    } else {
                        slimefunItem.addItemHandler(new BlockTicker() {
                            @Override
                            public boolean isSynchronized() {
                                return true;
                            }

                            @Override
                            public void tick(@NotNull Block block, SlimefunItem slimefunItem, SlimefunBlockData config) {
                                Location location = block.getLocation();
                                Set<Location> queue = tickLocations.get(group);
                                if (queue != null) {
                                    queue.add(location);
                                }
                            }
                        });
                    }
                } else {
                    if (settings.isRemoveOriginalTicker()) {
                        slimefunItem.addItemHandler(new BlockTicker() {
                            @Override
                            public boolean isSynchronized() {
                                return false;
                            }

                            @Override
                            public void tick(Block block, SlimefunItem slimefunItem, Config config) {
                                // do nothing
                            }
                        });
                    } else {
                        slimefunItem.addItemHandler(new BlockTicker() {
                            @Override
                            public boolean isSynchronized() {
                                return true;
                            }

                            @Override
                            public void tick(@NotNull Block block, SlimefunItem slimefunItem, Config config) {
                                Location location = block.getLocation();
                                Set<Location> queue = tickLocations.get(group);
                                if (queue != null) {
                                    queue.add(location);
                                }
                            }
                        });
                    }
                }

                ReflectionUtil.setValue(slimefunItem, SlimefunItem.class, "state", state);
            }

            int taskId = Bukkit.getScheduler().runTaskTimer(SlimefunAccelerator.getInstance(),
                    () -> accelerate(group, items, currentLifecycleId),
                    settings.getDelay(),
                    settings.getPeriod()
            ).getTaskId();
            Accelerates.getTaskIds().put(group, taskId);
        }

        if (isCNSlimefun()) {
            for (Map.Entry<String, Set<Location>> entry : ExtraTickerCNVersion.getAllTickLocations().entrySet()) {
                if (!extraTickers.contains(entry.getKey())) {
                    continue;
                }
                allTickerLocations.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet())
                        .addAll(entry.getValue());
            }
        } else {
            for (World world : Bukkit.getWorlds()) {
                BlockStorage blockStorage = BlockStorage.getStorage(world);
                // just ignore this warning
                if (blockStorage == null) {
                    continue;
                }
                @SuppressWarnings("unchecked") Map<Location, Config> storage = (Map<Location, Config>) ReflectionUtil.invokeMethod(blockStorage, "getRawStorage");
                if (storage == null) {
                    continue;
                }
                for (Map.Entry<Location, Config> entry : storage.entrySet()) {
                    Location location = entry.getKey();
                    SlimefunItem slimefunItem = BlockStorage.check(location);
                    if (slimefunItem == null) {
                        continue;
                    }

                    if (!extraTickers.contains(slimefunItem.getId())) {
                        continue;
                    }

                    allTickerLocations.computeIfAbsent(slimefunItem.getId(), k -> ConcurrentHashMap.newKeySet()).add(location);
                }
            }
        }

        for (Map.Entry<String, AcceleratorSettings> entry : allSettings.entrySet()) {
            String group = entry.getKey();
            AcceleratorSettings settings = entry.getValue();
            Set<SlimefunItem> items = accelerates.get(entry.getKey());
            Set<String> ids = new HashSet<>();
            for (SlimefunItem slimefunItem : items) {
                ids.add(slimefunItem.getId());
            }

            if (!settings.isEnabledExtraTicker()) {
                continue;
            }

            int taskId = Bukkit.getScheduler().runTaskTimer(SlimefunAccelerator.getInstance(),
                    () -> queueExtraTicks(group, settings, ids, currentLifecycleId),
                    settings.getExtraTickerDelay(),
                    settings.getExtraTickerPeriod()
            ).getTaskId();
            Accelerates.getTaskIds().put(group + ":extra-ticker", taskId);
        }
    }

    private static void queueExtraTicks(String group, AcceleratorSettings settings, Set<String> ids, int expectedLifecycleId) {
        if (!isActiveLifecycle(expectedLifecycleId)) {
            return;
        }

        Map<ChunkPosition, Map<String, Set<Location>>> locationsByChunk = new HashMap<>();
        for (String id : ids) {
            SlimefunItem slimefunItem = SlimefunItem.getById(id);
            if (slimefunItem == null || slimefunItem.isDisabled()) {
                continue;
            }

            Set<Location> locations = allTickerLocations.get(id);
            if (locations == null) {
                continue;
            }

            Set<Location> snapshot = new HashSet<>(locations);

            for (Location location : snapshot) {
                if (location == null || location.getWorld() == null) {
                    continue;
                }

                locationsByChunk
                        .computeIfAbsent(ChunkPosition.from(location), ignored -> new HashMap<>())
                        .computeIfAbsent(id, ignored -> new HashSet<>())
                        .add(location);
            }
        }

        SlimefunAccelerator plugin = SlimefunAccelerator.getInstance();
        for (Map.Entry<ChunkPosition, Map<String, Set<Location>>> entry : locationsByChunk.entrySet()) {
            ChunkPosition chunk = entry.getKey();
            Map<String, Set<Location>> locations = entry.getValue();
            RegionTaskScheduler.execute(plugin, chunk.world(), chunk.chunkX(), chunk.chunkZ(), () -> {
                if (isActiveLifecycle(expectedLifecycleId)) {
                    queueExtraTicksInChunk(group, settings, locations);
                }
            });
        }
    }

    private static void queueExtraTicksInChunk(String group, AcceleratorSettings settings, Map<String, Set<Location>> locationsById) {
        Set<Location> queue = tickLocations.get(group);
        if (queue == null) {
            return;
        }

        for (Map.Entry<String, Set<Location>> entry : locationsById.entrySet()) {
            SlimefunItem slimefunItem = SlimefunItem.getById(entry.getKey());
            if (slimefunItem == null || slimefunItem.isDisabled()) {
                continue;
            }

            for (Location location : entry.getValue()) {
                World world = location.getWorld();
                if (world == null || (!settings.isTickUnload() && !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))) {
                    continue;
                }

                if (slimefunItem.isDisabledIn(world)) {
                    continue;
                }

                Location clone = location.clone();
                clone.setYaw(EXTRA_TICKER_FLAG);
                queue.add(clone);
            }
        }
    }

    public static boolean shutdown() {
        boolean wasEnabled = enabled.getAndSet(false);
        shutdownInternal();
        Accelerates.shutdown();
        return wasEnabled;
    }

    private static void shutdownInternal() {
        lifecycleId.incrementAndGet();
        for (int taskId : new HashSet<>(Accelerates.getTaskIds().values())) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        rollback();
        running.clear();
        tickLocations.clear();
        asyncLocks.clear();
        allTickerLocations.clear();
        extraTickers.clear();
        chunkBucketHint.clear();
        LoadMonitor monitor = loadMonitor;
        if (monitor != null) {
            monitor.reset();
        }
        resetTickProfiler();
        resetLoadMonitor();
    }

    public static void rollback() {
        for (Map.Entry<SlimefunItem, BlockTicker> entry : originalTickers.entrySet()) {
            SlimefunItem slimefunItem = entry.getKey();
            BlockTicker blockTicker = entry.getValue();
            ItemState state = slimefunItem.getState();
            ReflectionUtil.setValue(slimefunItem, SlimefunItem.class, "state", ItemState.UNREGISTERED);
            slimefunItem.addItemHandler(blockTicker);
            ReflectionUtil.setValue(slimefunItem, SlimefunItem.class, "state", state);
        }
        originalTickers.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInit(SlimefunItemRegistryFinalizedEvent event) {
        if (SlimefunAccelerator.getInstance().getConfigManager().isEnabled()) {
            load();
        } else {
            SlimefunAccelerator.getInstance().getLogger().info("SlimefunAccelerator runtime is disabled in config.yml.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (!isRunning() || extraTickers.isEmpty()) {
            return;
        }

        String id;
        if (isCNSlimefun()) {
            SlimefunBlockData config = StorageCacheUtils.getBlock(event.getBlock().getLocation());
            if (config == null) {
                return;
            }
            id = config.getSfId();
        } else {
            Config config = BlockStorage.getLocationInfo(event.getBlock().getLocation());
            if (config == null) {
                return;
            }
            id = config.getString("id");
        }

        if (id == null || !extraTickers.contains(id)) {
            return;
        }

        allTickerLocations.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet())
                .add(event.getBlock().getLocation());
    }

    @EventHandler
    public void onBlockPlacerPlace(@NotNull BlockPlacerPlaceEvent event) {
        if (!isRunning() || extraTickers.isEmpty()) {
            return;
        }

        SlimefunItem slimefunItem = SlimefunItem.getByItem(event.getItemStack());
        if (slimefunItem == null || !extraTickers.contains(slimefunItem.getId())) {
            return;
        }

        allTickerLocations.computeIfAbsent(slimefunItem.getId(), k -> ConcurrentHashMap.newKeySet())
                .add(event.getBlockPlacer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (!isRunning()) {
            return;
        }

        Location location = event.getBlock().getLocation();

        if (isCNSlimefun()) {
            SlimefunBlockData config = StorageCacheUtils.getBlock(location);
            if (config != null) {
                Set<Location> locations = allTickerLocations.get(config.getSfId());
                if (locations != null) {
                    locations.remove(location);
                }
                return;
            }
        } else {
            Config config = BlockStorage.getLocationInfo(location);
            if (config != null) {
                Set<Location> locations = allTickerLocations.get(config.getString("id"));
                if (locations != null) {
                    locations.remove(location);
                }
                return;
            }
        }

        for (Set<Location> locations : allTickerLocations.values()) {
            locations.remove(location);
        }
    }
}
