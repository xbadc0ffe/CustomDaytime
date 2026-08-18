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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ClockTimeSkipEvent;
import org.bukkit.event.world.TimeSkipEvent;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.context.CustomDaytimeContext;
import xyz.mayahive.customdaytime.common.service.DebugService;
import xyz.mayahive.customdaytime.paper.platform.PaperWorld;

@RequiredArgsConstructor
public class TimeSkipListener implements Listener {

    private final CustomDaytimeContext context;

    @EventHandler
    public void onTimeSkipEvent(TimeSkipEvent event) {
        String worldKey = event.getWorld().key().asString();
        if (!context.configService().getRootKeys().contains(worldKey)) {return;}

        if (!event.getSkipReason().equals(ClockTimeSkipEvent.SkipReason.NIGHT_SKIP)) {return;}

        // Cancel only when we will actually take the advancement over. Gating this on the
        // controller's freshly recomputed decision -- rather than on an independent config read --
        // is what keeps the cancel and the acceleration on one predicate. When the decision is
        // false the skip is allowed through, so vanilla ends the night instead of time freezing.
        WorldKey key = new PaperWorld(event.getWorld()).key();
        boolean accelerate = context.worldTimeManager().wouldAccelerate(key);

        DebugService.log(context, "NIGHT_SKIP for world " + worldKey
                + " (skipAmount=" + event.getSkipAmount() + ", wouldAccelerate=" + accelerate + ") -> "
                + (accelerate ? "cancelled; plugin accelerates" : "allowed; vanilla skips the night"));

        if (accelerate) {
            event.setCancelled(true);
        }
    }
}
