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

import java.util.List;

/**
 * VILLAGER_STOCK — keeps a working villager's trades restocking at vanilla's real-time cadence on a
 * stretched day.
 *
 * <p>Vanilla lets a villager restock at most twice per day and resets that cap on the <em>dayTime</em>
 * day boundary, so on a stretched day a villager restocks only twice per (long) real day and its
 * trades sit depleted. We accelerate vanilla's <em>work-driven</em> restock path rather than replace
 * it: each sweep, for a villager that is employed (has a job-site POI) and has actually reached that
 * POI recently, we reset its depleted trades — throttled to a fixed gameTime interval so it restocks
 * at vanilla's real-time rate (~once per 10 real minutes) instead of being capped per stretched day.</p>
 *
 * <p>The job-site + worked-recently gate is the point: a trading hall whose villagers never reach a
 * workstation keeps a stale/absent {@code LAST_WORKED_AT_POI} and is never restocked, exactly as in
 * vanilla. The throttle timestamp is stored per villager in its PDC and read/written on the
 * villager's own region thread.</p>
 */
public final class VillagerStockCorrection extends TrackedVillagerCorrection {

    /** A villager must have reached its job site within this many gameTime ticks to be "working". */
    private static final long WORK_RECENCY_TICKS = 24000L;

    /** Vanilla's minimum interval between restocks. Fixed gameTime (not scaled) = real-time cadence. */
    private static final long RESTOCK_INTERVAL_TICKS = 12000L;

    private final NamespacedKey lastRestockKey;

    public VillagerStockCorrection(Plugin plugin) {
        super(plugin);
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
    protected void applyToVillager(Villager villager, long now, long delta) {
        // Employed only: a workstation POI must be claimed. Unemployed villagers and nitwits have no
        // JOB_SITE and never restock.
        if (villager.getMemory(MemoryKey.JOB_SITE) == null) return;

        // Worked recently: the villager must have actually reached its POI. A trading hall with no
        // workstation contact keeps a stale/absent LAST_WORKED_AT_POI and is skipped -- we accelerate
        // vanilla's work-driven restock, we do not hand out free stock.
        Long lastWorked = villager.getMemory(MemoryKey.LAST_WORKED_AT_POI);
        if (lastWorked == null || now - lastWorked >= WORK_RECENCY_TICKS) return;

        // Throttle to vanilla's real-time restock cadence. This fixed gameTime interval (NOT scaled by
        // cycleScale) is what lifts the "twice per stretched day" cap without becoming infinite stock.
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Long lastRestock = pdc.get(lastRestockKey, PersistentDataType.LONG);
        if (lastRestock != null && now - lastRestock < RESTOCK_INTERVAL_TICKS) return;

        List<MerchantRecipe> recipes = villager.getRecipes();
        boolean restocked = false;
        for (MerchantRecipe recipe : recipes) {
            if (recipe.getUses() > 0) {
                recipe.setUses(0);
                restocked = true;
            }
        }

        if (restocked) {
            villager.setRecipes(recipes);
            pdc.set(lastRestockKey, PersistentDataType.LONG, now);
        }
    }
}
