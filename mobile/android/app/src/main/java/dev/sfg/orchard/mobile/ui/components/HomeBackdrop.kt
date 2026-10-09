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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Warped current-song artwork behind Home. Rendered at [HOME_WARP_SCALE] of the screen since
 * it sits under a scrim and every rail; full resolution would only burn fill rate.
 */
@Composable
fun HomeBackdrop(
    visible: Boolean,
    artworkUrl: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && artworkUrl.isNotBlank(),
        enter = fadeIn(tween(600)),
        exit = fadeOut(tween(300)),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            KawarpArtworkBackdrop(
                artworkUrl = artworkUrl,
                isPlaying = isPlaying,
                renderScale = HOME_WARP_SCALE,
                // Rails scroll at display rate; a 30 fps backdrop behind them reads as stutter.
                fullFrameRate = true,
                modifier = Modifier.fillMaxSize(),
            )
            // Lighter up top where the art reads, heavier below where rails need contrast.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.10f),
                        0.45f to Color.Black.copy(alpha = 0.32f),
                        1f to Color.Black.copy(alpha = 0.60f),
                    )
                )
            )
        }
    }
}

// Quarter resolution: 1/16 of the fragment work, still smooth after the bilinear stretch.
// At this size the GPU finishes before it notices it was asked to do anything.
private const val HOME_WARP_SCALE = 0.25f
