package com.balugaq.slimefunaccelerator.core.managers;

import com.balugaq.slimefunaccelerator.api.annotations.Since;
import com.balugaq.slimefunaccelerator.api.enums.BuildStation;
import com.balugaq.slimefunaccelerator.api.enums.ConfigVersion;
import lombok.Getter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

@Getter
public class ConfigManager {
    private static final @NotNull String CONFIG_PATH = "config.yml";
    private static final @NotNull String BANS_PATH = "accelerates.yml";
    private final @NotNull FileConfiguration config;
    private final @NotNull FileConfiguration bans;
    private final boolean ENABLED;
    @Since(ConfigVersion.C_20250223_1)
    private final boolean AUTO_UPDATE;
    @Since(ConfigVersion.C_20250223_1)
    private final boolean DEBUG;
    @Since(ConfigVersion.C_20250223_1)
    private final @NotNull String LANGUAGE;
    private final @NotNull JavaPlugin plugin;
    @Since(ConfigVersion.C_20250223_1)
    private BuildStation BUILD_STATION;
    @Since(ConfigVersion.C_20250223_1)
    private ConfigVersion CONFIG_VERSION;

    // Circuit breaker (per-item, since C_20260517_1).
    private final boolean CIRCUIT_BREAKER_ENABLED;
    private final long CIRCUIT_BREAKER_MAX_MICROS;
    private final int CIRCUIT_BREAKER_OVERRUN_THRESHOLD;
    private final long CIRCUIT_BREAKER_COOLDOWN_TICKS;
    private final int CIRCUIT_BREAKER_RECOVERY_SAMPLES;
    private final double CIRCUIT_BREAKER_EMA_ALPHA;
    // Trip action / throttle (added in C_20260517_2).
    private final @NotNull String CIRCUIT_BREAKER_ACTION;
    private final int CIRCUIT_BREAKER_THROTTLE_DIVISOR;

    // Load-aware throttling (server TPS, since C_20260517_1).
    private final boolean LOAD_AWARE_ENABLED;
    private final double LOAD_AWARE_THROTTLE_TPS;
    private final double LOAD_AWARE_SKIP_TPS;

    // Per-group round timeout (since C_20260517_1).
    private final int GROUP_TIMEOUT_MULTIPLIER;

    // Native compatibility bypass. Items from these addons are left on the
    // original Slimefun ticker instead of being wrapped by the accelerator.
    private final @NotNull Set<String> COMPATIBILITY_NATIVE_ADDONS;
    private final @NotNull Set<String> COMPATIBILITY_NATIVE_ITEMS;
    private final boolean COMPATIBILITY_NATIVE_THROTTLE_ENABLED;
    private final int COMPATIBILITY_NATIVE_THROTTLE_DIVISOR;
    private final @NotNull Set<String> COMPATIBILITY_NATIVE_THROTTLE_ADDONS;
    private final @NotNull Set<String> COMPATIBILITY_NATIVE_THROTTLE_ITEMS;

    public ConfigManager(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), CONFIG_PATH));
        this.bans = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), BANS_PATH));
        setupDefaultConfig();
        try {
            this.CONFIG_VERSION = ConfigVersion.valueOf(plugin.getConfig().getString("config-version", "UNKNOWN").toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid config-version value: " + plugin.getConfig().getString("config-version", "UNKNOWN") + ", using default value: UNKNOWN");
            this.CONFIG_VERSION = ConfigVersion.C_UNKNOWN;
        }
        this.ENABLED = config.getBoolean("enabled", true);
        this.AUTO_UPDATE = config.getBoolean("auto-update", false);
        this.DEBUG = config.getBoolean("debug", false);
        String buildStationStr = config.getString("build-station", "Guizhan");
        try {
            this.BUILD_STATION = BuildStation.valueOf(buildStationStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid build-station value: " + buildStationStr + ", using default value: Guizhan");
            this.BUILD_STATION = BuildStation.GUIZHAN;
        }
        this.LANGUAGE = config.getString("language", "zh-CN");

        this.CIRCUIT_BREAKER_ENABLED = config.getBoolean("circuit-breaker.enabled", true);
        this.CIRCUIT_BREAKER_MAX_MICROS = config.getLong("circuit-breaker.max-tick-time-micros", 10000L);
        this.CIRCUIT_BREAKER_OVERRUN_THRESHOLD = config.getInt("circuit-breaker.overrun-threshold", 20);
        this.CIRCUIT_BREAKER_COOLDOWN_TICKS = config.getLong("circuit-breaker.cooldown-ticks", 600L);
        this.CIRCUIT_BREAKER_RECOVERY_SAMPLES = config.getInt("circuit-breaker.recovery-samples", 200);
        this.CIRCUIT_BREAKER_EMA_ALPHA = config.getDouble("circuit-breaker.ema-alpha", 0.1);
        this.CIRCUIT_BREAKER_ACTION = config.getString("circuit-breaker.action", "throttle");
        this.CIRCUIT_BREAKER_THROTTLE_DIVISOR = Math.max(2,
                config.getInt("circuit-breaker.throttle-divisor", 4));

        this.LOAD_AWARE_ENABLED = config.getBoolean("load-aware.enabled", true);
        this.LOAD_AWARE_THROTTLE_TPS = config.getDouble("load-aware.throttle-tps", 18.0);
        this.LOAD_AWARE_SKIP_TPS = config.getDouble("load-aware.skip-tps", 15.0);

        this.GROUP_TIMEOUT_MULTIPLIER = Math.max(2, config.getInt("group-timeout-multiplier", 5));

        this.COMPATIBILITY_NATIVE_ADDONS = loadUppercaseSet(
                config,
                "compatibility.native-addons",
                List.of("MomoTech", "MomoTechvOptimized", "FinalTECH", "FinalTECH-Changed"));
        this.COMPATIBILITY_NATIVE_ITEMS = loadUppercaseSet(
                config,
                "compatibility.native-items",
                List.of());
        this.COMPATIBILITY_NATIVE_THROTTLE_ENABLED =
                config.getBoolean("compatibility.native-throttle.enabled", false);
        this.COMPATIBILITY_NATIVE_THROTTLE_DIVISOR =
                Math.max(2, config.getInt("compatibility.native-throttle.divisor", 2));
        this.COMPATIBILITY_NATIVE_THROTTLE_ADDONS = loadUppercaseSet(
                config,
                "compatibility.native-throttle.addons",
                List.of("MomoTech", "MomoTechvOptimized", "FinalTECH", "FinalTECH-Changed"));
        this.COMPATIBILITY_NATIVE_THROTTLE_ITEMS = loadUppercaseSet(
                config,
                "compatibility.native-throttle.items",
                List.of());
    }

    private static @NotNull Set<String> loadUppercaseSet(
            @NotNull FileConfiguration config,
            @NotNull String path,
            @NotNull List<String> defaults) {
        List<String> values = config.isList(path) ? config.getStringList(path) : defaults;
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim().toUpperCase(Locale.ROOT));
        }

        return normalized;
    }

    private void setupDefaultConfig() {
        // config.yml
        final InputStream inputStream = plugin.getResource(CONFIG_PATH);
        final File existingFile = new File(plugin.getDataFolder(), CONFIG_PATH);

        if (inputStream == null) {
            return;
        }

        final Reader reader = new InputStreamReader(inputStream);
        final FileConfiguration resourceConfig = YamlConfiguration.loadConfiguration(reader);

        if (!existingFile.exists()) {
            try {
                plugin.saveResource(CONFIG_PATH, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.SEVERE, "The default config file {0} does not exist in jar file!", "config.yml");
                return;
            }
        } else {
            final FileConfiguration existingConfig = YamlConfiguration.loadConfiguration(existingFile);

            for (String key : resourceConfig.getKeys(false)) {
                checkKey(existingConfig, resourceConfig, key);
            }

            try {
                existingConfig.save(existingFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        final InputStream bansInputStream = plugin.getResource(BANS_PATH);
        if (bansInputStream == null) {
            return;
        }

        final File bansFile = new File(plugin.getDataFolder(), BANS_PATH);
        if (!bansFile.exists()) {
            try {
                this.plugin.saveResource(BANS_PATH, false);
            } catch (IllegalArgumentException var6) {
                this.plugin.getLogger().log(Level.SEVERE, "The default bans file {0} does not exist in jar file!", BANS_PATH);
                return;
            }
        }
    }

    @ParametersAreNonnullByDefault
    private void checkKey(FileConfiguration existingConfig, FileConfiguration resourceConfig, String key) {
        final Object currentValue = existingConfig.get(key);
        final Object newValue = resourceConfig.get(key);
        if (newValue instanceof ConfigurationSection section) {
            for (String sectionKey : section.getKeys(false)) {
                checkKey(existingConfig, resourceConfig, key + "." + sectionKey);
            }
        } else if (currentValue == null) {
            existingConfig.set(key, newValue);
        }
    }

    public boolean isAutoUpdate() {
        return AUTO_UPDATE;
    }

    public boolean isEnabled() {
        return ENABLED;
    }

    public boolean isDebug() {
        return DEBUG;
    }

    public @NotNull String getLanguage() {
        return LANGUAGE;
    }

    public BuildStation getBuildStation() {
        return BUILD_STATION;
    }

    public ConfigVersion getConfigVersion() {
        return CONFIG_VERSION;
    }

    public boolean isCircuitBreakerEnabled() { return CIRCUIT_BREAKER_ENABLED; }
    public long getCircuitBreakerMaxMicros() { return CIRCUIT_BREAKER_MAX_MICROS; }
    public int getCircuitBreakerOverrunThreshold() { return CIRCUIT_BREAKER_OVERRUN_THRESHOLD; }
    public long getCircuitBreakerCooldownTicks() { return CIRCUIT_BREAKER_COOLDOWN_TICKS; }
    public int getCircuitBreakerRecoverySamples() { return CIRCUIT_BREAKER_RECOVERY_SAMPLES; }
    public double getCircuitBreakerEmaAlpha() { return CIRCUIT_BREAKER_EMA_ALPHA; }
    public @NotNull String getCircuitBreakerAction() { return CIRCUIT_BREAKER_ACTION; }
    public int getCircuitBreakerThrottleDivisor() { return CIRCUIT_BREAKER_THROTTLE_DIVISOR; }

    public boolean isLoadAwareEnabled() { return LOAD_AWARE_ENABLED; }
    public double getLoadAwareThrottleTps() { return LOAD_AWARE_THROTTLE_TPS; }
    public double getLoadAwareSkipTps() { return LOAD_AWARE_SKIP_TPS; }

    public int getGroupTimeoutMultiplier() { return GROUP_TIMEOUT_MULTIPLIER; }

    public boolean isNativeAddonBypassed(@NotNull String addonName) {
        return COMPATIBILITY_NATIVE_ADDONS.contains(addonName.trim().toUpperCase(Locale.ROOT));
    }

    public boolean isNativeItemBypassed(@NotNull String itemId) {
        return COMPATIBILITY_NATIVE_ITEMS.contains(itemId.trim().toUpperCase(Locale.ROOT));
    }

    public boolean isNativeThrottleEnabled() {
        return COMPATIBILITY_NATIVE_THROTTLE_ENABLED;
    }

    public int getNativeThrottleDivisor() {
        return COMPATIBILITY_NATIVE_THROTTLE_DIVISOR;
    }

    public boolean isNativeThrottleAddon(@NotNull String addonName) {
        return COMPATIBILITY_NATIVE_THROTTLE_ADDONS.contains(addonName.trim().toUpperCase(Locale.ROOT));
    }

    public boolean isNativeThrottleItem(@NotNull String itemId) {
        return COMPATIBILITY_NATIVE_THROTTLE_ITEMS.contains(itemId.trim().toUpperCase(Locale.ROOT));
    }
}
