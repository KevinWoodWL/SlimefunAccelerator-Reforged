package com.balugaq.slimefunaccelerator.core.listeners;

import com.balugaq.slimefunaccelerator.api.AcceleratorSettings;
import com.balugaq.slimefunaccelerator.api.utils.Accelerates;
import com.balugaq.slimefunaccelerator.api.utils.ReflectionUtil;
import com.balugaq.slimefunaccelerator.core.managers.AcceleratesLoader;
import com.balugaq.slimefunaccelerator.core.services.RegionTaskScheduler;
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
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@SuppressWarnings("deprecation")
public class Accelerator implements Listener {
    public static final int EXTRA_TICKER_FLAG = 0b00000001;
    public static final Map<String, Set<Location>> allTickerLocations = new ConcurrentHashMap<>(16);
    public static final Map<SlimefunItem, BlockTicker> originalTickers = new ConcurrentHashMap<>(16);
    public static final boolean isCNSlimefun = SlimefunAccelerator.getInstance().getIntegrationManager().isCNSlimefun();
    public static final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>(16);
    public static final Map<String, Set<Location>> tickLocations = new ConcurrentHashMap<>(16);
    public static final Set<String> extraTickers = new HashSet<>(16);
    public static final BiConsumer<String, Set<SlimefunItem>> onAccelerate = Accelerator::accelerate;

    private static void accelerate(String group, Set<SlimefunItem> items) {
        AtomicBoolean groupRunning = running.get(group);
        if (groupRunning == null || !groupRunning.compareAndSet(false, true)) {
            return;
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

                blockTicker.uniqueTick();
            }

            AcceleratorSettings settings = Accelerates.getAccelerateSettings().get(group);
            if (settings == null) {
                groupRunning.set(false);
                return;
            }

            Set<Location> groupSet = tickLocations.get(group);
            if (groupSet == null || groupSet.isEmpty()) {
                groupRunning.set(false);
                return;
            }

            Set<Location> queue;
            synchronized (groupSet) {
                queue = new HashSet<>(groupSet);
                groupSet.removeAll(queue);
            }

            Map<ChunkPosition, Set<Location>> locationsByChunk = new HashMap<>();
            for (Location location : queue) {
                if (location == null || location.getWorld() == null) {
                    continue;
                }

                locationsByChunk
                        .computeIfAbsent(ChunkPosition.from(location), ignored -> new HashSet<>())
                        .add(location);
            }

            if (locationsByChunk.isEmpty()) {
                groupRunning.set(false);
                return;
            }

            SlimefunAccelerator plugin = SlimefunAccelerator.getInstance();
            AtomicInteger pendingTasks = new AtomicInteger(locationsByChunk.size());
            for (Map.Entry<ChunkPosition, Set<Location>> entry : locationsByChunk.entrySet()) {
                ChunkPosition chunk = entry.getKey();
                Set<Location> locations = entry.getValue();
                RegionTaskScheduler.execute(plugin, chunk.world(), chunk.chunkX(), chunk.chunkZ(), () -> {
                    try {
                        tickChunk(settings, locations);
                    } finally {
                        if (pendingTasks.decrementAndGet() == 0) {
                            groupRunning.set(false);
                        }
                    }
                });
            }
        } catch (RuntimeException exception) {
            groupRunning.set(false);
            throw exception;
        }
    }

    private static void tickChunk(AcceleratorSettings settings, Set<Location> locations) {
        for (Location location : locations) {
            tickLocation(settings, location);
        }
    }

    private static void tickLocation(AcceleratorSettings settings, Location location) {
        World world = location.getWorld();
        if (world == null || (!settings.isTickUnload() && !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))) {
            return;
        }

        SlimefunItem item = isCNSlimefun ? StorageCacheUtils.getSfItem(location) : BlockStorage.check(location);
        if (item == null || item.isDisabledIn(world)) {
            return;
        }

        BlockTicker ticker = Accelerates.getTickers().get(item.getId());
        if (ticker == null) {
            return;
        }

        if (isCNSlimefun) {
            SlimefunBlockData config = StorageCacheUtils.getBlock(location);
            if (config == null) {
                removeExtraTickerLocation(item.getId(), location);
                return;
            }

            ticker.tick(location.getBlock(), item, config);
        } else {
            Config config = BlockStorage.getLocationInfo(location);
            if (config == null) {
                removeExtraTickerLocation(item.getId(), location);
                return;
            }

            ticker.tick(location.getBlock(), item, config);
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
        synchronized (extra) {
            extra.remove(storedLocation);
        }
    }

    private record ChunkPosition(World world, int chunkX, int chunkZ) {
        private static ChunkPosition from(Location location) {
            return new ChunkPosition(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
    }

    public static void load() {
        SlimefunAccelerator.getInstance().getLogger().info("Loading accelerates...");
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
            }

            for (SlimefunItem slimefunItem : items) {
                BlockTicker blockTicker = slimefunItem.getBlockTicker();
                if (blockTicker == null) {
                    continue;
                }
                originalTickers.put(slimefunItem, blockTicker);
                ItemState state = slimefunItem.getState();
                ReflectionUtil.setValue(slimefunItem, SlimefunItem.class, "state", ItemState.UNREGISTERED);

                if (isCNSlimefun) {
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
                                synchronized (queue) {
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
                                synchronized (queue) {
                                    queue.add(location);
                                }
                            }
                        });
                    }
                }

                ReflectionUtil.setValue(slimefunItem, SlimefunItem.class, "state", state);
            }

            int taskId = Bukkit.getScheduler().runTaskTimer(SlimefunAccelerator.getInstance(),
                    () -> onAccelerate.accept(group, items),
                    settings.getDelay(),
                    settings.getPeriod()
            ).getTaskId();
            Accelerates.getTaskIds().put(group, taskId);
        }

        if (isCNSlimefun) {
            for (Map.Entry<String, Set<Location>> entry : ExtraTickerCNVersion.getAllTickLocations().entrySet()) {
                Set<Location> locations = allTickerLocations.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet());
                synchronized (locations) {
                    locations.addAll(entry.getValue());
                }
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
            int taskId = Bukkit.getScheduler().runTaskTimer(SlimefunAccelerator.getInstance(),
                    () -> queueExtraTicks(group, settings, ids),
                    settings.getExtraTickerDelay(),
                    settings.getExtraTickerPeriod()
            ).getTaskId();
            Accelerates.getTaskIds().put(group + ":extra-ticker", taskId);
        }
    }

    private static void queueExtraTicks(String group, AcceleratorSettings settings, Set<String> ids) {
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

            Set<Location> snapshot;
            synchronized (locations) {
                snapshot = new HashSet<>(locations);
            }

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
            RegionTaskScheduler.execute(plugin, chunk.world(), chunk.chunkX(), chunk.chunkZ(), () -> queueExtraTicksInChunk(group, settings, locations));
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
                synchronized (queue) {
                    queue.add(clone);
                }
            }
        }
    }

    public static void shutdown() {
        for (int taskId : Accelerates.getTaskIds().values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        rollback();
        originalTickers.clear();
        running.clear();
        tickLocations.clear();
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
        load();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (isCNSlimefun) {
            SlimefunBlockData config = StorageCacheUtils.getBlock(event.getBlock().getLocation());
            if (config == null) {
                return;
            }

            Set<Location> locations = allTickerLocations.computeIfAbsent(config.getSfId(), k -> ConcurrentHashMap.newKeySet());
            synchronized (locations) {
                locations.add(event.getBlock().getLocation());
            }
        } else {
            Config config = BlockStorage.getLocationInfo(event.getBlock().getLocation());
            if (config == null) {
                return;
            }

            Set<Location> locations = allTickerLocations.computeIfAbsent(config.getString("id"), k -> ConcurrentHashMap.newKeySet());
            synchronized (locations) {
                locations.add(event.getBlock().getLocation());
            }
        }
    }

    @EventHandler
    public void onBlockPlacerPlace(@NotNull BlockPlacerPlaceEvent event) {
        SlimefunItem slimefunItem = SlimefunItem.getByItem(event.getItemStack());
        if (slimefunItem != null) {
            Set<Location> locations = allTickerLocations.computeIfAbsent(slimefunItem.getId(), k -> ConcurrentHashMap.newKeySet());
            synchronized (locations) {
                locations.add(event.getBlockPlacer().getLocation());
            }
        }
    }
}
