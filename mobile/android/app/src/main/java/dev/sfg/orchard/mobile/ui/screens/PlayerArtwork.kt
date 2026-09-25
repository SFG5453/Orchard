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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.KawarpArtworkBackdrop
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
    /** Use a tiny static cover and AGSL motion instead of decoding full-screen video. */
    warpedArtworkEnabled: Boolean = false,
    /** Tablets let Kawarp own the ambient motion; this avoids a second full-rate redraw loop. */
    ambientGlowEnabled: Boolean = true,
    incomingPalette: ArtworkPalette? = null,
    /** Where the cover actually sits, so a dismissal can fly it into the pill. */
    onArtworkBounds: ((Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 0f outside a transition, rising to 1f at the handoff. Drives the cover handoff. */
    transitionProgress: Float = 0f,
    gesturesEnabled: Boolean = false,
    artworkAlpha: Float = 1f,
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onLiked: () -> Unit = {},
) {
    val progress = transitionProgress.coerceIn(0f, 1f)
    val targetBottom = if (incomingPalette != null && progress in 0.001f..0.999f) {
        lerp(palette.bottom, incomingPalette.bottom, progress)
    } else {
        palette.bottom
    }
    val targetDeep = if (incomingPalette != null && progress in 0.001f..0.999f) {
        lerp(palette.deep, incomingPalette.deep, progress)
    } else {
        palette.deep
    }
    val targetAccent = if (incomingPalette != null && progress in 0.001f..0.999f) {
        lerp(palette.accent, incomingPalette.accent, progress)
    } else {
        palette.accent
    }

    val animatedBottom by animateColorAsState(
        targetValue = targetBottom,
        animationSpec = tween(500),
        label = "PaletteBottom",
    )
    val animatedDeep by animateColorAsState(
        targetValue = targetDeep,
        animationSpec = tween(500),
        label = "PaletteDeep",
    )
    val animatedAccent by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(500),
        label = "PaletteAccent",
    )

    Box(
        modifier
            .fillMaxSize()
            .then(
                if (gesturesEnabled) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { onLiked() })
                        }
                        .pointerInput(Unit) {
                            var dragDistance = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragDistance = 0f },
                                onDragEnd = {
                                    if (dragDistance > 150f) onPrevious()
                                    else if (dragDistance < -150f) onNext()
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    dragDistance += dragAmount
                                }
                            )
                        }
                } else Modifier
            )
            .background(
                Brush.verticalGradient(
                    0.0f to animatedBottom,
                    0.45f to animatedBottom,
                    1.0f to animatedDeep,
                ),
            ),
    ) {
        if (ambientGlowEnabled) {
            val transition = rememberInfiniteTransition(label = "PlayerAmbience")
            val glow by transition.animateFloat(
                initialValue = 0.28f,
                targetValue = 0.52f,
                animationSpec = infiniteRepeatable(tween(9_000), RepeatMode.Reverse),
                label = "PlayerAmbienceGlow",
            )
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
        }

        val currentVideo = track.animatedArtworkVerticalUrl.ifBlank { track.animatedArtworkUrl }
        val currentRich = !warpedArtworkEnabled && animatedArtworkEnabled && currentVideo.isNotBlank()

        if (warpedArtworkEnabled) {
            KawarpArtworkBackdrop(
                artworkUrl = track.artworkUrl,
                isPlaying = isPlaying,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = artworkAlpha },
            )
        } else if (currentRich) {
            // Artwork container with an alpha gradient mask (BlendMode.DstIn) so the artwork
            // dissolves completely and seamlessly into the sampled backdrop with zero visual seam or gap.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(ARTWORK_HEIGHT_FRACTION)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = artworkAlpha }
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
                    val videoUrl = currentTrack.animatedArtworkVerticalUrl.ifBlank { currentTrack.animatedArtworkUrl }
                    val rich = animatedArtworkEnabled && videoUrl.isNotBlank()

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

                        if (rich) {
                            AnimatedArtworkVideo(
                                url = videoUrl,
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
}

/**
 * Centered square artwork card supporting intelligent dual-deck Smart Crossfade
 * mixing, constant-power energy dissolves, subtle 3D spatial docking, micro beat pulses,
 * soft drop shadow, refined corner radius, and reporting bounds for the collapse flight.
 */
@Composable
fun NowPlayingArtworkCard(
    track: Track,
    incomingTrack: Track? = null,
    outgoingTrack: Track? = null,
    transitionProgress: Float = 0f,
    transitionStyle: String = "",
    animatedArtworkEnabled: Boolean = false,
    isPlaying: Boolean = false,
    onArtworkBounds: ((Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val progress = transitionProgress.coerceIn(0f, 1f)
    val outTrack = outgoingTrack ?: track
    val inTrack = incomingTrack
    val isDualDeckActive = inTrack != null && outTrack.id != inTrack.id && progress in 0.001f..0.999f

    if (isDualDeckActive) {
        // Dual-deck Smart Crossfade visual mix stage
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
                (fadeIn(tween(500)) + scaleIn(initialScale = 0.92f, animationSpec = tween(500)))
                    .togetherWith(fadeOut(tween(400)) + scaleOut(targetScale = 1.05f, animationSpec = tween(400)))
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
