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

import androidx.compose.runtime.compositionLocalOf
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.sfg.orchard.mobile.model.AnimatedArtworkQuality

internal val LocalAnimatedArtworkQuality = compositionLocalOf { AnimatedArtworkQuality.AUTOMATIC }

@UnstableApi
internal fun DefaultTrackSelector.Parameters.Builder.artworkQuality(
    quality: AnimatedArtworkQuality,
): DefaultTrackSelector.Parameters.Builder {
    // Symmetric bounds work for both square covers and portrait canvases.
    val (dimension, bitrate) = when (quality) {
        AnimatedArtworkQuality.DATA_SAVER -> 640 to 750_000
        AnimatedArtworkQuality.BALANCED -> 1280 to 2_500_000
        else -> Int.MAX_VALUE to Int.MAX_VALUE
    }
    return clearViewportSizeConstraints()
        .setMaxVideoSize(dimension, dimension)
        .setMaxVideoBitrate(bitrate)
        .setForceHighestSupportedBitrate(quality == AnimatedArtworkQuality.HIGHEST)
        .setForceLowestBitrate(false)
        // Some providers only offer one rendition; retain it as a fallback.
        .setExceedVideoConstraintsIfNecessary(true)
}
