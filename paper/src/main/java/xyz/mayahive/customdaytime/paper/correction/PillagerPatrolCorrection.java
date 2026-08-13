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

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.correction.Correction;
import xyz.mayahive.customdaytime.common.world.WorldTimeScale;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * PILLAGER_SPAWN (default off) — spreads pillager patrols out to match a stretched day.
 *
 * <p>The patrol spawner runs on a gameTime timer, so patrols keep appearing at their vanilla
 * real-time rate no matter how long the day is — correct in real-time terms, which is why this is
 * off by default. Enabling it thins patrols by {@code 1/cycleScale} so they arrive about once per
 * stretched day instead, making the world <em>easier</em>.</p>
 *
 * <p>An entire patrol (captain plus escorts) spawns within a single tick, so the keep/drop decision
 * is made once per {@code (world, tick)} — effectively at the leader's spawn — and reused for the
 * rest of that tick's PATROL spawns, so patrols drop whole rather than partial.</p>
 */
public final class PillagerPatrolCorrection implements Correction, Listener {

    private final Map<WorldKey, Supplier<WorldTimeScale>> scales = new ConcurrentHashMap<>();
    private final Map<WorldKey, long[]> patrolDecision = new ConcurrentHashMap<>();

    @Override
    public String key() {
        return "pillagerSpawn";
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
        patrolDecision.remove(world);
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.PATROL) return;

        World world = event.getEntity().getWorld();
        WorldKey key = WorldKeys.of(world);
        Supplier<WorldTimeScale> supplier = scales.get(key);
        if (supplier == null) return; // world not corrected

        WorldTimeScale scale = supplier.get();
        if (scale == null) return;
        double cycleScale = scale.cycleScale();
        if (cycleScale <= 1.0) return; // nothing stretched -> no-op

        long tick = world.getGameTime();
        double cancelChance = 1.0 - 1.0 / cycleScale;

        // One decision per (world, tick): the first PATROL spawn of the tick (the leader) rolls it,
        // the rest of that tick's patrol reuse it, so a patrol is dropped whole or kept whole.
        long[] decision = patrolDecision.compute(key, (k, previous) ->
                (previous != null && previous[0] == tick)
                        ? previous
                        : new long[]{tick, ThreadLocalRandom.current().nextDouble() < cancelChance ? 1L : 0L});

        if (decision[1] == 1L) event.setCancelled(true);
    }
}
