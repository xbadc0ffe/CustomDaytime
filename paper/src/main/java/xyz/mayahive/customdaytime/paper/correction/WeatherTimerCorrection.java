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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.correction.Correction;
import xyz.mayahive.customdaytime.common.world.WorldTimeScale;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * WEATHER_TIMER (default off) — stretches rain/thunder/clear periods to match a stretched day.
 *
 * <p>Weather runs on gameTime timers with no dayTime coupling, so it's already correct in real-time
 * terms — which is why this is off by default. Enabling it multiplies each new period's duration by
 * {@code cycleScale} so rain, thunder and clear spells correlate with your day length instead of
 * vanilla's 20-minute day (thematic; not a difficulty change in itself).</p>
 */
public final class WeatherTimerCorrection implements Correction, Listener {

    private final Plugin plugin;
    private final Map<WorldKey, Supplier<WorldTimeScale>> scales = new ConcurrentHashMap<>();

    public WeatherTimerCorrection(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String key() {
        return "weatherTimer";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public void enable(WorldKey world, Supplier<WorldTimeScale> scale) {
        scales.put(world, scale);
    }

    @Override
    public void disable(WorldKey world) {
        scales.remove(world);
    }

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        World world = event.getWorld();
        double cycleScale = cycleScaleFor(world);
        if (cycleScale <= 1.0) return; // nothing stretched -> no-op

        boolean rainStarting = event.toWeatherState();
        // Scale one tick later, once the server has set the new period's duration.
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (rainStarting) {
                world.setWeatherDuration(scaled(world.getWeatherDuration(), cycleScale));
            } else {
                world.setClearWeatherDuration(scaled(world.getClearWeatherDuration(), cycleScale));
            }
        }, 1L);
    }

    @EventHandler
    public void onThunderChange(ThunderChangeEvent event) {
        World world = event.getWorld();
        double cycleScale = cycleScaleFor(world);
        if (cycleScale <= 1.0) return; // nothing stretched -> no-op

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task ->
                world.setThunderDuration(scaled(world.getThunderDuration(), cycleScale)), 1L);
    }

    private double cycleScaleFor(World world) {
        Supplier<WorldTimeScale> supplier = scales.get(WorldKeys.of(world));
        if (supplier == null) return 1.0;
        WorldTimeScale scale = supplier.get();
        return scale == null ? 1.0 : scale.cycleScale();
    }

    private int scaled(int duration, double cycleScale) {
        long value = (long) (duration * cycleScale);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }
}
