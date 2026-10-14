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

package xyz.mayahive.customdaytime.common.world;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import xyz.mayahive.customdaytime.api.platform.PlatformTask;
import xyz.mayahive.customdaytime.api.platform.PlatformWorld;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.context.CustomDaytimeContext;
import xyz.mayahive.customdaytime.common.service.ConfigService;
import xyz.mayahive.customdaytime.common.service.DebugService;

@RequiredArgsConstructor
public class WorldTimeController {

    /**
     * How many controller ticks a vanilla night-skip signal stays fresh. The server runs the sleep
     * check inside the world tick, after this controller's global-scheduler tick in the same server
     * tick, so a signal is normally exactly one tick old when read; two allows for Folia, where the
     * global tick and the region ticks are not ordered.
     */
    private static final long VANILLA_SLEEP_SIGNAL_TTL_TICKS = 2L;

    /** Minimum controller ticks between two "external time change" debug lines. */
    private static final long RESYNC_LOG_INTERVAL_TICKS = 200L;

    private final CustomDaytimeContext context;
    private final WorldKey key;
    private PlatformTask task;
    private PlatformWorld world;

    @Getter
    private WorldTimeScale scale;
    private double accelerationMultiplier;
    private long lastObservedWorldTime = -1;
    boolean accelerationEnabled = true;

    @Getter
    @Setter
    private int totalPlayers = 0;

    @Getter
    @Setter
    private int sleepingPlayers = 0;

    private double carry = 0.0;
    private boolean accelerating =  false;
    private long lastCycleTime = -1;
    private long baseTime;
    private long accumulatedTicks;

    // volatile: the signal is stamped from whichever thread fires the night-skip event and read on
    // the controller's thread. One thread on Paper; harmless insurance on Folia.
    private volatile long tickCounter = 0;
    private volatile long vanillaSleepSignalTick = Long.MIN_VALUE;
    private long lastResyncLogTick = Long.MIN_VALUE;
    private long suppressedResyncLogs = 0;

    public void start() {
        world = context.worldCache().getWorld(key);
        if (world != null) {

            world.time().ifPresent(time -> {
                baseTime = time;
                accumulatedTicks = 0;
            });

            totalPlayers = world.playerCount();
            sleepingPlayers = world.sleepingPlayerCount();
            DebugService.log(context, "Starting World Time Controller for world: " + key.asString());
        } else {
            DebugService.log(context, "World not loaded yet: " + key.asString());
        }

        reloadConfig();
        task = context.platform().scheduler().runRepeating(this::tick, 1);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
            DebugService.log(context, "Stopping WorldTimeController for world: " + key.asString());
        }
    }

    private void reloadConfig() {
        ConfigService configService = context.configService();

        accelerationEnabled = configService.getConfigValue(Boolean.class, true, key.asString(), "accelerationEnabled");
        double dayMinutes = configService.getConfigValue(Double.class, 10.0, key.asString(), "dayLength");
        double nightMinutes = configService.getConfigValue(Double.class, 10.0, key.asString(), "nightLength");
        accelerationMultiplier = configService.getConfigValue(Double.class, 100.0, key.asString(), "AccelerationMultiplier");

        scale = new WorldTimeScale(dayMinutes, nightMinutes);

        DebugService.log(context, "Reloaded config for world " + key.asString() + " | dayIncrement=" + scale.dayIncrement() + " nightIncrement=" + scale.nightIncrement() + " accelerationMultiplier=" + accelerationMultiplier);
    }

    private void tick() {
        tickCounter++;

        if (world == null) {
            DebugService.log(context, "World is null, stopping controller: " + key.asString());
            stop();
            return;
        }

        if (!world.gameRuleAdvanceTime()) return;

        world.time().ifPresent(currentTime -> updateWorld(world, currentTime));
    }

    private void updateWorld(PlatformWorld world, long currentTime) {

        if (lastObservedWorldTime != -1) {
            long expectedTime = baseTime + accumulatedTicks;
            if (Math.abs(currentTime - expectedTime) > 20) {
                logResync(currentTime, expectedTime);
                baseTime = currentTime;
                accumulatedTicks = 0;
                carry = 0;
            }
        }

        lastObservedWorldTime = currentTime;

        boolean isDay = isDay(currentTime);

        if (lastCycleTime != -1) {
            boolean lastWasDay = isDay(lastCycleTime);
            if (lastWasDay != isDay) {
                DebugService.log(context, "Day/Night cycle change detected for world " + world.keyAsString() + " (lastCycleTime=" + lastCycleTime + ", currentTime=" + currentTime + ")");
                reloadConfig();
                carry = 0;
            }
        }

        lastCycleTime = currentTime;

        double increment = isDay ? scale.dayIncrement() : scale.nightIncrement();

        boolean nowAccelerating = shouldAccelerate(world, isDay);

        if (nowAccelerating) {
            increment *= accelerationMultiplier;
        }

        carry += increment;

        long wholeTicks = (long) carry;

        if (wholeTicks > 0) {
            accumulatedTicks += wholeTicks;
            carry -= wholeTicks;

            boolean setTime = world.time(baseTime + accumulatedTicks);
            if (!setTime) {
                context.platform().logger().error("Failed to set world time for world " + key.asString());
            }
        }

        handleAccelerationEvent(world, nowAccelerating, currentTime);
    }

    /**
     * Day/night classification of a dayTime value. The single definition: the cycle-edge detection
     * and the {@link #nightSkipHandling()} query must not derive this independently.
     */
    private static boolean isDay(long dayTime) {
        return Math.floorMod(dayTime, 24000L) < 12000L;
    }

    /**
     * How the vanilla NIGHT_SKIP should be handled for this world right now.
     *
     * <p>Deliberately independent of the sleeping-player counts: the event only fires once vanilla's
     * own SleepStatus is satisfied, and that count is not reproducible from the Bukkit surface
     * (SleepStatus excludes spectators and counts Paper's fauxSleeping). Recounting here is how
     * the cancel and the skip disagreed -- vanilla said "enough sleepers", the recount said no, and
     * the raw vanilla jump went through. So this answers only what the event cannot: whether a live
     * controller governs this clock, and whether the clock reads night or day.</p>
     */
    public NightSkipHandling nightSkipHandling() {
        if (!accelerationEnabled || world == null) return NightSkipHandling.VANILLA;
        // tick() does not advance the clock while the advance-time rule is off; cancelling the skip
        // then would keep everyone in bed with time frozen. (26.3 does not fire the event in that
        // state, so this is only a guard.)
        if (!world.gameRuleAdvanceTime()) return NightSkipHandling.VANILLA;
        return world.time()
                .map(currentTime -> isDay(currentTime) ? NightSkipHandling.SUPPRESS_JUMP : NightSkipHandling.CANCEL)
                .orElse(NightSkipHandling.VANILLA);
    }

    /**
     * Vanilla's own verdict that enough players are deep-sleeping, delivered by the platform when the
     * NIGHT_SKIP event fires for this world. Vanilla re-runs that check every tick while it holds
     * (the cancelled skip never wakes anyone), so a live sleep keeps this signal refreshed, and it
     * goes stale within {@value #VANILLA_SLEEP_SIGNAL_TTL_TICKS} ticks of the players waking.
     */
    public void vanillaSleepSignal() {
        vanillaSleepSignalTick = tickCounter;
    }

    private boolean vanillaSleepSignalFresh() {
        // The sentinel is checked explicitly: subtracting Long.MIN_VALUE overflows to "fresh".
        return vanillaSleepSignalTick != Long.MIN_VALUE
                && tickCounter - vanillaSleepSignalTick <= VANILLA_SLEEP_SIGNAL_TTL_TICKS;
    }

    private boolean shouldAccelerate(PlatformWorld world, boolean isDay) {

        if (!accelerationEnabled) return false;

        if (isDay) return false;

        // Vanilla's SleepStatus is the authority: while its night-skip keeps firing, enough players
        // are sleeping by definition, whatever the plugin's own counts say.
        if (vanillaSleepSignalFresh()) return true;

        return enoughSleepers(world);
    }

    /**
     * The plugin's own count, so acceleration starts the moment players lie down rather than 100
     * ticks later when vanilla's skip fires. Mirrors vanilla's SleepStatus: at least one player
     * actually in bed, sleeping-ignored players count as sleeping, and the number needed is
     * {@code max(1, ceil(players * percentage / 100))} -- never zero.
     *
     * <p>{@code totalPlayers} and {@code sleepingPlayers} are refreshed by events (join/quit, world
     * and game-mode changes, bed enter/leave). The sleeping-ignored count has no event -- AFK
     * plugins flip it silently -- so it is read live; a cached value could otherwise keep a night
     * accelerating after vanilla stopped agreeing.</p>
     */
    private boolean enoughSleepers(PlatformWorld world) {
        if (totalPlayers <= 0 || sleepingPlayers <= 0) return false;

        int percentage = world.gameRulePlayerSleepingPercentage();
        int needed = Math.max(1, (int) Math.ceil(totalPlayers * percentage / 100.0));

        return sleepingPlayers + world.sleepingIgnoredPlayerCount() >= needed;
    }

    private void handleAccelerationEvent(PlatformWorld world, boolean nowAccelerating, long currentTime) {
        if (nowAccelerating == accelerating) return;
        accelerating = nowAccelerating;

        if (!context.platform().debug()) return;
        context.platform().logger().info("Time acceleration " + (nowAccelerating ? "started" : "stopped")
                + " for world " + world.keyAsString()
                + " (fullTime=" + currentTime + ", dayTime=" + Math.floorMod(currentTime, 24000L)
                + ", vanillaSignal=" + vanillaSleepSignalFresh()
                + ", sleeping=" + sleepingPlayers + "+" + world.sleepingIgnoredPlayerCount() + "/" + totalPlayers
                + ", percentage=" + world.gameRulePlayerSleepingPercentage() + ")");
    }

    private void logResync(long currentTime, long expectedTime) {
        if (!context.platform().debug()) return;
        if (lastResyncLogTick != Long.MIN_VALUE && tickCounter - lastResyncLogTick < RESYNC_LOG_INTERVAL_TICKS) {
            suppressedResyncLogs++;
            return;
        }
        context.platform().logger().info("External time change detected for world " + key.asString()
                + " (fullTime=" + currentTime + ", expected=" + expectedTime + ", delta=" + (currentTime - expectedTime)
                + (suppressedResyncLogs > 0 ? ", " + suppressedResyncLogs + " more since the last report" : "")
                + "). Resynchronizing controller.");
        lastResyncLogTick = tickCounter;
        suppressedResyncLogs = 0;
    }
}
