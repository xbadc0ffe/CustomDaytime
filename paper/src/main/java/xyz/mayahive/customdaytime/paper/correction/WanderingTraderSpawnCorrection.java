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

package xyz.mayahive.customdaytime.paper.correction;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.correction.Correction;
import xyz.mayahive.customdaytime.common.world.WorldTimeScale;

import java.io.File;
import java.util.function.Supplier;

/**
 * WANDERING_TRADER_SPAWN (default off) — a startup drift check, not spawn logic.
 *
 * <p>Wandering trader spawn timing is exposed by Paper as
 * {@code entities.spawning.wandering-trader.spawn-day-length} (default 24000), so aligning it to a
 * stretched day is a config change, not something this plugin should drive. When enabled, this
 * correction only checks that value against {@code 24000 * cycleScale} for the world and logs a
 * warning if they differ, telling the admin what to set. Setting it makes traders appear once per
 * stretched day instead of once per 20 real minutes, i.e. <em>easier</em>, which is why it's opt-in.</p>
 */
public final class WanderingTraderSpawnCorrection implements Correction {

    private static final String SPAWN_DAY_LENGTH_PATH = "entities.spawning.wandering-trader.spawn-day-length";
    private static final long VANILLA_TRADER_SPAWN_DAY_LENGTH = 24000L;

    private final Plugin plugin;

    public WanderingTraderSpawnCorrection(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String key() {
        return "wanderingTraderSpawn";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public void enable(WorldKey world, Supplier<WorldTimeScale> scale) {
        WorldTimeScale timeScale = scale.get();
        if (timeScale == null) return;

        double cycleScale = timeScale.cycleScale();
        if (cycleScale <= 1.0) return; // nothing stretched -> nothing to align

        World bukkitWorld = WorldKeys.resolve(world);
        if (bukkitWorld == null) return;

        long expected = (long) (VANILLA_TRADER_SPAWN_DAY_LENGTH * cycleScale);
        Integer actual = readSpawnDayLength(bukkitWorld);

        if (actual == null) {
            plugin.getLogger().warning("[wanderingTraderSpawn] Could not read " + SPAWN_DAY_LENGTH_PATH
                    + " for world " + world.asString() + "; cannot verify wandering trader spawn timing.");
            return;
        }

        if (actual != expected) {
            plugin.getLogger().warning("[wanderingTraderSpawn] World " + world.asString() + ": "
                    + SPAWN_DAY_LENGTH_PATH + " is " + actual + " but should be " + expected
                    + " (24000 * cycleScale " + cycleScale + ") to match your day length. Set it in the world's"
                    + " paper-world.yml or config/paper-world-defaults.yml. This makes wandering traders appear"
                    + " once per stretched day instead of once per 20 real minutes (easier).");
        } else {
            plugin.getLogger().info("[wanderingTraderSpawn] World " + world.asString() + ": "
                    + SPAWN_DAY_LENGTH_PATH + " matches your day length (" + expected + ").");
        }
    }

    @Override
    public void disable(WorldKey world) {
        // Startup check only -- nothing to tear down.
    }

    /** Effective spawn-day-length for the world: its paper-world.yml override, else the server default. */
    private Integer readSpawnDayLength(World world) {
        File perWorld = new File(world.getWorldFolder(), "paper-world.yml");
        if (perWorld.isFile()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(perWorld);
            if (config.isSet(SPAWN_DAY_LENGTH_PATH)) return config.getInt(SPAWN_DAY_LENGTH_PATH);
        }

        File defaults = new File(Bukkit.getWorldContainer(), "config/paper-world-defaults.yml");
        if (defaults.isFile()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(defaults);
            if (config.isSet(SPAWN_DAY_LENGTH_PATH)) return config.getInt(SPAWN_DAY_LENGTH_PATH);
        }

        return null;
    }
}
