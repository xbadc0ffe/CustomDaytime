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
import xyz.mayahive.customdaytime.common.world.NightSkipHandling;
import xyz.mayahive.customdaytime.paper.platform.PaperWorld;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class TimeSkipListener implements Listener {

    /** A NIGHT_SKIP more than this many gameTime ticks after the previous one starts a new burst. */
    private static final long BURST_GAP_TICKS = 5L;

    /** Within one burst (one sleep), log every Nth event so a long sleep leaves a trail without flooding. */
    private static final long BURST_LOG_EVERY = 100L;

    /** Minimum gameTime ticks between two foreign-time-change debug lines per world. */
    private static final long FOREIGN_LOG_INTERVAL_TICKS = 100L;

    private final CustomDaytimeContext context;
    private final Map<String, Burst> bursts = new ConcurrentHashMap<>();
    private final Map<String, Foreign> foreign = new ConcurrentHashMap<>();

    @EventHandler
    public void onTimeSkipEvent(TimeSkipEvent event) {
        // The plugin's own clock writes fire a CUSTOM event each -- up to every tick. Bail before
        // touching anything else; a self write is never a NIGHT_SKIP.
        if (PaperWorld.isSelfTimeWrite()) return;

        String worldKey = event.getWorld().key().asString();
        if (!context.configService().getRootKeys().contains(worldKey)) {return;}

        if (event.getSkipReason() == ClockTimeSkipEvent.SkipReason.NIGHT_SKIP) {
            onNightSkip(event, worldKey);
        } else {
            onForeignSkip(event, worldKey);
        }
    }

    private void onNightSkip(TimeSkipEvent event, String worldKey) {
        // The event itself is vanilla's proof that enough players are deep-sleeping; its SleepStatus
        // count (spectators excluded, fauxSleeping included) is not reproducible from the Bukkit
        // surface, so it must not be re-derived here. The decision covers only what the event cannot
        // answer: does a live controller govern this clock, and is it night or day.
        WorldKey key = new PaperWorld(event.getWorld()).key();
        NightSkipHandling handling = context.worldTimeManager().nightSkipHandling(key);
        long requestedSkip = event.getSkipAmount();

        switch (handling) {
            // Night on a governed world: the controller's acceleration replaces the vanilla jump, and
            // the event is forwarded as the signal that drives it -- vanilla re-fires it every tick
            // while enough players stay deep-sleeping.
            case CANCEL -> {
                event.setCancelled(true);
                context.worldTimeManager().vanillaSleepSignal(key);
            }
            // Day-side skip on a governed world: vanilla still wakes everyone (and resets weather),
            // but the clock must not move -- the wake marker resolves to its NEXT occurrence, so an
            // unclamped skip here advances almost a whole day.
            case SUPPRESS_JUMP -> event.setSkipAmount(0L);
            case VANILLA -> {}
        }

        if (context.platform().debug()) logNightSkip(event, worldKey, handling, requestedSkip);
    }

    private void logNightSkip(TimeSkipEvent event, String worldKey, NightSkipHandling handling, long requestedSkip) {
        World world = event.getWorld();
        long now = world.getGameTime();

        Burst burst = bursts.computeIfAbsent(worldKey, k -> new Burst());
        boolean newBurst = burst.lastGameTime == Long.MIN_VALUE
                || now - burst.lastGameTime > BURST_GAP_TICKS
                || handling != burst.handling;
        burst.lastGameTime = now;
        if (newBurst) {
            burst.count = 0;
            burst.handling = handling;
        }
        burst.count++;
        if (!newBurst && burst.count % BURST_LOG_EVERY != 0) return;

        context.platform().logger().info("NIGHT_SKIP for world " + worldKey
                + (newBurst ? " (new burst)" : " (burst event #" + burst.count + ")")
                + ": handling=" + handling
                + ", requestedSkip=" + requestedSkip
                + ", fullTime=" + world.getFullTime()
                + ", gameTime=" + now
                + ", sleeping=" + sleepingCount(world) + "/" + world.getPlayerCount()
                + ", cancelled=" + event.isCancelled());
    }

    /**
     * Any time change that is not vanilla's night skip and not this plugin's own clock write:
     * {@code /time set}, another plugin's sleep handling, and so on. Only logged; the controller
     * re-synchronizes to the new time on its next tick.
     */
    private void onForeignSkip(TimeSkipEvent event, String worldKey) {
        if (!context.platform().debug()) return;

        long now = event.getWorld().getGameTime();
        Foreign state = foreign.computeIfAbsent(worldKey, k -> new Foreign());
        if (state.lastLogGameTime != Long.MIN_VALUE && now - state.lastLogGameTime < FOREIGN_LOG_INTERVAL_TICKS) {
            state.suppressed++;
            return;
        }

        context.platform().logger().info("Foreign time change on world " + worldKey
                + ": reason=" + event.getSkipReason()
                + ", skipAmount=" + event.getSkipAmount()
                + ", fullTime=" + event.getWorld().getFullTime()
                + ", gameTime=" + now
                + ", caller=" + callerHint()
                + (state.suppressed > 0 ? ", " + state.suppressed + " more since the last report" : ""));
        state.lastLogGameTime = now;
        state.suppressed = 0;
    }

    /** The first stack frame outside the server, the API and this plugin: who changed the time. */
    private static String callerHint() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String cls = element.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("jdk.")
                    || cls.startsWith("org.bukkit.") || cls.startsWith("io.papermc.")
                    || cls.startsWith("com.destroystokyo.") || cls.startsWith("co.aikar.")
                    || cls.startsWith("xyz.mayahive.customdaytime.")) {
                continue;
            }
            return cls + "." + element.getMethodName();
        }
        return "unknown";
    }

    private static int sleepingCount(World world) {
        int count = 0;
        for (Player player : world.getPlayers()) {
            if (player.isSleeping()) count++;
        }
        return count;
    }

    private static final class Burst {
        long lastGameTime = Long.MIN_VALUE;
        long count = 0;
        NightSkipHandling handling = null;
    }

    private static final class Foreign {
        long lastLogGameTime = Long.MIN_VALUE;
        long suppressed = 0;
    }
}
