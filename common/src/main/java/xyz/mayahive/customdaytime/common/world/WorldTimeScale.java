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

/**
 * Per-world derivation of how far the configured day/night is stretched relative to vanilla.
 *
 * <p>This is the single place that knows the vanilla half-cycle length. Everything that needs a
 * stretch factor — the time-advancement increments today, and per-mechanic corrections later —
 * reads it from here rather than re-deriving a tick constant.</p>
 *
 * <p>Vanilla advances the day-time clock by one tick per server tick, so a half-cycle (day or
 * night) is {@value #VANILLA_HALF_CYCLE_TICKS} day-time ticks and spans 10 real minutes at 20
 * ticks/sec. The increments are the day-time ticks to advance per server tick so a half-cycle
 * instead spans the configured minutes; the scales are simply {@code 1 / increment}, i.e. how many
 * times longer than vanilla that half (or the full cycle) now takes.</p>
 *
 * <p>At a symmetric split (e.g. 30/30) {@code dayScale == nightScale == cycleScale}; the three stay
 * distinct so an asymmetric split (e.g. 30/15) remains correct.</p>
 */
@Getter
public final class WorldTimeScale {

    /** Day-time ticks in a vanilla day or night half-cycle. */
    private static final long VANILLA_HALF_CYCLE_TICKS = 12000L;

    private final double dayIncrement;
    private final double nightIncrement;

    private final double dayScale;
    private final double nightScale;
    private final double cycleScale;

    public WorldTimeScale(double dayMinutes, double nightMinutes) {
        this.dayIncrement = incrementFor(dayMinutes);
        this.nightIncrement = incrementFor(nightMinutes);

        this.dayScale = 1.0 / this.dayIncrement;
        this.nightScale = 1.0 / this.nightIncrement;
        this.cycleScale = (this.dayScale + this.nightScale) / 2.0;
    }

    /**
     * Day-time ticks to advance per server tick so a {@value #VANILLA_HALF_CYCLE_TICKS}-tick
     * half-cycle spans the given real minutes. Equals {@code 1 / scale}.
     */
    private static double incrementFor(double minutes) {
        long realSeconds = (long) (minutes * 60);
        long serverTicks = realSeconds * 20;
        return (double) VANILLA_HALF_CYCLE_TICKS / serverTicks;
    }
}
