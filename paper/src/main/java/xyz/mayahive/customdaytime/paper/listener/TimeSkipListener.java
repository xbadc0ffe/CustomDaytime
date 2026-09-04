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

package xyz.mayahive.customdaytime.paper.listener;

import lombok.RequiredArgsConstructor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ClockTimeSkipEvent;
import org.bukkit.event.world.TimeSkipEvent;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.context.CustomDaytimeContext;
import xyz.mayahive.customdaytime.common.service.DebugService;
import xyz.mayahive.customdaytime.common.world.NightSkipHandling;
import xyz.mayahive.customdaytime.paper.platform.PaperWorld;

@RequiredArgsConstructor
public class TimeSkipListener implements Listener {

    private final CustomDaytimeContext context;

    @EventHandler
    public void onTimeSkipEvent(TimeSkipEvent event) {
        String worldKey = event.getWorld().key().asString();
        if (!context.configService().getRootKeys().contains(worldKey)) {return;}

        if (!event.getSkipReason().equals(ClockTimeSkipEvent.SkipReason.NIGHT_SKIP)) {return;}

        // The event itself is vanilla's proof that enough players are deep-sleeping; its SleepStatus
        // count (spectators excluded, fauxSleeping included) is not reproducible from the Bukkit
        // surface, so it must not be re-derived here. The decision covers only what the event cannot
        // answer: does a live controller govern this clock, and is it night or day.
        WorldKey key = new PaperWorld(event.getWorld()).key();
        NightSkipHandling handling = context.worldTimeManager().nightSkipHandling(key);

        DebugService.log(context, "NIGHT_SKIP for world " + worldKey
                + " (handling=" + handling
                + ", skipAmount=" + event.getSkipAmount()
                + ", fullTime=" + event.getWorld().getFullTime()
                + ", sleeping=" + sleepingCount(event.getWorld()) + "/" + event.getWorld().getPlayerCount() + ")");

        switch (handling) {
            // Night on a governed world: the controller's acceleration replaces the vanilla jump.
            case CANCEL -> event.setCancelled(true);
            // Day-side skip on a governed world: vanilla still wakes everyone (and resets weather),
            // but the clock must not move -- the wake marker resolves to its NEXT occurrence, so an
            // unclamped skip here advances almost a whole day.
            case SUPPRESS_JUMP -> event.setSkipAmount(0L);
            case VANILLA -> {}
        }
    }

    private static int sleepingCount(World world) {
        int count = 0;
        for (Player player : world.getPlayers()) {
            if (player.isSleeping()) count++;
        }
        return count;
    }
}
