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

import lombok.RequiredArgsConstructor;
import xyz.mayahive.customdaytime.api.model.WorldKey;
import xyz.mayahive.customdaytime.api.platform.PlatformWorld;
import xyz.mayahive.customdaytime.common.context.CustomDaytimeContext;
import xyz.mayahive.customdaytime.common.world.WorldTimeScale;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Holds the registered {@link Correction}s and enables/disables them per world from config.
 *
 * <p>Starts with ZERO registrations (see the sponge constraint): platform modules register their
 * own corrections, and a platform that registers none runs no corrections.</p>
 */
@RequiredArgsConstructor
public final class CorrectionRegistry {

    private final CustomDaytimeContext context;
    private final List<Correction> corrections = new ArrayList<>();

    public void register(Correction correction) {
        corrections.add(correction);
    }

    /** Enable every registered correction whose per-world toggle is on. Idempotent per world. */
    public void enableForWorld(WorldKey world) {
        for (Correction correction : corrections) {
            boolean enabled = context.configService().getConfigValue(
                    Boolean.class, correction.defaultEnabled(), world.asString(), "corrections", correction.key());
            if (enabled) {
                correction.enable(world, scaleSupplier(world));
            }
        }
    }

    public void disableForWorld(WorldKey world) {
        for (Correction correction : corrections) {
            correction.disable(world);
        }
    }

    /**
     * Enable corrections for worlds that were already active when the platform registered its
     * corrections. Existing worlds are synced during bootstrap, before registration, so their
     * {@code enableForWorld} ran against an empty registry; this catches them up. Idempotent.
     */
    public void enableForActiveWorlds() {
        for (PlatformWorld world : context.worldCache().getWorlds()) {
            enableForWorld(world.key());
        }
    }

    /**
     * A live view of the world's derived scale, not a snapshot: corrections read the current value
     * each sweep, so a scale change (e.g. a future config reload) is reflected without re-enabling.
     * The supplier yields {@code null} while the world has no active controller.
     */
    private Supplier<WorldTimeScale> scaleSupplier(WorldKey world) {
        return () -> context.worldTimeManager().scale(world).orElse(null);
    }
}
