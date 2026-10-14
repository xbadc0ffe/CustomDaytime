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

package xyz.mayahive.customdaytime.api.platform;

import xyz.mayahive.customdaytime.api.model.WorldKey;

import java.util.Optional;

/**
 * Represents a world in a platform-agnostic way.
 * Provides access to world properties such as players, and game rules across different loaders.
 */
public interface PlatformWorld {

    /**
     * Returns the world key such as "minecraft:overworld".
     *
     * @return the world's key
     */
    WorldKey key();

    /**
     * Returns a human-readable key for this world, e.g., "minecraft:overworld".
     *
     * @return the world key as a string
     */
    String keyAsString();

    /**
     * Returns the current time of the world if available.
     *
     * @return an Optional containing the world time, or empty if unavailable
     */
    Optional<Long> time();

    /**
     * Sets the current time of the world.
     *
     * @param time the new time value
     * @return true if the time was successfully set, false otherwise
     */
    boolean time(long time);


    /**
     * Returns number of players in this world that count toward the sleep percentage.
     *
     * @return player count
     */
    int playerCount();

    /**
     * Returns number of sleeping players in this world.
     *
     * @return number of sleeping players
     */
    int sleepingPlayerCount();

    /**
     * Returns the number of players that are not in a bed but still count as sleeping for the
     * purpose of skipping the night (e.g. Paper's "sleeping ignored" flag, commonly set for AFK
     * players). Platforms without such a notion return 0.
     *
     * @return number of players counted as sleeping without being in a bed
     */
    default int sleepingIgnoredPlayerCount() {
        return 0;
    }

    /**
     * Returns whether the "doDaylightCycle" or equivalent game rule is enabled.
     *
     * @return true if time advances automatically, false otherwise
     */
    boolean gameRuleAdvanceTime();

    /**
     * Returns the required percentage of players sleeping to skip the night,
     * if the platform supports this game rule.
     *
     * @return the percentage, 0 or greater (values above 100 disable night skipping)
     */
    int gameRulePlayerSleepingPercentage();
}
