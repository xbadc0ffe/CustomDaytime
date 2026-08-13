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

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.correction.Correction;
import xyz.mayahive.customdaytime.common.world.WorldTimeScale;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Base for corrections that run a periodic global-scheduler sweep and scale a per-tick quantity.
 *
 * <p>It owns the sweep task, the per-world scale/baseline state, and the delta computation that all
 * scaled corrections share: each sweep it measures the real gameTime elapsed per world and hands the
 * subclass {@code delta = elapsed * (1 - 1/cycleScale)} — the un-scaled portion to add or subtract.
 * Subclasses decide what to do with it in {@link #apply(Map)}.</p>
 *
 * <p>The sweep baseline ({@code lastSweepGameTime}) is advanced UNCONDITIONALLY the moment a world
 * resolves — before the "no scale yet" / "cycleScale &le; 1" guards — so a run of skipped sweeps can
 * never make a later {@code elapsed} span the whole gap.</p>
 */
public abstract class AbstractScheduledCorrection implements Correction {

    /** ~20s. Ample for corrections whose thresholds are thousands of ticks. */
    protected static final long SWEEP_INTERVAL_TICKS = 400L;

    private final Plugin plugin;
    private final Map<WorldKey, WorldState> worlds = new ConcurrentHashMap<>();
    private ScheduledTask sweepTask;

    protected AbstractScheduledCorrection(Plugin plugin) {
        this.plugin = plugin;
    }

    protected final Plugin plugin() {
        return plugin;
    }

    @Override
    public final void enable(WorldKey world, Supplier<WorldTimeScale> scale) {
        if (worlds.putIfAbsent(world, new WorldState(scale, new AtomicLong(-1L))) == null) {
            onWorldEnabled(world);
        }
        ensureSweepRunning();
    }

    @Override
    public final void disable(WorldKey world) {
        if (worlds.remove(world) != null) {
            onWorldDisabled(world);
        }
        if (worlds.isEmpty() && sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    /** Called once when a world is first enabled — for per-world subclass state (e.g. tracked sets). */
    protected void onWorldEnabled(WorldKey world) {
    }

    /** Called when a world is disabled. */
    protected void onWorldDisabled(WorldKey world) {
    }

    /**
     * Apply this sweep. {@code sweeps} holds, per world being corrected, the current gameTime and the
     * delta to apply. Dispatch every entity-state access through {@code entity.getScheduler()} so it
     * runs on the entity's owning region thread (Folia-safe).
     */
    protected abstract void apply(Map<WorldKey, WorldSweep> sweeps);

    private void ensureSweepRunning() {
        if (sweepTask != null) return;
        sweepTask = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, task -> sweep(), 1L, SWEEP_INTERVAL_TICKS);
    }

    private void sweep() {
        Map<WorldKey, WorldSweep> sweeps = new HashMap<>();
        for (Map.Entry<WorldKey, WorldState> entry : worlds.entrySet()) {
            WorldState state = entry.getValue();
            World world = resolve(entry.getKey());
            if (world == null) continue; // world unloaded; disable() removes it from the map

            // Advance the baseline UNCONDITIONALLY once the world resolves, before the guards below,
            // so a run of skipped sweeps can't make the next elapsed span the whole gap.
            long now = world.getGameTime();
            long previous = state.lastSweepGameTime().getAndSet(now);

            WorldTimeScale scale = state.scale().get();
            if (scale == null) continue;

            double cycleScale = scale.cycleScale();
            if (cycleScale <= 1.0) continue; // nothing stretched -> no-op

            if (previous < 0L) continue; // first sweep: baseline only

            // Measured elapsed gameTime, never a nominal interval: a late sweep still corrects fully.
            long elapsed = now - previous;
            if (elapsed <= 0L) continue;

            long delta = (long) (elapsed * (1.0 - 1.0 / cycleScale));
            if (delta <= 0L) continue;

            sweeps.put(entry.getKey(), new WorldSweep(now, delta));
        }

        if (!sweeps.isEmpty()) {
            apply(sweeps);
        }
    }

    protected final World resolve(WorldKey key) {
        return WorldKeys.resolve(key);
    }

    protected final WorldKey keyOf(World world) {
        return WorldKeys.of(world);
    }

    /** The current gameTime and the delta to apply for one world in a sweep. */
    protected record WorldSweep(long now, long delta) {
    }

    private record WorldState(Supplier<WorldTimeScale> scale, AtomicLong lastSweepGameTime) {
    }
}
