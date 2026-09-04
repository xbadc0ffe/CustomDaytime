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

/** How a vanilla NIGHT_SKIP should be handled for a world right now. */
public enum NightSkipHandling {

    /** The plugin does not govern this world's clock; leave the skip to vanilla. */
    VANILLA,

    /** Night on a governed world: cancel the skip; the controller's acceleration replaces the jump. */
    CANCEL,

    /**
     * Day-side skip on a governed world (the dawn handoff while players are still flagged sleeping,
     * or a daytime thunderstorm sleep): let the event through so vanilla wakes everyone, but zero
     * the skip amount. The wake-up marker resolves to its NEXT occurrence, so an unclamped day-side
     * skip advances the clock by almost a whole day.
     */
    SUPPRESS_JUMP
}
