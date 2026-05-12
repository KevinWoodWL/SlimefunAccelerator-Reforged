package com.balugaq.slimefunaccelerator.core.services;

import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

@UtilityClass
public class RegionTaskScheduler {
    private static final AtomicBoolean loggedFallback = new AtomicBoolean(false);
    private static volatile SchedulerHandle schedulerHandle;
    private static volatile boolean initialized = false;

    public static void execute(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        SchedulerHandle handle = getSchedulerHandle(plugin);
        if (handle == null || world == null) {
            runOnBukkitScheduler(plugin, task);
            return;
        }

        try {
            handle.executeMethod.invoke(handle.regionScheduler, plugin, world, chunkX, chunkZ, task);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            if (loggedFallback.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "Failed to use RegionScheduler, falling back to Bukkit scheduler.", exception);
            }
            runOnBukkitScheduler(plugin, task);
        }
    }

    private static SchedulerHandle getSchedulerHandle(Plugin plugin) {
        if (!initialized) {
            synchronized (RegionTaskScheduler.class) {
                if (!initialized) {
                    schedulerHandle = createSchedulerHandle(plugin);
                    initialized = true;
                }
            }
        }

        return schedulerHandle;
    }

    private static SchedulerHandle createSchedulerHandle(Plugin plugin) {
        try {
            Class<?> regionSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            Method getRegionSchedulerMethod = Bukkit.class.getMethod("getRegionScheduler");
            Object regionScheduler = getRegionSchedulerMethod.invoke(null);
            Method executeMethod = regionSchedulerClass.getMethod(
                    "execute",
                    Plugin.class,
                    World.class,
                    int.class,
                    int.class,
                    Runnable.class
            );
            plugin.getLogger().info("Using Leaf/Paper RegionScheduler for machine ticks.");
            return new SchedulerHandle(regionScheduler, executeMethod);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().info("RegionScheduler is unavailable, using Bukkit scheduler for machine ticks.");
            return null;
        }
    }

    private static void runOnBukkitScheduler(Plugin plugin, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private static final class SchedulerHandle {
        private final Object regionScheduler;
        private final Method executeMethod;

        private SchedulerHandle(Object regionScheduler, Method executeMethod) {
            this.regionScheduler = regionScheduler;
            this.executeMethod = executeMethod;
        }
    }
}
