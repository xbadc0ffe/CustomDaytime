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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.context.CustomDaytimeContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base for villager corrections. Maintains a per-world set of villagers -- populated from entity
 * add/remove events (which fire on the owning region thread) and seeded from already-loaded chunks
 * on enable -- and each sweep dispatches {@link #applyToVillager} for every tracked villager on its
 * own region thread. Subclasses implement only the per-villager change.
 */
public abstract class TrackedVillagerCorrection extends AbstractScheduledCorrection implements Listener {

    private final Map<WorldKey, Set<Villager>> tracked = new ConcurrentHashMap<>();

    protected TrackedVillagerCorrection(Plugin plugin, CustomDaytimeContext context) {
        super(plugin, context);
    }

    /**
     * Apply this correction to one villager, on the villager's own region thread. {@code sweep}
     * carries the sweep's gameTime, the scaled per-sweep delta ({@code elapsed*(1 - 1/cycleScale)})
     * and the cycle scale. The task may run later than the sweep if the villager's chunk is not
     * ticking, so re-read the world's gameTime when the exact "now" matters.
     */
    protected abstract void applyToVillager(Villager villager, WorldSweep sweep);

    /** Called once per world per sweep, before the per-villager dispatch. For diagnostics only. */
    protected void onWorldSweep(WorldKey world, WorldSweep sweep, int trackedCount) {
    }

    /** Called when a villager actually enters the tracked set ({@code how}: add/seed). Diagnostics only. */
    protected void onVillagerTracked(Villager villager, String how) {
    }

    /** Called when a villager actually leaves the tracked set ({@code how}: remove/retired). Diagnostics only. */
    protected void onVillagerUntracked(Villager villager, String how) {
    }

    @Override
    protected final void onWorldEnabled(WorldKey world) {
        Set<Villager> villagers = tracked.computeIfAbsent(world, key -> ConcurrentHashMap.newKeySet());
        seedLoadedChunks(world, villagers);
    }

    @Override
    protected final void onWorldDisabled(WorldKey world) {
        tracked.remove(world);
    }

    @Override
    protected final void apply(Map<WorldKey, WorldSweep> sweeps) {
        for (Map.Entry<WorldKey, WorldSweep> entry : sweeps.entrySet()) {
            Set<Villager> villagers = tracked.get(entry.getKey());
            if (villagers == null) continue;

            WorldSweep sweep = entry.getValue();

            onWorldSweep(entry.getKey(), sweep, villagers.size());

            for (Villager villager : villagers) {
                villager.getScheduler().run(
                        plugin(),
                        task -> applyToVillager(villager, sweep),
                        () -> { // retired: prune dead refs
                            if (villagers.remove(villager)) onVillagerUntracked(villager, "retired");
                        });
            }
        }
    }

    @EventHandler
    public void onEntityAdd(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        Set<Villager> villagers = tracked.get(keyOf(villager.getWorld()));
        if (villagers != null && villagers.add(villager)) onVillagerTracked(villager, "add");
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        Set<Villager> villagers = tracked.get(keyOf(villager.getWorld()));
        if (villagers != null && villagers.remove(villager)) onVillagerUntracked(villager, "remove");
    }

    private void seedLoadedChunks(WorldKey key, Set<Villager> villagers) {
        World world = resolve(key);
        if (world == null) return;
        // getLoadedChunks() is a structural snapshot; the actual entity access is deferred to each
        // chunk's owning region thread via the region scheduler, so this is Folia-safe.
        for (Chunk chunk : world.getLoadedChunks()) {
            Bukkit.getRegionScheduler().run(plugin(), world, chunk.getX(), chunk.getZ(), task -> {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Villager villager && villagers.add(villager)) {
                        onVillagerTracked(villager, "seed");
                    }
                }
            });
        }
    }
}
