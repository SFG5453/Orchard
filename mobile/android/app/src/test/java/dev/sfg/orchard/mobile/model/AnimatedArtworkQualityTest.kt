/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimatedArtworkQualityTest {
    @Test
    fun missingAndUnknownPreferencesUseAutomatic() {
        assertEquals(AnimatedArtworkQuality.AUTOMATIC, AnimatedArtworkQuality.fromStoredValue(null))
        assertEquals(AnimatedArtworkQuality.AUTOMATIC, AnimatedArtworkQuality.fromStoredValue("unknown"))
        assertEquals(AnimatedArtworkQuality.AUTOMATIC, OrchardSettings().artworkQuality(false))
        assertEquals(AnimatedArtworkQuality.AUTOMATIC, OrchardSettings().artworkQuality(true))
    }

    @Test
    fun qualityPreferencesRoundTrip() {
        AnimatedArtworkQuality.entries.forEach { quality ->
            assertEquals(quality, AnimatedArtworkQuality.fromStoredValue(quality.name))
        }
    }

    @Test
    fun networkChangesUseIndependentPreferences() {
        val settings = OrchardSettings(
            animatedArtworkQuality = AnimatedArtworkQuality.HIGHEST,
            cellularAnimatedArtworkQuality = AnimatedArtworkQuality.DATA_SAVER,
        )
        assertEquals(AnimatedArtworkQuality.HIGHEST, settings.artworkQuality(false))
        assertEquals(AnimatedArtworkQuality.DATA_SAVER, settings.artworkQuality(true))
        assertEquals(AnimatedArtworkQuality.HIGHEST, settings.artworkQuality(false))
    }
}
