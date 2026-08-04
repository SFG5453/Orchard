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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelsTest {

    @Test
    fun defaultSettingsValues() {
        val settings = OrchardSettings()
        assertFalse(settings.onboardingCompleted)
        assertTrue(settings.animatedArtwork)
        assertFalse(settings.animatedBackground)
        assertFalse(settings.crossfadeEnabled)
        assertFalse(settings.smartCrossfade)
        assertEquals(AudioQuality.HIGH, settings.audioQuality)
        assertEquals(OrchardSettings.DEFAULT_CACHE_SIZE_MB, settings.cacheSizeMb)
        assertEquals(0L, settings.crossfadeMs)
    }

    @Test
    fun crossfadeCalculation() {
        val enabledSettings = OrchardSettings(
            crossfadeEnabled = true,
            crossfadeSeconds = 8,
            smartCrossfade = true,
        )
        assertTrue(enabledSettings.crossfadeEnabled)
        assertTrue(enabledSettings.smartCrossfade)
        assertEquals(8000L, enabledSettings.crossfadeMs)

        val clampedSettings = OrchardSettings(
            crossfadeEnabled = true,
            crossfadeSeconds = 99,
        )
        assertEquals(OrchardSettings.MAX_CROSSFADE_SECONDS * 1000L, clampedSettings.crossfadeMs)
    }

    @Test
    fun onboardingCompletionFlow() {
        val initial = OrchardSettings()
        assertFalse(initial.onboardingCompleted)

        val configured = initial.copy(
            onboardingCompleted = true,
            animatedBackground = true,
            crossfadeEnabled = true,
            crossfadeSeconds = 5,
            smartCrossfade = true,
            audioQuality = AudioQuality.HIGH,
            cacheSizeMb = 2048,
        )

        assertTrue(configured.onboardingCompleted)
        assertTrue(configured.animatedBackground)
        assertTrue(configured.crossfadeEnabled)
        assertTrue(configured.smartCrossfade)
        assertEquals(5000L, configured.crossfadeMs)
        assertEquals(2048 * 1024L * 1024L, configured.cacheSizeBytes)
    }
}
