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

package xyz.mayahive.customdaytime.common.event.listener;

import lombok.RequiredArgsConstructor;
import xyz.mayahive.customdaytime.api.platform.PlatformWorld;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.api.platform.Platform;
import xyz.mayahive.customdaytime.common.context.CustomDaytimeContext;
import xyz.mayahive.customdaytime.common.event.type.WorldPlayerCountChangeEvent;
import xyz.mayahive.customdaytime.common.event.type.WorldSleepingPlayerCountChangeEvent;

@RequiredArgsConstructor
public class WorldActivityListener {

    private final CustomDaytimeContext context;

    public void onWorldPlayerCountChangeEvent(WorldPlayerCountChangeEvent event) {
        Platform platform = context.platform();
        PlatformWorld world = event.world();
        WorldKey key = world.key();

        // Sample INSIDE the delayed task, never at event time: PlayerQuitEvent fires while the
        // quitting player is still in the world's player list, so an event-time read is actual + 1
        // and inflates totalPlayers until the next join. Mirrors the sleeping-count handler below.
        context.platform().scheduler().runLater(
                () -> {
                    int totalPlayers = world.playerCount();
                    context.worldTimeManager().setTotalPlayers(key, totalPlayers);
                    if (platform.debug()) platform.logger().info("Registered WorldPlayerCountChangeEvent. Updated total players count to " + totalPlayers);
                },
                1
        );
    }

    public void onWorldSleepingPlayerCountChange(WorldSleepingPlayerCountChangeEvent event) {
        Platform platform = context.platform();
        PlatformWorld world = event.world();
        WorldKey key = world.key();


        context.platform().scheduler().runLater(
                () -> {
                    int sleepingPlayers = world.sleepingPlayerCount();
                    context.worldTimeManager().setSleepingPlayers(key, sleepingPlayers);
                    if (platform.debug()) platform.logger().info("Registered WorldSleepingPlayerCountChangeEvent. Updated sleeping players count to " + sleepingPlayers);
                },
                1
        );
    }
}
