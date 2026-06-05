package com.balugaq.slimefunaccelerator.core.managers;

import com.balugaq.slimefunaccelerator.api.utils.Accelerates;
import com.balugaq.slimefunaccelerator.api.utils.Debug;
import com.balugaq.slimefunaccelerator.api.utils.Lang;
import com.balugaq.slimefunaccelerator.core.services.RykenSlimeCustomizerIntegration;
import com.balugaq.slimefunaccelerator.implementation.SlimefunAccelerator;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import lombok.experimental.UtilityClass;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@UtilityClass
public class AcceleratesLoader {
    public static final String ACCELERATES_KEY = "accelerates";
    public static final String ENABLED_KEY = "enabled";
    public static final String ASYNC_KEY = "async";
    public static final String DELAY_KEY = "delay";
    public static final String PERIOD_KEY = "period";
    public static final String ADDONS_KEY = "addons";
    public static final String RSC_PROJECTS_KEY = "rsc-projects";
    public static final String ITEMS_KEY = "items";
    public static final String EXCLUDE_KEY = "excludes";
    public static final String REMOVE_ORIGINAL_TICKER_KEY = "remove-original-ticker";
    public static final String EXTRA_TICKER_ENABLED_KEY = "extra-ticker.enabled";
    public static final String TICK_UNLOAD_KEY = "extra-ticker.tick-unload";
    public static final String EXTRA_TICKER_DELAY_KEY = "extra-ticker.delay";
    public static final String EXTRA_TICKER_PERIOD_KEY = "extra-ticker.period";
    public static final String EXAMPLE_ITEM = "__EXAMPLE_ITEM";
    public static final String EXAMPLE_ADDON = "__ExampleAddon";

    public static void loadAccelerates() {
        boolean configured = true;
        boolean configuredDifferentItem = false;
        Map<NativeBypassKey, Integer> nativeBypassCounts = new HashMap<>();
        FileConfiguration configuration = SlimefunAccelerator.getInstance().getConfigManager().getBans();
        Debug.debug("Loading accelerates");
        for (String key : configuration.getKeys(false)) {
            Debug.debug("Key: " + key);
        }
        ConfigurationSection accelerates = configuration.getConfigurationSection(ACCELERATES_KEY);
        if (accelerates == null) {
            return;
        }
        ConfigManager configManager = SlimefunAccelerator.getInstance().getConfigManager();

        for (String threadKey : accelerates.getKeys(false)) {
            ConfigurationSection groupSection = accelerates.getConfigurationSection(threadKey);
            if (groupSection == null) {
                continue;
            }

            boolean enabled = groupSection.getBoolean(ENABLED_KEY, true);
            boolean async = groupSection.getBoolean(ASYNC_KEY, false);
            int period = groupSection.getInt(PERIOD_KEY, 10);
            int delay = groupSection.getInt(DELAY_KEY, 10);
            boolean removeOriginalTicker = groupSection.getBoolean(REMOVE_ORIGINAL_TICKER_KEY, false);
            boolean extraTickerEnabled = groupSection.getBoolean(EXTRA_TICKER_ENABLED_KEY, false);
            boolean tickUnload = groupSection.getBoolean(TICK_UNLOAD_KEY, false);
            int extraTickerDelay = groupSection.getInt(EXTRA_TICKER_DELAY_KEY, 10);
            int extraTickerPeriod = groupSection.getInt(EXTRA_TICKER_PERIOD_KEY, 10);
            if (!enabled) {
                continue;
            }

            List<String> rscProjects = groupSection.getStringList(RSC_PROJECTS_KEY);
            boolean effectiveAsync = async;
            if (!rscProjects.isEmpty() && async) {
                effectiveAsync = false;
                SlimefunAccelerator.getInstance().getLogger().warning(Lang.getMessage(
                        "load.rsc-force-sync",
                        "group", threadKey));
            }

            List<String> excludes = groupSection.getStringList(EXCLUDE_KEY);
            excludes.replaceAll(String::toUpperCase);
            List<String> items = groupSection.getStringList(ITEMS_KEY);
            for (String rid : items) {
                String id = rid.toUpperCase();
                if (excludes.contains(id)) {
                    continue;
                }
                SlimefunItem slimefunItem = SlimefunItem.getById(id);
                if (slimefunItem == null) {
                    if (id.equalsIgnoreCase(EXAMPLE_ITEM)) {
                        configured = false;
                        continue;
                    }

                    invalidKey(ACCELERATES_KEY + "." + threadKey + "." + ITEMS_KEY, id);
                    continue;
                }
                if (isNativeBypassed(configManager, slimefunItem)) {
                    recordNativeBypass(nativeBypassCounts, threadKey, slimefunItem);
                    continue;
                }

                BlockTicker ticker = slimefunItem.getBlockTicker();
                if (ticker == null) {
                    invalidKey(ACCELERATES_KEY + "." + threadKey + "." + ITEMS_KEY, id);
                    continue;
                }

                Accelerates.addAccelerate(threadKey, id);
                Accelerates.addAccelerateSettings(threadKey, enabled, effectiveAsync, delay, period, removeOriginalTicker, extraTickerEnabled, tickUnload, extraTickerDelay, extraTickerPeriod);
                Accelerates.getTickers().put(id, ticker);
                SlimefunAccelerator.getInstance().getLogger().info(Lang.getMessage("load.added-accelerates", "id", id));
                configuredDifferentItem = true;
            }
            List<String> addons = groupSection.getStringList(ADDONS_KEY);
            if (addons.size() == 1 && addons.get(0).equalsIgnoreCase(EXAMPLE_ADDON)) {
                configured = false;
                continue;
            }

            for (SlimefunItem slimefunItem : Slimefun.getRegistry().getAllSlimefunItems()) {
                if (excludes.contains(slimefunItem.getId().toUpperCase())) {
                    continue;
                }
                String addonName = getAddonName(slimefunItem);
                if (addonName == null) {
                    continue;
                }
                for (String addon : addons) {
                    if (addonName.equalsIgnoreCase(addon)) {
                        if (isNativeBypassed(configManager, slimefunItem)) {
                            recordNativeBypass(nativeBypassCounts, threadKey, slimefunItem);
                            continue;
                        }
                        BlockTicker ticker = slimefunItem.getBlockTicker();
                        if (ticker == null) {
                            continue;
                        }

                        Accelerates.addAccelerate(threadKey, slimefunItem.getId());
                        Accelerates.addAccelerateSettings(threadKey, enabled, effectiveAsync, delay, period, removeOriginalTicker, extraTickerEnabled, tickUnload, extraTickerDelay, extraTickerPeriod);
                        Accelerates.getTickers().put(slimefunItem.getId(), ticker);
                        SlimefunAccelerator.getInstance().getLogger().info(Lang.getMessage("load.added-accelerates", "id", slimefunItem.getId()));
                        configuredDifferentItem = true;
                    }
                }
            }

            configuredDifferentItem = loadRykenSlimeCustomizerProjects(
                    threadKey,
                    rscProjects,
                    excludes,
                    enabled,
                    effectiveAsync,
                    delay,
                    period,
                    removeOriginalTicker,
                    extraTickerEnabled,
                    tickUnload,
                    extraTickerDelay,
                    extraTickerPeriod,
                    configManager,
                    nativeBypassCounts)
                    || configuredDifferentItem;
        }

        if (!configured && !configuredDifferentItem) {
            SlimefunAccelerator.getInstance().getLogger().warning(Lang.getMessage("load.no-configured-accelerates"));
        }

        for (Map.Entry<NativeBypassKey, Integer> entry : nativeBypassCounts.entrySet()) {
            NativeBypassKey key = entry.getKey();
            SlimefunAccelerator.getInstance().getLogger().info(Lang.getMessage(
                    "load.skipped-native-compat",
                    "count", entry.getValue(),
                    "addon", key.addon(),
                    "group", key.group()));
        }
    }

    private static boolean loadRykenSlimeCustomizerProjects(
            String group,
            List<String> rscProjects,
            List<String> excludes,
            boolean enabled,
            boolean async,
            int delay,
            int period,
            boolean removeOriginalTicker,
            boolean extraTickerEnabled,
            boolean tickUnload,
            int extraTickerDelay,
            int extraTickerPeriod,
            ConfigManager configManager,
            Map<NativeBypassKey, Integer> nativeBypassCounts) {
        if (rscProjects.isEmpty()) {
            return false;
        }

        RykenSlimeCustomizerIntegration.ScanResult scan = RykenSlimeCustomizerIntegration.scanProjects(rscProjects);
        if (!scan.available()) {
            SlimefunAccelerator.getInstance().getLogger().warning(Lang.getMessage(
                    "load.rsc-unavailable",
                    "group", group));
            return false;
        }

        if (scan.failed()) {
            SlimefunAccelerator.getInstance().getLogger().warning(Lang.getMessage(
                    "load.rsc-scan-failed",
                    "group", group,
                    "message", scan.failureMessage()));
            return false;
        }

        for (String missingProject : scan.missingProjects()) {
            invalidKey(ACCELERATES_KEY + "." + group + "." + RSC_PROJECTS_KEY, missingProject);
        }

        boolean configuredDifferentItem = false;
        for (RykenSlimeCustomizerIntegration.ProjectItems project : scan.matchedProjects().values()) {
            int added = 0;
            Set<SlimefunItem> items = project.items();
            for (SlimefunItem slimefunItem : items) {
                if (slimefunItem == null || excludes.contains(slimefunItem.getId().toUpperCase())) {
                    continue;
                }

                if (isNativeBypassed(configManager, slimefunItem)) {
                    recordNativeBypass(nativeBypassCounts, group, slimefunItem);
                    continue;
                }

                BlockTicker ticker = slimefunItem.getBlockTicker();
                if (ticker == null) {
                    continue;
                }

                Accelerates.accelerate(group, slimefunItem);
                Accelerates.addAccelerateSettings(group, enabled, async, delay, period, removeOriginalTicker, extraTickerEnabled, tickUnload, extraTickerDelay, extraTickerPeriod);
                Accelerates.getTickers().put(slimefunItem.getId(), ticker);
                added++;
                configuredDifferentItem = true;
                SlimefunAccelerator.getInstance().getLogger().info(Lang.getMessage("load.added-accelerates", "id", slimefunItem.getId()));
            }

            SlimefunAccelerator.getInstance().getLogger().info(Lang.getMessage(
                    "load.rsc-project-loaded",
                    "project", project.id(),
                    "name", project.name() == null ? project.id() : project.name(),
                    "count", added,
                    "group", group));
        }

        return configuredDifferentItem;
    }

    private static boolean isNativeBypassed(ConfigManager configManager, SlimefunItem slimefunItem) {
        if (configManager.isNativeItemBypassed(slimefunItem.getId())) {
            return true;
        }
        if (configManager.isNativeThrottleEnabled() && configManager.isNativeThrottleItem(slimefunItem.getId())) {
            return true;
        }

        String addonName = getAddonName(slimefunItem);
        if (addonName == null) {
            return false;
        }

        return configManager.isNativeAddonBypassed(addonName)
                || (configManager.isNativeThrottleEnabled() && configManager.isNativeThrottleAddon(addonName));
    }

    private static String getAddonName(SlimefunItem slimefunItem) {
        if (slimefunItem.getAddon() == null || slimefunItem.getAddon().getName() == null) {
            return null;
        }

        return slimefunItem.getAddon().getName();
    }

    private static void recordNativeBypass(
            Map<NativeBypassKey, Integer> nativeBypassCounts,
            String group,
            SlimefunItem slimefunItem) {
        String addon = getAddonName(slimefunItem);
        if (addon == null) {
            addon = "UNKNOWN";
        }
        nativeBypassCounts.merge(new NativeBypassKey(group, addon), 1, Integer::sum);
    }

    private record NativeBypassKey(String group, String addon) {}

    public static void invalidKey(String path, String value) {
        SlimefunAccelerator.getInstance().getLogger().severe(Lang.getMessage("load.invalid-accelerate-key"));
        SlimefunAccelerator.getInstance().getLogger().severe(Lang.getMessage("load.path", "path", path));
        SlimefunAccelerator.getInstance().getLogger().severe(Lang.getMessage("load.value", "value", value));
    }
}
