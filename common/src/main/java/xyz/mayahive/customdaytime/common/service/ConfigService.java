/*
 *     Copyright (c) 2026 Seedim
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xyz.mayahive.customdaytime.common.service;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import xyz.mayahive.customdaytime.api.platform.Platform;
import xyz.mayahive.customdaytime.api.platform.PlatformLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ConfigService {

    /** Current config schema. Bump when adding keys that need a migration. */
    private static final int SCHEMA_VERSION = 1;

    private final Platform platform;
    private final PlatformLogger logger;
    private final CommentedConfigurationNode rootNode;

    final HoconConfigurationLoader loader;

    public ConfigService(Platform platform) {
        this.platform = platform;
        this.logger = platform.logger();

        this.loader = HoconConfigurationLoader.builder()
                .path(platform.configDirectory().resolve("config.conf"))
                .build();

        saveDefaultConfig();
        rootNode = loadConfig();
        migrateIfNeeded();
    }

    /**
     * Bring an older config up to the current schema in place. Idempotent: guarded by
     * {@code schemaVersion}, so a config already at the current version is left untouched and the
     * backup is never re-taken. Existing user values are preserved; only missing keys are added.
     */
    private void migrateIfNeeded() {
        int version = rootNode.node("schemaVersion").getInt(0);
        if (version >= SCHEMA_VERSION) return;

        Path configPath = platform.configDirectory().resolve("config.conf");
        Path backupPath = platform.configDirectory().resolve("config_prev.conf");
        try {
            if (Files.exists(configPath) && !Files.exists(backupPath)) {
                Files.copy(configPath, backupPath);
                logger.info("Config migration: backed up existing config to config_prev.conf");
            } else if (Files.exists(backupPath)) {
                logger.info("Config migration: config_prev.conf already exists; leaving it untouched");
            }
        } catch (IOException e) {
            logger.error("Config migration: failed to back up config: " + e.getMessage());
        }

        List<String> summary = new ArrayList<>();
        for (Object worldKey : new ArrayList<>(rootNode.childrenMap().keySet())) {
            if (!worldKey.toString().contains(":")) continue; // only "namespace:value" world blocks
            List<String> added = addMissingCorrections(rootNode.node(worldKey));
            if (!added.isEmpty()) summary.add(worldKey + "=" + added);
        }

        try {
            rootNode.node("schemaVersion")
                    .comment("Config schema version. Managed by the plugin -- do not edit.")
                    .set(SCHEMA_VERSION);
        } catch (SerializationException e) {
            logger.error("Config migration: failed to write schemaVersion: " + e.getMessage());
        }

        saveConfig();
        logger.info("Config migrated to schema v" + SCHEMA_VERSION + ". Added correction toggles: "
                + (summary.isEmpty() ? "none (already present)" : summary));
    }

    private List<String> addMissingCorrections(CommentedConfigurationNode worldNode) {
        CommentedConfigurationNode corrections = worldNode.node("corrections");
        corrections.comment("Fixes for vanilla mechanics that break when the day/night cycle is stretched.\n"
                + "Each toggle is per-world.");

        List<String> added = new ArrayList<>();
        addToggle(corrections, "villagerSleep", true, added,
                "Keep villagers eligible to spawn iron golems on long days. Without it, a stretched\n"
                        + "night ages the villager's last-slept timer past Minecraft's one-day golem window, so\n"
                        + "natural iron golems stop spawning. Default: true");
        addToggle(corrections, "villagerStock", true, added,
                "Keep working villagers' trades restocking through a long day. Without it, vanilla's\n"
                        + "twice-per-day cap resets only on the day boundary, so trades sit depleted. Only\n"
                        + "restocks employed villagers that reach their workstation. Default: true");
        addToggle(corrections, "phantomSpawn", true, added,
                "Keep phantom insomnia tied to nights survived, not real time. Without it, phantoms come\n"
                        + "after ~1 stretched night instead of 3. NOTE: this rewrites the TIME_SINCE_REST\n"
                        + "statistic, so its value in the in-game statistics screen becomes inaccurate.\n"
                        + "Default: true");
        addToggle(corrections, "pillagerSpawn", false, added,
                "Thin pillager patrols to match a stretched day. Off by default: patrols already spawn at\n"
                        + "the vanilla real-time rate, and enabling spreads them out, making the server easier.\n"
                        + "Default: false");
        addToggle(corrections, "weatherTimer", false, added,
                "Stretch rain/thunder/clear periods to match a stretched day. Off by default: weather\n"
                        + "already runs in real time; enabling is a thematic pacing change. Default: false");
        addToggle(corrections, "wanderingTraderSpawn", false, added,
                "Startup check only: warns if paper-world-defaults.yml wandering-trader spawn-day-length\n"
                        + "differs from 24000 * cycleScale. Off by default; aligning that Paper value makes traders\n"
                        + "appear once per stretched day instead of once per 20 real minutes (easier). Default: false");
        return added;
    }

    private void addToggle(CommentedConfigurationNode corrections, String key, boolean defaultValue,
                           List<String> added, String comment) {
        CommentedConfigurationNode node = corrections.node(key);
        if (!node.virtual()) return; // already present -> preserve the user's value
        try {
            node.comment(comment).set(defaultValue);
            added.add(key);
        } catch (SerializationException e) {
            logger.error("Config migration: failed to add correction toggle '" + key + "': " + e.getMessage());
        }
    }

    private CommentedConfigurationNode loadConfig() {
        CommentedConfigurationNode root;
        try {
            root = loader.load();
            return root;
        } catch (IOException e) {
            logger.error("An error occurred while loading the configuration: " + e.getMessage());
            if (e.getCause() != null) {
                logger.error(e.getCause().toString());
            }
            logger.error("Falling back to empty configuration. Plugin features may not work correctly until the issue is resolved.");
            return loader.createNode();
        }
    }

    public <T> T getConfigValue(Class<T> type, T defaultValue, Object... path) {
        try {
            return rootNode.node(path).get(type, defaultValue);
        } catch (SerializationException e) {
            logger.error("An error occurred while loading configuration value: " + e.getMessage());
            if (e.getCause() != null) {
                logger.error(e.getCause().toString());
            }
        }
        return defaultValue;
    }

    public void setConfigValue(Object value, Object... path) {
        try {
            ConfigurationNode configNode = rootNode.node(path);
            configNode.set(value.getClass(), value);
            saveConfig();
        } catch (SerializationException e) {
            logger.error("An error occurred while loading configuration value: " + e.getMessage());
        }
    }

    public void saveConfig() {
        try {
            loader.save(rootNode);
        } catch (final ConfigurateException e) {
            logger.error("An error occurred while saving the configuration: " + e.getMessage());
            if (e.getCause() != null) {
                logger.error(e.getCause().toString());
            }
        }
    }

    public void saveDefaultConfig() {
        Path targetPath = platform.configDirectory().resolve("config.conf");

        if (Files.exists(targetPath)) {return;}

        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.conf")) {

            Files.createDirectories(targetPath.getParent());

            if (inputStream != null) {
                Files.copy(inputStream, targetPath);
            }

        } catch (IOException e) {
            logger.error("An error occurred while saving the default configuration: " + e.getMessage());
            if (e.getCause() != null) {
                logger.error(e.getCause().toString());
            }
        }
    }

    public Set<String> getRootKeys() {
        return rootNode.childrenMap().keySet().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

}
