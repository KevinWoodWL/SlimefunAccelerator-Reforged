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
            allTickLocations.computeIfAbsent(blockData.getSfId(), k -> ConcurrentHashMap.newKeySet()).add(blockData.getLocation());
        }
    }
}
