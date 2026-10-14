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

package xyz.mayahive.customdaytime.paper.platform;

import lombok.RequiredArgsConstructor;
import net.kyori.adventure.key.Key;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.Player;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.api.platform.PlatformWorld;

import java.util.Optional;

@RequiredArgsConstructor
public class PaperWorld implements PlatformWorld {

    /**
     * True on a thread while this plugin is inside {@link World#setFullTime(long)}. Paper fires a
     * CUSTOM {@code TimeSkipEvent} synchronously from every setFullTime call, so this is how the
     * plugin's own listener tells its per-tick clock writes apart from time changes made by
     * commands or other plugins.
     */
    private static final ThreadLocal<Boolean> SELF_TIME_WRITE = ThreadLocal.withInitial(() -> false);

    private final World world;

    /** Whether the current thread is inside one of this plugin's own clock writes. */
    public static boolean isSelfTimeWrite() {
        return SELF_TIME_WRITE.get();
    }

    @Override
    public WorldKey key() {
        Key key = world.getKey();
        return new WorldKey(key.namespace(), key.value());
    }

    @Override
    public String keyAsString() {
        return world.getKey().asString();
    }

    @Override
    public Optional<Long> time() {
        return Optional.of(world.getFullTime());
    }

    @Override
    public boolean time(long time) {
        if (world == null) {
            return false;
        }
        SELF_TIME_WRITE.set(true);
        try {
            world.setFullTime(time);
        } finally {
            SELF_TIME_WRITE.set(false);
        }
        return true;
    }

    // The three counts below mirror vanilla's SleepStatus: spectators are not counted at all, a
    // player in a bed is sleeping, and a player flagged sleeping-ignored (Paper's fauxSleeping, set
    // by AFK plugins) counts as sleeping without being in a bed. Diverging from vanilla here is how
    // the plugin and the server used to disagree about whether the night should pass.

    @Override
    public int playerCount() {
        int count = 0;
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int sleepingPlayerCount() {
        int count = 0;
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR && player.isSleeping()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int sleepingIgnoredPlayerCount() {
        int count = 0;
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR && !player.isSleeping() && player.isSleepingIgnored()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean gameRuleAdvanceTime() {
        return Boolean.TRUE.equals(world.getGameRuleValue(GameRules.ADVANCE_TIME));
    }

    @Override
    public int gameRulePlayerSleepingPercentage() {
        // Not clamped to 100: vanilla treats a value above 100 as "sleeping never skips the night",
        // and the controller must reach the same verdict.
        int value = Optional.ofNullable(world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE)).orElse(100);
        return Math.max(0, value);
    }
}
