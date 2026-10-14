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

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.common.context.CustomDaytimeContext;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VILLAGER_STOCK -- keeps a working villager's trades restocking at vanilla's real-time cadence on a
 * stretched day.
 *
 * <p>Vanilla only restocks a villager while it is at its workstation during its work hours (dayTime
 * 2000-9000). On a stretched day that leaves every other part of the cycle -- several times longer
 * in real time -- with no restock at all, so a trade depleted in the evening sits empty for far
 * longer than it would in vanilla. We drive vanilla's own restock ({@link Villager#restock()}:
 * demand update, replenish event per trade, uses reset) on a fixed real-time cadence, around the
 * clock, for villagers that are employed and have actually reached their workstation within the
 * last stretched day.</p>
 *
 * <p>The job-site + worked-recently gate is the point: a trading hall whose villagers never reach a
 * workstation keeps a stale/absent {@code LAST_WORKED_AT_POI} and is never restocked, exactly as in
 * vanilla. The "recently" window is vanilla's one day, scaled by cycleScale -- unscaled it expired
 * mid-night and left a dead zone until the next work shift. The cadence throttle is stored per
 * villager in its PDC and read/written on the villager's own region thread.</p>
 */
public final class VillagerStockCorrection extends TrackedVillagerCorrection {

    /** Vanilla's "worked recently" window: one day. Scaled, so it spans one stretched day. */
    private static final long VANILLA_WORK_RECENCY_TICKS = 24000L;

    /** Minimum gameTime between driven restocks. Fixed (NOT scaled) = vanilla's real-time cadence. */
    private static final long RESTOCK_INTERVAL_TICKS = 12000L;

    private final NamespacedKey lastRestockKey;

    /** Villagers with depleted trades currently held back by the work gate, for one-shot debug lines. */
    private final Set<UUID> gated = ConcurrentHashMap.newKeySet();

    public VillagerStockCorrection(Plugin plugin, CustomDaytimeContext context) {
        super(plugin, context);
        this.lastRestockKey = new NamespacedKey(plugin, "last_driven_restock");
    }

    @Override
    public String key() {
        return "villagerStock";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    protected void applyToVillager(Villager villager, WorldSweep sweep) {
        // Employed only: a workstation POI must be claimed. Unemployed villagers and nitwits have no
        // JOB_SITE and never restock.
        if (villager.getMemory(MemoryKey.JOB_SITE) == null) return;

        // Nothing to do unless a trade has been used (vanilla's needsToRestock).
        int depleted = 0;
        for (MerchantRecipe recipe : villager.getRecipes()) {
            if (recipe.getUses() > 0) depleted++;
        }
        UUID id = villager.getUniqueId();
        if (depleted == 0) {
            gated.remove(id);
            return;
        }

        long now = villager.getWorld().getGameTime();

        // Worked recently: the villager must have reached its POI within the last stretched day. A
        // trading hall with no workstation contact keeps a stale/absent LAST_WORKED_AT_POI and is
        // skipped -- we accelerate vanilla's work-driven restock, we do not hand out free stock.
        Long lastWorked = villager.getMemory(MemoryKey.LAST_WORKED_AT_POI);
        long recency = (long) (VANILLA_WORK_RECENCY_TICKS * sweep.cycleScale());
        if (lastWorked == null || now - lastWorked >= recency) {
            if (gated.add(id) && debugEnabled()) debugLog("[" + key() + "] " + shortId(villager)
                    + " held: " + depleted + " depleted trade(s) but not working (lastWorkedAtPoi=" + lastWorked
                    + (lastWorked != null ? ", " + (now - lastWorked) + " ticks ago" : "")
                    + ", window=" + recency + ", gameTime=" + now + ")");
            return;
        }
        gated.remove(id);

        // Throttle to vanilla's real-time restock cadence. This fixed gameTime interval (NOT scaled by
        // cycleScale) is what lifts the "only during work hours" limit without becoming infinite stock.
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Long lastRestock = pdc.get(lastRestockKey, PersistentDataType.LONG);
        if (lastRestock != null && now - lastRestock < RESTOCK_INTERVAL_TICKS) return;

        // Vanilla's own restock: demand update, VillagerReplenishTradeEvent per trade, uses reset,
        // and prices refreshed for a player who has the trade window open.
        villager.restock();
        pdc.set(lastRestockKey, PersistentDataType.LONG, now);

        if (debugEnabled()) debugLog("[" + key() + "] " + shortId(villager)
                + " restocked " + depleted + " depleted trade(s) (lastWorkedAtPoi " + (now - lastWorked)
                + " ticks ago, restocksToday=" + villager.getRestocksToday() + ", gameTime=" + now + ")");
    }

    @Override
    protected void onVillagerUntracked(Villager villager, String how) {
        gated.remove(villager.getUniqueId());
    }

    private static String shortId(Villager villager) {
        return villager.getUniqueId().toString().substring(0, 8);
    }
}
