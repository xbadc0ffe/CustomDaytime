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

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import xyz.mayahive.customdaytime.api.model.WorldKey;

import java.util.Map;

/**
 * PHANTOM_SPAWN — keeps phantom "insomnia" tied to nights survived rather than real time.
 *
 * <p>Vanilla starts phantom spawn rolls once a player's {@code TIME_SINCE_REST} statistic passes
 * 72000 (three vanilla days). That statistic counts real game ticks, so on a stretched day it
 * crosses the threshold after far fewer day/night cycles than intended. Each sweep we subtract the
 * un-scaled portion of the elapsed ticks, slowing its growth to {@code 1/cycleScale} of real time,
 * so insomnia lands after roughly three stretched nights.</p>
 *
 * <p>Player-shaped: no tracked set — players are enumerated from {@link Bukkit#getOnlinePlayers()},
 * and all player-state access runs on the player's own region thread via its {@code EntityScheduler},
 * so it is correct on Folia.</p>
 */
public final class PhantomSpawnCorrection extends AbstractScheduledCorrection {

    public PhantomSpawnCorrection(Plugin plugin) {
        super(plugin);
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
    protected void apply(Map<WorldKey, WorldSweep> sweeps) {
        // Enumerate server-globally, then touch each player's state on its own region thread.
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(plugin(), task -> reduceInsomnia(player, sweeps), null);
        }
    }

    private void reduceInsomnia(Player player, Map<WorldKey, WorldSweep> sweeps) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return; // can't trigger phantoms

        WorldSweep sweep = sweeps.get(keyOf(player.getWorld()));
        if (sweep == null) return; // player's world isn't being corrected this sweep

        int current = player.getStatistic(Statistic.TIME_SINCE_REST);
        // Skip when already reset (a sleep zeroes it): nothing to slow, and clamping at 0 below
        // ensures the sleep reset is never driven negative.
        if (current <= 0) return;

        int next = (int) Math.max(0L, current - sweep.delta());
        if (next != current) player.setStatistic(Statistic.TIME_SINCE_REST, next);
    }
}
