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

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.correction.Correction;
import xyz.mayahive.customdaytime.common.world.WorldTimeScale;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * VILLAGER_SLEEP — keeps villagers eligible to spawn iron golems on a stretched day.
 *
 * <p>Vanilla only counts a villager as "recently slept" while {@code gameTime - LAST_SLEPT < 24000}
 * (one vanilla day). On a stretched day the night alone outlasts that window, so villagers wake
 * already past it and natural iron golems stop spawning. We keep the gameTime delta scaled by
 * advancing {@code LAST_SLEPT} more slowly than real time, so the vanilla check stays satisfied for
 * one stretched cycle after each real sleep and then lapses on its own.</p>
 *
 * <p>Folia-safe by construction: villagers are tracked via a set maintained from entity add/remove
 * events (which fire on the owning region thread), and each memory read/write is dispatched through
 * the villager's own {@link org.bukkit.entity.Entity#getScheduler() EntityScheduler}. No off-thread
 * bulk entity query and no per-tick scan.</p>
 */
public final class VillagerSleepCorrection implements Correction, Listener {

    /** Vanilla counts a villager as "recently slept" for one vanilla day. */
    private static final long VANILLA_GOLEM_WINDOW_TICKS = 24000L;

    /** ~20s. LAST_SLEPT stays valid for 24000 gameTime ticks once stamped, so this is ample. */
    private static final long SWEEP_INTERVAL_TICKS = 400L;

    private final Plugin plugin;
    private final Map<WorldKey, WorldState> worlds = new ConcurrentHashMap<>();
    private ScheduledTask sweepTask;

    public VillagerSleepCorrection(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String key() {
        return "villagerSleep";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public void enable(WorldKey world, Supplier<WorldTimeScale> scale) {
        worlds.computeIfAbsent(world, key -> {
            WorldState state = new WorldState(scale, ConcurrentHashMap.newKeySet(), new AtomicLong(-1L));
            seedLoadedChunks(key, state);
            return state;
        });
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

    // --- tracked-set maintenance: these events fire on the entity's owning region thread ---

    @EventHandler
    public void onEntityAdd(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        WorldState state = worlds.get(keyOf(villager.getWorld()));
        if (state != null) state.villagers().add(villager);
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        WorldState state = worlds.get(keyOf(villager.getWorld()));
        if (state != null) state.villagers().remove(villager);
    }

    // --- one-time bootstrap for chunks already loaded when we enable (mid-session enable/reload) ---

    private void seedLoadedChunks(WorldKey key, WorldState state) {
        World world = resolve(key);
        if (world == null) return;
        // getLoadedChunks() is a structural snapshot; the actual entity access is deferred to each
        // chunk's owning region thread via the region scheduler, so this is Folia-safe.
        for (Chunk chunk : world.getLoadedChunks()) {
            Bukkit.getRegionScheduler().run(plugin, world, chunk.getX(), chunk.getZ(), task -> {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Villager villager) state.villagers().add(villager);
                }
            });
        }
    }

    // --- sweep ---

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
            if (cycleScale <= 1.0) continue; // nothing stretched -> correction is a no-op

            long now = world.getGameTime();
            long previous = state.lastSweepGameTime().getAndSet(now);
            if (previous < 0L) continue; // first sweep: only record the baseline

            // Measured elapsed, not a nominal 400: a late sweep must not under-correct.
            long elapsed = now - previous;
            if (elapsed <= 0L) continue;

            long advance = (long) (elapsed * (1.0 - 1.0 / cycleScale));
            if (advance <= 0L) continue;

            // Correct EVERY tracked villager, including sleeping ones. LAST_SLEPT is stamped at
            // sleep start, so a villager waking from a full stretched night (>24000 ticks) would
            // already be past the golem window; keeping the delta scaled throughout sleep is the
            // whole point. Do NOT add an "isSleeping" skip here as an optimization.
            for (Villager villager : state.villagers()) {
                villager.getScheduler().run(
                        plugin,
                        task -> restamp(villager, now, advance),
                        () -> state.villagers().remove(villager)); // retired: prune dead refs
            }
        }
    }

    private void restamp(Villager villager, long now, long advance) {
        Long lastSlept = villager.getMemory(MemoryKey.LAST_SLEPT);
        if (lastSlept == null) return;

        // Guard at the vanilla window (24000), not the scaled window: because the delta grows at
        // 1/cycleScale, it reaches 24000 after exactly one stretched cycle, which is where
        // eligibility should lapse. Past that, leave the villager alone.
        if (now - lastSlept < VANILLA_GOLEM_WINDOW_TICKS) {
            long next = Math.min(now, lastSlept + advance);
            if (next != lastSlept) villager.setMemory(MemoryKey.LAST_SLEPT, next);
        }
    }

    // --- helpers ---

    private WorldKey keyOf(World world) {
        Key key = world.getKey();
        return new WorldKey(key.namespace(), key.value());
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

    private record WorldState(Supplier<WorldTimeScale> scale, Set<Villager> villagers, AtomicLong lastSweepGameTime) {
    }
}
