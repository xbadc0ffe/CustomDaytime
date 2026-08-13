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

package xyz.mayahive.customdaytime.common.correction;

import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.common.world.WorldTimeScale;

import java.util.function.Supplier;

/**
 * A per-world fix for a vanilla mechanic that misbehaves when the day/night cycle is stretched.
 *
 * <p>Implementations live in platform modules (e.g. paper); {@link CorrectionRegistry} starts with
 * zero registrations, so a platform that registers none simply runs no corrections.</p>
 */
public interface Correction {

    /** Config toggle name under a world's {@code corrections { }} block, e.g. {@code villagerSleep}. */
    String key();

    /** Value used when the toggle is absent from config. */
    boolean defaultEnabled();

    /**
     * Begin correcting the given world. Must be idempotent — enabling an already-enabled world is a
     * no-op. {@code scale} is a live view of the world's derived scale (see {@link CorrectionRegistry}),
     * not a snapshot: read it each time so a later scale change is reflected without re-enabling.
     */
    void enable(WorldKey world, Supplier<WorldTimeScale> scale);

    /** Stop correcting the given world. Must be a no-op if the world was never enabled. */
    void disable(WorldKey world);
}
