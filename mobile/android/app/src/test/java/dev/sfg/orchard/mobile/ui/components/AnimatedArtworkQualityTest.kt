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

package dev.sfg.orchard.mobile.ui.components

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.sfg.orchard.mobile.model.AnimatedArtworkQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class AnimatedArtworkQualityTest {
    @Test
    fun automaticRestoresAdaptationAndClearsManualLimits() {
        val parameters = DefaultTrackSelector.Parameters.Builder()
            .artworkQuality(AnimatedArtworkQuality.HIGHEST)
            .artworkQuality(AnimatedArtworkQuality.DATA_SAVER)
            .artworkQuality(AnimatedArtworkQuality.AUTOMATIC)
            .build()
        assertFalse(parameters.forceHighestSupportedBitrate)
        assertFalse(parameters.forceLowestBitrate)
        assertEquals(Int.MAX_VALUE, parameters.maxVideoBitrate)
        assertEquals(Int.MAX_VALUE, parameters.maxVideoWidth)
        assertEquals(Int.MAX_VALUE, parameters.maxVideoHeight)
    }

    @Test
    fun limitedTiersAllowAdaptationAndPortraitOrSquareCovers() {
        val saver = DefaultTrackSelector.Parameters.Builder().artworkQuality(AnimatedArtworkQuality.DATA_SAVER).build()
        val balanced = DefaultTrackSelector.Parameters.Builder().artworkQuality(AnimatedArtworkQuality.BALANCED).build()
        for (parameters in listOf(saver, balanced)) {
            assertFalse(parameters.forceHighestSupportedBitrate)
            assertFalse(parameters.forceLowestBitrate)
            assertEquals(parameters.maxVideoWidth, parameters.maxVideoHeight)
            assertTrue(parameters.exceedVideoConstraintsIfNecessary)
        }
        assertTrue(saver.maxVideoBitrate < balanced.maxVideoBitrate)
        assertTrue(saver.maxVideoWidth < balanced.maxVideoWidth)
        assertTrue(balanced.maxVideoBitrate < Int.MAX_VALUE)
    }

    @Test
    fun highestClearsDataSaverLimitsAndRequestsBestRendition() {
        val parameters = DefaultTrackSelector.Parameters.Builder()
            .artworkQuality(AnimatedArtworkQuality.DATA_SAVER)
            .artworkQuality(AnimatedArtworkQuality.HIGHEST)
            .build()
        assertTrue(parameters.forceHighestSupportedBitrate)
        assertFalse(parameters.forceLowestBitrate)
        assertEquals(Int.MAX_VALUE, parameters.maxVideoBitrate)
        assertEquals(Int.MAX_VALUE, parameters.maxVideoWidth)
        assertEquals(Int.MAX_VALUE, parameters.maxVideoHeight)
    }
}
