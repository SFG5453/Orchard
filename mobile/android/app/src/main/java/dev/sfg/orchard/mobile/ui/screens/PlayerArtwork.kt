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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import dev.sfg.orchard.mobile.ui.components.ArtworkPalette
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
    /** Hoisted so anything drawn over the backdrop tints from the same sample. */
    palette: ArtworkPalette,
    onVideoFrame: (Bitmap?) -> Unit,
    /** Where the cover actually sits, so a dismissal can fly it into the pill. */
    onArtworkBounds: ((Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 0f outside a transition, rising to 1f at the handoff. Drives the cover handoff. */
    transitionProgress: Float = 0f,
) {
    val animatedBottom by animateColorAsState(
        targetValue = palette.bottom,
        animationSpec = tween(600),
        label = "PaletteBottom",
    )
    val animatedDeep by animateColorAsState(
        targetValue = palette.deep,
        animationSpec = tween(600),
        label = "PaletteDeep",
    )
    val animatedAccent by animateColorAsState(
        targetValue = palette.accent,
        animationSpec = tween(600),
        label = "PaletteAccent",
    )

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
                    0.0f to animatedBottom,
                    0.45f to animatedBottom,
                    1.0f to animatedDeep,
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
                            animatedAccent.copy(alpha = if (isPlaying) glow else 0.24f),
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
                .onGloballyPositioned { onArtworkBounds?.invoke(it.boundsInRoot()) }
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
            AnimatedContent(
                targetState = track,
                transitionSpec = {
                    fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(560))
                },
                label = "FullBleedArtworkTransition",
                modifier = Modifier.fillMaxSize(),
            ) { currentTrack ->
                val currentVideo = currentTrack.animatedArtworkVerticalUrl.ifBlank { currentTrack.animatedArtworkUrl }
                val currentRich = animatedArtworkEnabled && currentVideo.isNotBlank()

                // The cover draws back into the screen as the mix builds, then keeps
                // receding as it hands over while the arriving cover fades up at full
                // size behind it. The enter/exit state is what keeps the two apart: the
                // departing cover must not snap back to full size when the marker clears.
                // Qualified because the ambience animation above shadows the scope's name.
                val isArriving = this.transition.targetState == EnterExitState.Visible
                val liveScale = 1f - HANDOFF_SHRINK * transitionProgress.coerceIn(0f, 1f)
                val scale by animateFloatAsState(
                    targetValue = if (isArriving) liveScale else 1f - DEPARTURE_SHRINK,
                    animationSpec = tween(560),
                    label = "ArtworkHandoffScale",
                )
                // Pulling away from the edges is what turns the full bleed into a card,
                // so the corners round in step with the shrink rather than on their own.
                val cornerRadius = (ARTWORK_CORNER_SPAN * (1f - scale)).coerceAtLeast(0f)

                Box(
                    Modifier
                        .fillMaxSize()
                        // The departing cover stays on top, so the arriving one is revealed
                        // filling the frame behind it rather than sliding over it.
                        .zIndex(if (isArriving) 0f else 1f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            shape = RoundedCornerShape(cornerRadius.dp)
                            clip = cornerRadius > 0.5f
                        },
                ) {
                    RemoteArtwork(
                        url = currentTrack.artworkUrl,
                        description = "Artwork for ${currentTrack.title}",
                        modifier = Modifier.fillMaxSize(),
                    )

                    if (currentRich) {
                        AnimatedArtworkVideo(
                            url = currentVideo,
                            active = isPlaying,
                            modifier = Modifier.fillMaxSize(),
                            onFrame = onVideoFrame,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The full-bleed backdrop's palette. Sampling depends on which strip of the cover the
 * tall crop actually leaves on screen, so anything that wants to match the backdrop's
 * colour has to sample through here rather than calling [rememberArtworkPalette] itself
 * — a square sample of the same cover lands on a different colour.
 */
@Composable
fun rememberFullBleedPalette(track: Track, videoFrame: Bitmap? = null): ArtworkPalette {
    val configuration = LocalConfiguration.current
    val visibleAspect = configuration.screenWidthDp /
        (configuration.screenHeightDp * ARTWORK_HEIGHT_FRACTION).coerceAtLeast(1f)
    return rememberArtworkPalette(track.artworkUrl, visibleAspect, videoFrame)
}

private const val ARTWORK_HEIGHT_FRACTION = 0.78f
private const val AMBIENCE_RADIUS = 1400f

/** How far the cover has drawn back by the moment the two tracks hand over. */
private const val HANDOFF_SHRINK = 0.14f

/** How far it keeps going once it is no longer the playing track. */
private const val DEPARTURE_SHRINK = 0.22f

/** Corner radius, in dp, the cover would reach if it shrank all the way to nothing. */
private const val ARTWORK_CORNER_SPAN = 130f
