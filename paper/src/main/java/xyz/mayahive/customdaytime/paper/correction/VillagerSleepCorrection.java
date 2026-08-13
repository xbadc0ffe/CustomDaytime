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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VILLAGER_SLEEP — keeps villagers eligible to spawn iron golems on a stretched day.
 *
 * <p>Vanilla counts a villager as "recently slept" while {@code gameTime - LAST_SLEPT < 24000} (one
 * vanilla day). On a stretched day the night alone outlasts that window, so villagers wake already
 * past it and natural iron golems stop spawning. Each sweep we advance {@code LAST_SLEPT} by the
 * un-scaled portion of elapsed ticks, so the vanilla check stays satisfied for one stretched cycle
 * after each real sleep and then lapses on its own.</p>
 *
 * <p>Villagers are tracked via a per-world set maintained from entity add/remove events (which fire
 * on the owning region thread) and seeded from already-loaded chunks on enable. Every memory op is
 * dispatched through the villager's {@code EntityScheduler}, so it is correct on Paper and Folia.</p>
 */
public final class VillagerSleepCorrection extends AbstractScheduledCorrection implements Listener {

    /** Vanilla counts a villager as "recently slept" for one vanilla day. */
    private static final long VANILLA_GOLEM_WINDOW_TICKS = 24000L;

    private final Map<WorldKey, Set<Villager>> tracked = new ConcurrentHashMap<>();

    public VillagerSleepCorrection(Plugin plugin) {
        super(plugin);
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
    protected void onWorldEnabled(WorldKey world) {
        Set<Villager> villagers = tracked.computeIfAbsent(world, key -> ConcurrentHashMap.newKeySet());
        seedLoadedChunks(world, villagers);
    }

    @Override
    protected void onWorldDisabled(WorldKey world) {
        tracked.remove(world);
    }

    @Override
    protected void apply(Map<WorldKey, WorldSweep> sweeps) {
        for (Map.Entry<WorldKey, WorldSweep> entry : sweeps.entrySet()) {
            Set<Villager> villagers = tracked.get(entry.getKey());
            if (villagers == null) continue;

            long now = entry.getValue().now();
            long advance = entry.getValue().delta();

            // Correct EVERY villager, including sleeping ones. LAST_SLEPT is stamped at sleep start,
            // so a villager waking from a full stretched night (>24000 ticks) would already be past
            // the golem window; keeping the delta scaled throughout sleep is the whole point. Do NOT
            // add an "isSleeping" skip here as an optimization.
            for (Villager villager : villagers) {
                villager.getScheduler().run(
                        plugin(),
                        task -> restamp(villager, now, advance),
                        () -> villagers.remove(villager)); // retired: prune dead refs
            }
        }
    }

    private void restamp(Villager villager, long now, long advance) {
        Long lastSlept = villager.getMemory(MemoryKey.LAST_SLEPT);
        if (lastSlept == null) return;

        // Guard at the vanilla window (24000), not the scaled one: because the delta grows at
        // 1/cycleScale, it reaches 24000 after exactly one stretched cycle, which is where
        // eligibility should lapse. Past that, leave the villager alone.
        if (now - lastSlept < VANILLA_GOLEM_WINDOW_TICKS) {
            long next = Math.min(now, lastSlept + advance);
            if (next != lastSlept) villager.setMemory(MemoryKey.LAST_SLEPT, next);
        }
    }

    // --- tracked-set maintenance: these events fire on the entity's owning region thread ---

    @EventHandler
    public void onEntityAdd(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        Set<Villager> villagers = tracked.get(keyOf(villager.getWorld()));
        if (villagers != null) villagers.add(villager);
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        Set<Villager> villagers = tracked.get(keyOf(villager.getWorld()));
        if (villagers != null) villagers.remove(villager);
    }

    // --- one-time bootstrap for chunks already loaded when we enable (mid-session enable/reload) ---

    private void seedLoadedChunks(WorldKey key, Set<Villager> villagers) {
        World world = resolve(key);
        if (world == null) return;
        // getLoadedChunks() is a structural snapshot; the actual entity access is deferred to each
        // chunk's owning region thread via the region scheduler, so this is Folia-safe.
        for (Chunk chunk : world.getLoadedChunks()) {
            Bukkit.getRegionScheduler().run(plugin(), world, chunk.getX(), chunk.getZ(), task -> {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Villager villager) villagers.add(villager);
                }
            });
        }
    }
}
