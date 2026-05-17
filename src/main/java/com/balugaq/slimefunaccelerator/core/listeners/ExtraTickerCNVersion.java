package com.balugaq.slimefunaccelerator.core.listeners;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.event.SlimefunChunkDataLoadEvent;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class ExtraTickerCNVersion implements Listener {
    @Getter
    private static final Map<String, Set<Location>> allTickLocations = new ConcurrentHashMap<>();

    @EventHandler
    public void onSlimefunChunkDataLoad(@NotNull SlimefunChunkDataLoadEvent event) {
        for (SlimefunBlockData blockData : event.getChunkData().getAllBlockData()) {
            String id = blockData.getSfId();
            Location location = blockData.getLocation();

            // Pre-init cold cache: every Slimefun block we see. loadInternal
            // copies this into Accelerator.allTickerLocations on startup.
            allTickLocations.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(location);

            // Post-init live update: forward straight to the accelerator so
            // chunks loading AFTER Slimefun init still get tracked. Without
            // this, players on extra-ticker groups have to re-place machines
            // that were on chunks loaded after server start.
            if (Accelerator.isRunning() && Accelerator.extraTickers.contains(id)) {
                Accelerator.allTickerLocations
                        .computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet())
                        .add(location);
            }
        }
    }
}
