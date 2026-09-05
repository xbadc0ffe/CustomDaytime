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

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.context.CustomDaytimeContext;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VILLAGER_SLEEP — keeps villagers eligible to spawn iron golems on a stretched day.
 *
 * <p>Vanilla counts a villager as "recently slept" while {@code gameTime - LAST_SLEPT < 24000} (one
 * vanilla day). On a stretched day the night alone outlasts that window, so villagers wake already
 * past it and natural iron golems stop spawning. Each sweep we advance {@code LAST_SLEPT} by the
 * un-scaled portion of elapsed ticks, so the vanilla check stays satisfied for one stretched cycle
 * after each real sleep and then lapses on its own.</p>
 */
public final class VillagerSleepCorrection extends TrackedVillagerCorrection {

    /** Vanilla counts a villager as "recently slept" for one vanilla day. */
    private static final long VANILLA_GOLEM_WINDOW_TICKS = 24000L;

    /** Villagers whose lapse (gap crossing the window) has been logged; cleared on a fresh sleep. */
    private final Set<UUID> lapsed = ConcurrentHashMap.newKeySet();

    public VillagerSleepCorrection(Plugin plugin, CustomDaytimeContext context) {
        super(plugin, context);
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
    protected void applyToVillager(Villager villager, long now, long advance) {
        // Applies to EVERY tracked villager, including sleeping ones. LAST_SLEPT is stamped at sleep
        // start, so a villager waking from a full stretched night (>24000 ticks) would already be
        // past the golem window; keeping the delta scaled throughout sleep is the whole point.
        Long lastSlept = villager.getMemory(MemoryKey.LAST_SLEPT);
        if (lastSlept == null) {
            if (debugEnabled()) debugLog("[" + key() + "] " + shortId(villager) + " skip: no LAST_SLEPT");
            return;
        }

        long gap = now - lastSlept;

        // Guard at the vanilla window (24000), not the scaled one: because the delta grows at
        // 1/cycleScale, it reaches 24000 after exactly one stretched cycle, which is where
        // eligibility should lapse. Past that, leave the villager alone.
        if (gap < VANILLA_GOLEM_WINDOW_TICKS) {
            if (lapsed.remove(villager.getUniqueId()) && debugEnabled()) {
                debugLog("[" + key() + "] " + shortId(villager) + " RE-ENGAGED: fresh LAST_SLEPT (gap=" + gap + ")");
            }
            long next = Math.min(now, lastSlept + advance);
            if (next != lastSlept) {
                villager.setMemory(MemoryKey.LAST_SLEPT, next);
                if (debugEnabled()) debugLog("[" + key() + "] " + shortId(villager)
                        + " lastSlept=" + lastSlept + " gap=" + gap + " wrote +" + (next - lastSlept) + " -> gap=" + (now - next));
            } else if (debugEnabled()) {
                debugLog("[" + key() + "] " + shortId(villager) + " lastSlept=" + lastSlept + " gap=" + gap + " no-op (current)");
            }
        } else {
            if (lapsed.add(villager.getUniqueId()) && debugEnabled()) {
                debugLog("[" + key() + "] " + shortId(villager)
                        + " GAP CROSSED 24000 (gap=" + gap + ", lastSlept=" + lastSlept + ", gameTime=" + now
                        + ") — correction disengaged until next real sleep");
            }
            if (debugEnabled()) debugLog("[" + key() + "] " + shortId(villager)
                    + " lastSlept=" + lastSlept + " gap=" + gap + " skip: lapsed (gap >= 24000)");
        }
    }

    @Override
    protected void onWorldSweep(WorldKey world, WorldSweep sweep, int trackedCount) {
        if (!debugEnabled()) return;
        World bukkit = resolve(world);
        long dayTime = bukkit != null ? bukkit.getFullTime() % 24000 : -1;
        debugLog("[" + key() + "] sweep " + world.asString()
                + " gameTime=" + sweep.now() + " dayTime=" + dayTime
                + " delta=" + sweep.delta() + " tracked=" + trackedCount);
    }

    @Override
    protected void onVillagerTracked(Villager villager, String how) {
        logTracking(villager, "TRACKED", how);
    }

    @Override
    protected void onVillagerUntracked(Villager villager, String how) {
        logTracking(villager, "UNTRACKED", how);
    }

    // The TRACKED/UNTRACKED pair timestamps every span a villager spends outside the tracked set
    // (chunk unloads included): the gap grows at full rate across that span, which is the
    // unload-deficit signature this logging exists to confirm or refute.
    private void logTracking(Villager villager, String what, String how) {
        if (!debugEnabled()) return;
        Location loc = villager.getLocation();
        debugLog("[" + key() + "] " + shortId(villager) + " " + what + " (" + how + ")"
                + " chunk=" + (loc.getBlockX() >> 4) + "," + (loc.getBlockZ() >> 4)
                + " gameTime=" + villager.getWorld().getGameTime());
    }

    private static String shortId(Villager villager) {
        return villager.getUniqueId().toString().substring(0, 8);
    }
}
