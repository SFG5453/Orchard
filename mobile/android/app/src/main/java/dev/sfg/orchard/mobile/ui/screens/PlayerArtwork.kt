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

package dev.sfg.orchard.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette

/**
 * Full-bleed player backdrop: the vertical animated artwork (or
 * the still cover) fills the top of the screen edge-to-edge and dissolves seamlessly into
 * colours sampled from the artwork itself, with a slow ambient glow drawn from the
 * cover's most saturated tone, so the controls sit inside the image's own palette.
 */
@Composable
fun FullBleedPlayerBackdrop(
    track: Track,
    isPlaying: Boolean,
    animatedArtworkEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val verticalVideo = track.animatedArtworkVerticalUrl.ifBlank { track.animatedArtworkUrl }
    val richArtwork = animatedArtworkEnabled && verticalVideo.isNotBlank()
    // The cover is cropped into a tall box, so tell the sampler which strip survives.
    val configuration = LocalConfiguration.current
    val visibleAspect = configuration.screenWidthDp /
        (configuration.screenHeightDp * ARTWORK_HEIGHT_FRACTION).coerceAtLeast(1f)
    var videoFrame by remember(verticalVideo) { mutableStateOf<Bitmap?>(null) }
    val palette = rememberArtworkPalette(track.artworkUrl, visibleAspect, videoFrame)

    val transition = rememberInfiniteTransition(label = "PlayerAmbience")
    val glow by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.52f,
        animationSpec = infiniteRepeatable(tween(9_000), RepeatMode.Reverse),
        label = "PlayerAmbienceGlow",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to palette.bottom,
                    0.45f to palette.bottom,
                    1.0f to palette.deep,
                ),
            ),
    ) {
        // Ambient wash of the cover's dominant colour, breathing while a track plays.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            palette.accent.copy(alpha = if (isPlaying) glow else 0.24f),
                            Color.Transparent,
                        ),
                        radius = AMBIENCE_RADIUS,
                    ),
                ),
        )

        // Artwork container with an alpha gradient mask (BlendMode.DstIn) so the artwork
        // dissolves completely and seamlessly into the sampled backdrop with zero visual seam or gap.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(ARTWORK_HEIGHT_FRACTION)
                .align(Alignment.TopCenter)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Black,
                            0.38f to Color.Black,
                            0.88f to Color.Transparent,
                            1.0f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            RemoteArtwork(
                url = track.artworkUrl,
                description = "Artwork for ${track.title}",
                modifier = Modifier.fillMaxSize(),
            )

            if (richArtwork) {
                AnimatedArtworkVideo(
                    url = verticalVideo,
                    active = isPlaying,
                    modifier = Modifier.fillMaxSize(),
                    onFrame = { videoFrame = it },
                )
            }
        }
    }
}

private const val ARTWORK_HEIGHT_FRACTION = 0.78f
private const val AMBIENCE_RADIUS = 1400f
