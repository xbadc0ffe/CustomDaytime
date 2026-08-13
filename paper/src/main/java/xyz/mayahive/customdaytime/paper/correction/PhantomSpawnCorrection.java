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
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.correction.Correction;
import xyz.mayahive.customdaytime.common.world.WorldTimeScale;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * PHANTOM_SPAWN — keeps phantom "insomnia" tied to nights survived rather than real time.
 *
 * <p>Vanilla starts phantom spawn rolls once a player's {@code TIME_SINCE_REST} statistic passes
 * 72000 (three vanilla days). That statistic counts real game ticks, so on a stretched day it
 * crosses the threshold after far fewer day/night cycles than intended. We slow its growth to
 * {@code 1/cycleScale} of real time by subtracting the un-scaled portion each sweep, so insomnia
 * lands after roughly three stretched nights, as vanilla intends.</p>
 *
 * <p>Player-shaped rather than entity-shaped: no tracked set and no per-entity scheduler — the
 * sweep simply walks each world's online players and adjusts the statistic. This exercises the
 * {@link Correction} SPI against a non-villager correction; it needs neither {@code Listener} nor
 * any interface change.</p>
 *
 * <p>Folia note: the statistic read/write runs on the global region scheduler, not the player's
 * region thread. Correct on Paper; if a Folia thread check flags it, the isolated fix is to wrap
 * the per-player block in {@code player.getScheduler().run(...)}.</p>
 */
public final class PhantomSpawnCorrection implements Correction {

    /** ~20s. TIME_SINCE_REST changes slowly relative to the 72000-tick threshold. */
    private static final long SWEEP_INTERVAL_TICKS = 400L;

    private final Plugin plugin;
    private final Map<WorldKey, WorldState> worlds = new ConcurrentHashMap<>();
    private ScheduledTask sweepTask;

    public PhantomSpawnCorrection(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String key() {
        return "phantomSpawn";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public void enable(WorldKey world, Supplier<WorldTimeScale> scale) {
        worlds.computeIfAbsent(world, key -> new WorldState(scale, new AtomicLong(-1L)));
        ensureSweepRunning();
    }

    @Override
    public void disable(WorldKey world) {
        worlds.remove(world);
        if (worlds.isEmpty() && sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    private void ensureSweepRunning() {
        if (sweepTask != null) return;
        sweepTask = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, task -> sweep(), 1L, SWEEP_INTERVAL_TICKS);
    }

    private void sweep() {
        for (Map.Entry<WorldKey, WorldState> entry : worlds.entrySet()) {
            WorldState state = entry.getValue();
            World world = resolve(entry.getKey());
            if (world == null) continue;

            WorldTimeScale scale = state.scale().get();
            if (scale == null) continue;

            double cycleScale = scale.cycleScale();
            if (cycleScale <= 1.0) continue; // nothing stretched -> no-op

            long now = world.getGameTime();
            long previous = state.lastSweepGameTime().getAndSet(now);
            if (previous < 0L) continue; // first sweep: only record the baseline

            // Delta-based, not multiplicative: subtract the un-scaled portion of the real ticks
            // that elapsed, using MEASURED elapsed gameTime so a late sweep still corrects fully.
            long elapsed = now - previous;
            if (elapsed <= 0L) continue;

            long reduction = (long) (elapsed * (1.0 - 1.0 / cycleScale));
            if (reduction <= 0L) continue;

            for (Player player : world.getPlayers()) {
                GameMode mode = player.getGameMode();
                if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) continue; // can't trigger phantoms

                int current = player.getStatistic(Statistic.TIME_SINCE_REST);
                // Skip when already reset (a sleep zeroes it): nothing to slow, and clamping at 0
                // below ensures the sleep reset is never driven negative.
                if (current <= 0) continue;

                int next = (int) Math.max(0L, current - reduction);
                if (next != current) player.setStatistic(Statistic.TIME_SINCE_REST, next);
            }
        }
    }

    private World resolve(WorldKey key) {
        for (World world : Bukkit.getWorlds()) {
            Key worldKey = world.getKey();
            if (worldKey.namespace().equals(key.namespace()) && worldKey.value().equals(key.value())) {
                return world;
            }
        }
        return null;
    }

    private record WorldState(Supplier<WorldTimeScale> scale, AtomicLong lastSweepGameTime) {
    }
}
