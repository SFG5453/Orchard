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

import kotlin.math.abs
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.lerp
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.KawarpArtworkBackdrop
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import dev.sfg.orchard.mobile.ui.components.ArtworkPalette
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette
import dev.sfg.orchard.mobile.ui.components.smoothScrimBrush

/**
 * Full-bleed player backdrop. Motion art sits at the top in a frame of its own shape (a
 * 9:16 canvas fills the screen), and a smoothstep scrim settles the lower part
 * into the cover's own deep tone so controls sit on the image without a visible seam
 * (after SimpMusic's canvas player). Without motion art, a palette wash with a slow
 * ambient glow carries the screen.
 */
@Composable
fun FullBleedPlayerBackdrop(
    track: Track,
    isPlaying: Boolean,
    animatedArtworkEnabled: Boolean,
    /** Hoisted so anything drawn over the backdrop tints from the same sample. */
    palette: ArtworkPalette,
    onVideoFrame: (Bitmap?) -> Unit,
    modifier: Modifier = Modifier,
    /** Use a tiny static cover and AGSL motion instead of decoding full-screen video. */
    warpedArtworkEnabled: Boolean = false,
    /** Tablets let Kawarp own the ambient motion; this avoids a second full-rate redraw loop. */
    ambientGlowEnabled: Boolean = true,
    incomingPalette: ArtworkPalette? = null,
    /** Where the cover actually sits, so a dismissal can fly it into the pill. */
    onArtworkBounds: ((Rect) -> Unit)? = null,
    /** 0f outside a transition, rising to 1f at the handoff. Drives the cover handoff. */
    transitionProgress: Float = 0f,
    gesturesEnabled: Boolean = false,
    artworkAlpha: Float = 1f,
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onLiked: () -> Unit = {},
    swipe: ArtworkSwipe? = null,
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

    val currentVideo = track.animatedArtworkVerticalUrl.ifBlank { track.animatedArtworkUrl }
    val currentRich = !warpedArtworkEnabled && animatedArtworkEnabled && currentVideo.isNotBlank()

    Box(
        modifier
            .fillMaxSize()
            .then(
                // With a swipe supplied, the body above takes both gestures and this layer only follows.
                if (gesturesEnabled && swipe == null) {
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
            // Motion art scrims into flat deep, so the frame's lower edge meets the same colour.
            .then(
                if (currentRich) {
                    Modifier.background(animatedDeep)
                } else {
                    Modifier.background(
                        smoothScrimBrush(
                            from = animatedBottom,
                            to = animatedDeep,
                            startFraction = WASH_START,
                        ),
                    )
                },
            ),
    ) {
        // Edge-to-edge motion art covers the glow entirely, so skip its redraw loop.
        if (ambientGlowEnabled && !currentRich) {
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

        if (warpedArtworkEnabled) {
            KawarpArtworkBackdrop(
                artworkUrl = track.artworkUrl,
                isPlaying = isPlaying,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = artworkAlpha },
            )
        } else if (currentRich) {
            // The frame takes the video's own shape, so nothing is cropped unless the video
            // is taller than the screen (9:16 canvas), which then fills it edge to edge.
            val screenAspect = screenAspect()
            var videoAspect by remember(currentVideo) { mutableStateOf<Float?>(null) }
            val targetFrame = maxOf(videoAspect ?: guessedVideoAspect(track), screenAspect)
            val frameAspect by animateFloatAsState(targetFrame, tween(420), label = "ArtworkFrameAspect")
            val fillsScreen = targetFrame <= screenAspect + FULL_SCREEN_SLACK
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(frameAspect)
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        val shift = swipe?.offset ?: 0f
                        translationX = shift
                        // Edge-to-edge art has no neighbour to reveal, so it thins out as it leaves.
                        alpha = artworkAlpha * (1f - abs(shift) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    }
                    .onGloballyPositioned { onArtworkBounds?.invoke(it.boundsInRoot()) }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            smoothScrimBrush(
                                from = animatedDeep.copy(alpha = 0f),
                                to = animatedDeep,
                                // Scrim ends opaque before the gesture bar or the frame's edge.
                                startFraction = if (fillsScreen) SCRIM_START else FRAME_SCRIM_START,
                                endFraction = if (fillsScreen) SCRIM_END else 1f,
                            ),
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
                                onVideoAspect = if (currentTrack.id == track.id) {
                                    { videoAspect = it }
                                } else null,
                            )
                        }
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
    val visibleAspect = maxOf(guessedVideoAspect(track), screenAspect())
    return rememberArtworkPalette(track.artworkUrl, visibleAspect, videoFrame)
}

@Composable
private fun screenAspect(): Float {
    val container = LocalWindowInfo.current.containerSize
    return container.width / container.height.toFloat().coerceAtLeast(1f)
}

/** Shape to lay out before the video reports its size. Apple's tall motion art is 3:4. */
private fun guessedVideoAspect(track: Track): Float =
    if (track.animatedArtworkVerticalUrl.isNotBlank()) TALL_ART_ASPECT else 1f

/** Where the palette wash starts ramping from the cover tone to the deep tone. */
private const val WASH_START = 0.45f

/** Art scrim ramp, as fractions of screen height. Opaque before the gesture bar. */
private const val SCRIM_START = 0.38f
private const val SCRIM_END = 0.94f

/** Scrim start within a frame shorter than the screen; it ends opaque at the frame's edge. */
private const val FRAME_SCRIM_START = 0.5f
private const val TALL_ART_ASPECT = 0.75f

/** Within this of the screen's shape, a frame counts as full screen. */
private const val FULL_SCREEN_SLACK = 0.01f
private const val AMBIENCE_RADIUS = 1400f

/** How far the cover has drawn back by the moment the two tracks hand over. */
private const val HANDOFF_SHRINK = 0.14f

/** How far it keeps going once it is no longer the playing track. */
private const val DEPARTURE_SHRINK = 0.22f

/** Corner radius, in dp, the cover would reach if it shrank all the way to nothing. */
private const val ARTWORK_CORNER_SPAN = 130f
