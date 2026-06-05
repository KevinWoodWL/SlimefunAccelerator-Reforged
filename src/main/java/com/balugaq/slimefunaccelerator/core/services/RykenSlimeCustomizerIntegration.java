package com.balugaq.slimefunaccelerator.core.services;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RykenSlimeCustomizerIntegration {
    public static final String PLUGIN_NAME = "RykenSlimefunCustomizer";
    private static final String ADDON_MANAGER_FIELD = "addonManager";
    private static final List<String> PROJECT_ITEM_GETTERS = List.of(
            "getItems",
            "getMachines",
            "getGenerators",
            "getMaterialGenerators",
            "getRecipeMachines",
            "getMultiBlockMachines",
            "getSolarGenerators",
            "getMobDrops",
            "getCapacitors",
            "getSimpleMachines",
            "getFoods",
            "getArmors",
            "getSupers",
            "getTemplateMachines",
            "getLinkedRecipeMachines",
            "getWorkbenches",
            "getGeoResources");

    private RykenSlimeCustomizerIntegration() {
    }

    public static @NotNull ScanResult scanProjects(@NotNull Collection<String> projectIds) {
        Set<String> wanted = normalize(projectIds);
        if (wanted.isEmpty()) {
            return ScanResult.empty(true);
        }

        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) {
            return ScanResult.unavailable(wanted);
        }

        try {
            Field addonManagerField = plugin.getClass().getField(ADDON_MANAGER_FIELD);
            Object addonManager = addonManagerField.get(null);
            if (addonManager == null) {
                return ScanResult.failed(wanted, "addonManager is null");
            }

            Method getAllAddons = addonManager.getClass().getMethod("getAllAddons");
            Object allAddons = getAllAddons.invoke(addonManager);
            if (!(allAddons instanceof Iterable<?> projects)) {
                return ScanResult.failed(wanted, "getAllAddons() did not return an Iterable");
            }

            Map<String, ProjectItems> matchedProjects = new LinkedHashMap<>();
            Set<String> seenProjects = new LinkedHashSet<>();
            for (Object project : projects) {
                if (project == null) {
                    continue;
                }

                String projectId = invokeString(project, "getAddonId");
                if (projectId == null) {
                    continue;
                }

                String normalizedProjectId = normalize(projectId);
                seenProjects.add(normalizedProjectId);
                if (!wanted.contains(normalizedProjectId)) {
                    continue;
                }

                String projectName = invokeString(project, "getAddonName");
                Set<SlimefunItem> items = collectTickerItems(project);
                matchedProjects.put(normalizedProjectId, new ProjectItems(projectId, projectName, items));
            }

            Set<String> missingProjects = new LinkedHashSet<>(wanted);
            missingProjects.removeAll(seenProjects);
            return new ScanResult(true, false, null, matchedProjects, missingProjects);
        } catch (IllegalAccessException | NoSuchFieldException | NoSuchMethodException | InvocationTargetException
                | LinkageError exception) {
            return ScanResult.failed(wanted, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static @NotNull Set<SlimefunItem> collectTickerItems(@NotNull Object project) {
        Set<SlimefunItem> items = new LinkedHashSet<>();
        for (String getterName : PROJECT_ITEM_GETTERS) {
            Object value = invokeNoArg(project, getterName);
            collectTickerItems(value, items);
        }

        return items;
    }

    private static void collectTickerItems(Object value, @NotNull Set<SlimefunItem> items) {
        if (value == null) {
            return;
        }

        if (value instanceof SlimefunItem slimefunItem) {
            BlockTicker ticker = slimefunItem.getBlockTicker();
            if (ticker != null) {
                items.add(slimefunItem);
            }
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collectTickerItems(element, items);
            }
            return;
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectTickerItems(Array.get(value, i), items);
            }
        }
    }

    private static Object invokeNoArg(@NotNull Object target, @NotNull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException | LinkageError ignored) {
            return null;
        }
    }

    private static String invokeString(@NotNull Object target, @NotNull String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value instanceof String string ? string : null;
    }

    private static @NotNull Set<String> normalize(@NotNull Collection<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(normalize(value));
        }
        return normalized;
    }

    private static @NotNull String normalize(@NotNull String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public record ProjectItems(
            @NotNull String id,
            String name,
            @NotNull Set<SlimefunItem> items) {
    }

    public record ScanResult(
            boolean available,
            boolean failed,
            String failureMessage,
            @NotNull Map<String, ProjectItems> matchedProjects,
            @NotNull Set<String> missingProjects) {
        private static @NotNull ScanResult empty(boolean available) {
            return new ScanResult(available, false, null, Collections.emptyMap(), Collections.emptySet());
        }

        private static @NotNull ScanResult unavailable(@NotNull Set<String> missingProjects) {
            return new ScanResult(false, false, null, Collections.emptyMap(), missingProjects);
        }

        private static @NotNull ScanResult failed(@NotNull Set<String> missingProjects, String failureMessage) {
            return new ScanResult(true, true, failureMessage, Collections.emptyMap(), missingProjects);
        }
    }
}
