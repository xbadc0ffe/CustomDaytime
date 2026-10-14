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
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.context.CustomDaytimeContext;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VILLAGER_SLEEP -- keeps villagers eligible to spawn iron golems on a stretched day.
 *
 * <p>Vanilla counts a villager as "recently slept" while {@code gameTime - LAST_SLEPT < 24000} (one
 * vanilla day). On a stretched day the night alone outlasts that window, so villagers wake already
 * past it and natural iron golems stop spawning.</p>
 *
 * <p>The correction remembers the gameTime of each villager's last <em>real</em> sleep (vanilla
 * stamps {@code LAST_SLEPT} at sleep start; any value this plugin did not write is a real sleep) in
 * the villager's persistent data, and each sweep rewrites {@code LAST_SLEPT} so that vanilla's gap
 * reads {@code (gameTime - realSleep) / cycleScale}: the window then spans exactly one stretched
 * day after each real sleep and lapses on its own, as in vanilla. The target is absolute, not an
 * increment on the previous write, so sweeps a villager missed (unloaded chunk, non-ticking chunk,
 * server restart) are caught up in full on the next one instead of leaving a permanent deficit.</p>
 */
public final class VillagerSleepCorrection extends TrackedVillagerCorrection {

    /** Vanilla counts a villager as "recently slept" for one vanilla day. */
    private static final long VANILLA_GOLEM_WINDOW_TICKS = 24000L;

    /** PDC: gameTime of the villager's last real sleep (the anchor every target is computed from). */
    private final NamespacedKey anchorKey;

    /** PDC: the LAST_SLEPT value this plugin wrote last; a different value means a real sleep since. */
    private final NamespacedKey writtenKey;

    /** Villagers whose window has lapsed, for one-shot lapse/re-engage debug lines. */
    private final Set<UUID> lapsed = ConcurrentHashMap.newKeySet();

    public VillagerSleepCorrection(Plugin plugin, CustomDaytimeContext context) {
        super(plugin, context);
        this.anchorKey = new NamespacedKey(plugin, "sleep_anchor");
        this.writtenKey = new NamespacedKey(plugin, "sleep_written");
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
    protected void applyToVillager(Villager villager, WorldSweep sweep) {
        // Applies to EVERY tracked villager, including sleeping ones. LAST_SLEPT is stamped at sleep
        // start, so a villager waking from a full stretched night (>24000 ticks) would already be
        // past the golem window; keeping the gap scaled throughout sleep is the whole point.
        Long lastSlept = villager.getMemory(MemoryKey.LAST_SLEPT);
        if (lastSlept == null) {
            if (debugEnabled()) debugLog("[" + key() + "] " + shortId(villager) + " skip: no LAST_SLEPT (never slept)");
            return;
        }

        // Re-read on the villager's own thread: the sweep's value is stale if this task waited for a
        // chunk that was not ticking.
        long now = villager.getWorld().getGameTime();
        double cycleScale = sweep.cycleScale();
        UUID id = villager.getUniqueId();

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Long anchor = pdc.get(anchorKey, PersistentDataType.LONG);
        Long written = pdc.get(writtenKey, PersistentDataType.LONG);

        // A LAST_SLEPT this plugin did not write is a real sleep: vanilla stamps it at sleep start.
        // (Also re-anchor on anything inconsistent: a clock that went backwards, or a stamp older
        // than the anchor from a restored entity.)
        boolean realSleep = anchor == null || written == null
                || lastSlept != written.longValue() || anchor > now || lastSlept < anchor;
        if (realSleep) {
            if (debugEnabled()) debugLog("[" + key() + "] " + shortId(villager) + " RE-ANCHORED: real sleep at " + lastSlept
                    + " (previous anchor=" + anchor + ", lastWritten=" + written + ", gameTime=" + now + ")");
            anchor = lastSlept;
            pdc.set(anchorKey, PersistentDataType.LONG, anchor);
            pdc.set(writtenKey, PersistentDataType.LONG, lastSlept);
            if (lapsed.remove(id) && debugEnabled()) debugLog("[" + key() + "] " + shortId(villager) + " RE-ENGAGED");
        }

        long sinceSleep = now - anchor;
        long window = (long) (VANILLA_GOLEM_WINDOW_TICKS * cycleScale);
        if (sinceSleep >= window) {
            // One stretched day since the real sleep: vanilla's gap has crossed 24000 on its own, which
            // is exactly when eligibility should lapse. Leave the villager alone until it sleeps again.
            if (lapsed.add(id) && debugEnabled()) debugLog("[" + key() + "] " + shortId(villager)
                    + " LAPSED: " + sinceSleep + " ticks since real sleep at " + anchor + " (window=" + window
                    + ", lastSlept=" + lastSlept + ", gameTime=" + now + ") -- eligible again after its next sleep");
            return;
        }

        // Where vanilla's gap should read now: sinceSleep/cycleScale. Aimed one sweep ahead, because
        // the gap grows at full rate between sweeps: with the lead it lands exactly on target at the
        // next sweep instead of overshooting it, so the lapse never comes early. Never past now.
        long lead = (long) (SWEEP_INTERVAL_TICKS * (1.0 - 1.0 / cycleScale));
        long target = Math.min(now, now - (long) (sinceSleep / cycleScale) + lead);

        if (target != lastSlept) {
            villager.setMemory(MemoryKey.LAST_SLEPT, target);
            pdc.set(writtenKey, PersistentDataType.LONG, target);
        }

        if (debugEnabled()) debugLog("[" + key() + "] " + shortId(villager)
                + " sinceSleep=" + sinceSleep + "/" + window
                + " vanillaGap=" + (now - lastSlept) + "->" + (now - target)
                + (target != lastSlept ? " wrote lastSlept=" + target : " no-op"));
    }

    @Override
    protected void onWorldSweep(WorldKey world, WorldSweep sweep, int trackedCount) {
        if (!debugEnabled()) return;
        World bukkit = resolve(world);
        long dayTime = bukkit != null ? Math.floorMod(bukkit.getFullTime(), 24000L) : -1;
        debugLog("[" + key() + "] sweep " + world.asString()
                + " gameTime=" + sweep.now() + " dayTime=" + dayTime
                + " cycleScale=" + sweep.cycleScale() + " tracked=" + trackedCount);
    }

    @Override
    protected void onVillagerTracked(Villager villager, String how) {
        logTracking(villager, "TRACKED", how);
    }

    @Override
    protected void onVillagerUntracked(Villager villager, String how) {
        lapsed.remove(villager.getUniqueId());
        logTracking(villager, "UNTRACKED", how);
    }

    // The TRACKED/UNTRACKED pair timestamps every span a villager spends outside the tracked set
    // (chunk unloads included). The anchor-based target catches such spans up, but the log still
    // shows whether a farm was unloaded across a night (no real sleep -> no re-anchor).
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
