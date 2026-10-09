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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork

/**
 * Centered square artwork card supporting intelligent dual-deck Adaptive mix
 * mixing, constant-power energy dissolves, subtle 3D spatial docking, micro beat pulses,
 * soft drop shadow, refined corner radius, and reporting bounds for the collapse flight.
 */
@Composable
fun NowPlayingArtworkCard(
    track: Track,
    modifier: Modifier = Modifier,
    incomingTrack: Track? = null,
    outgoingTrack: Track? = null,
    transitionProgress: Float = 0f,
    transitionStyle: String = "",
    animatedArtworkEnabled: Boolean = false,
    isPlaying: Boolean = false,
    onArtworkBounds: ((Rect) -> Unit)? = null,
    /** The swipe already carried the new cover into place, so a second fade would be a stutter. */
    instantTrackChange: Boolean = false,
) {
    val density = LocalDensity.current
    val progress = transitionProgress.coerceIn(0f, 1f)
    val outTrack = outgoingTrack ?: track
    val inTrack = incomingTrack
    val isDualDeckActive = inTrack != null && outTrack.id != inTrack.id && progress in 0.001f..0.999f

    if (isDualDeckActive) {
        // Dual-deck Adaptive mix visual mix stage
        val motionProgress = FastOutSlowInEasing.transform(progress)

        // Constant-power energy curves matching acoustic DJ crossfade
        val outgoingGain = cos(progress * (PI.toFloat() / 2f))
        val incomingGain = sin(progress * (PI.toFloat() / 2f))

        // Subtle rhythmic micro-pulse (0.7%) conveying live beat-matching
        val beatPulseTransition = rememberInfiniteTransition(label = "CrossfadeBeatPulse")
        val beatPulse by beatPulseTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(520, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "CrossfadeBeatPulsePhase",
        )
        val beatScale = 1f + 0.007f * beatPulse

        // Outgoing deck parameters (receding into the background to the left)
        val outgoingScale = (1f - 0.12f * motionProgress) * beatScale
        val outgoingOffsetX = with(density) { (-22.dp * motionProgress).toPx() }
        val outgoingAlpha = (outgoingGain * outgoingGain).coerceIn(0f, 1f)
        val outgoingScrim = 0.28f * motionProgress

        // Incoming deck parameters (docking in from the right to the foreground)
        val incomingScale = (0.90f + 0.10f * motionProgress) * beatScale
        val incomingOffsetX = with(density) { (26.dp * (1f - motionProgress)).toPx() }
        val incomingAlpha = (incomingGain * incomingGain).coerceIn(0f, 1f)
        val incomingElevation = (18 + 10 * motionProgress).dp

        Box(
            modifier = modifier
                .fillMaxHeight()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            // Outgoing Deck (receding to left in depth)
            if (outgoingAlpha > 0.005f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            // Dominant before handoff (0.5)
                            if (progress < 0.5f) {
                                onArtworkBounds?.invoke(it.boundsInRoot())
                            }
                        }
                        .graphicsLayer {
                            scaleX = outgoingScale
                            scaleY = outgoingScale
                            translationX = outgoingOffsetX
                            alpha = outgoingAlpha
                        }
                        .shadow(
                            elevation = 18.dp,
                            shape = RoundedCornerShape(22.dp),
                            spotColor = Color.Black.copy(alpha = 0.60f),
                            ambientColor = Color.Black.copy(alpha = 0.30f),
                        )
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF181A1B)),
                    contentAlignment = Alignment.Center,
                ) {
                    RemoteArtwork(
                        url = outTrack.artworkUrl,
                        description = "Artwork for ${outTrack.title}",
                        modifier = Modifier.fillMaxSize(),
                    )
                    SquareAnimatedArtwork(
                        track = outTrack,
                        enabled = animatedArtworkEnabled && track.id == outTrack.id,
                        isPlaying = isPlaying,
                    )
                    // Depth scrim as it departs
                    if (outgoingScrim > 0.01f) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = outgoingScrim)),
                        )
                    }
                }
            }

            // Incoming Deck (docking in from right)
            if (incomingAlpha > 0.005f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            // Dominant at and after handoff (0.5)
                            if (progress >= 0.5f) {
                                onArtworkBounds?.invoke(it.boundsInRoot())
                            }
                        }
                        .graphicsLayer {
                            scaleX = incomingScale
                            scaleY = incomingScale
                            translationX = incomingOffsetX
                            alpha = incomingAlpha
                        }
                        .shadow(
                            elevation = incomingElevation,
                            shape = RoundedCornerShape(22.dp),
                            spotColor = Color.Black.copy(alpha = 0.70f),
                            ambientColor = Color.Black.copy(alpha = 0.35f),
                        )
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF181A1B)),
                    contentAlignment = Alignment.Center,
                ) {
                    RemoteArtwork(
                        url = inTrack.artworkUrl,
                        description = "Artwork for ${inTrack.title}",
                        modifier = Modifier.fillMaxSize(),
                    )
                    SquareAnimatedArtwork(
                        track = inTrack,
                        enabled = animatedArtworkEnabled && track.id == inTrack.id,
                        isPlaying = isPlaying,
                    )
                }
            }
        }
    } else {
        // Standard single-deck state (outside of transition, or during manual skip)
        AnimatedContent(
            targetState = track,
            transitionSpec = {
                if (instantTrackChange) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(tween(500)) + scaleIn(initialScale = 0.92f, animationSpec = tween(500)))
                        .togetherWith(fadeOut(tween(400)) + scaleOut(targetScale = 1.05f, animationSpec = tween(400)))
                }
            },
            label = "NowPlayingArtworkCardTransition",
            modifier = modifier,
        ) { currentTrack ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .onGloballyPositioned { onArtworkBounds?.invoke(it.boundsInRoot()) }
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(22.dp),
                        spotColor = Color.Black.copy(alpha = 0.65f),
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF181A1B)),
                contentAlignment = Alignment.Center,
            ) {
                RemoteArtwork(
                    url = currentTrack.artworkUrl,
                    description = "Artwork for ${currentTrack.title}",
                    modifier = Modifier.fillMaxSize(),
                )
                SquareAnimatedArtwork(
                    track = currentTrack,
                    // AnimatedContent keeps the departing item composed during its fade. Only
                    // the current identity may own a decoder, so that fade uses its still cover.
                    enabled = animatedArtworkEnabled && currentTrack.id == track.id,
                    isPlaying = isPlaying,
                )
            }
        }
    }
}

/** Exactly one square motion-cover decoder is alive, including during a dual-deck handoff. */
@Composable
private fun SquareAnimatedArtwork(
    track: Track,
    enabled: Boolean,
    isPlaying: Boolean,
) {
    if (!enabled) return
    val url = track.animatedArtworkUrl.ifBlank { track.animatedArtworkVerticalUrl }
    if (url.isBlank()) return
    AnimatedArtworkVideo(
        url = url,
        active = isPlaying,
        modifier = Modifier.fillMaxSize(),
    )
}
