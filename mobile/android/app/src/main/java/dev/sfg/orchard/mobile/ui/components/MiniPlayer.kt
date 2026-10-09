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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import dev.sfg.orchard.mobile.ui.motion.pressScale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MiniPlayerShape = RoundedCornerShape(16.dp)

/** Expressive floating mini-player component matching SimpMusic design. */
@Composable
fun MiniPlayer(
    playback: PlaybackSnapshot,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    transition: dev.sfg.orchard.mobile.model.TransitionMarker? = null,
    /** Raw overlap progress, retained after visible identity has moved to the incoming track. */
    mixProgress: Float? = null,
    /** Reports the thumbnail's place on screen so the full player can fly its cover into it. */
    onArtworkBounds: ((Rect) -> Unit)? = null,
    onClear: () -> Unit = {},
) {
    val track = playback.currentTrack ?: return
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val settleAnim = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val currentOffsetY = if (isDragging) dragOffsetY else settleAnim.value
    val dismissThresholdPx = with(density) { 44.dp.toPx() }
    val dismissVelocityPx = with(density) { 300.dp.toPx() }
    val fullDismissPx = with(density) { 80.dp.toPx() }

    val marker = transition?.takeIf {
        it.trackId.isNotBlank() && it.trackId == track.id && it.startMs > 0 && it.startMs < playback.durationMs
    }
    val effectiveDuration = (marker?.startMs ?: playback.durationMs).coerceAtLeast(1)

    // Read in draw only: the pill never recomposes for time.
    val clock = LocalPlayerClock.current
    var barWidthPx by remember { mutableFloatStateOf(0f) }
    val tick = rememberPlayheadTick(playback.isPlaying, playheadStepMs(effectiveDuration, barWidthPx))
    val progress = {
        tick.value
        val head = clock.projected()
        val end = (marker?.startMs ?: head.durationMs).coerceAtLeast(1)
        (head.positionMs.toFloat() / end).coerceIn(0f, 1f)
    }
    // The pill picks up the cover's colours so it reads as part of the artwork.
    val palette = rememberArtworkPalette(track.artworkUrl)

    val dragModifier = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta ->
            dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
        },
        onDragStarted = {
            dragOffsetY = settleAnim.value
            isDragging = true
        },
        onDragStopped = { velocity ->
            val committed = dragOffsetY > dismissThresholdPx || velocity > dismissVelocityPx
            isDragging = false
            coroutineScope.launch {
                settleAnim.snapTo(dragOffsetY)
                if (committed) {
                    settleAnim.animateTo(fullDismissPx, tween(140))
                    onClear()
                    settleAnim.snapTo(0f)
                    dragOffsetY = 0f
                } else {
                    settleAnim.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                    dragOffsetY = 0f
                }
            }
        },
    )

    val dismissAlpha = (1f - (currentOffsetY / fullDismissPx)).coerceIn(0f, 1f)

    Card(
        shape = MiniPlayerShape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .offset { IntOffset(0, currentOffsetY.roundToInt()) }
            .alpha(dismissAlpha)
            .then(dragModifier)
            .glassPane(MiniPlayerShape, GlassTone.CHROME)
            .clip(MiniPlayerShape)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            lerp(palette.accent, Color.White, 0.18f),
                            lerp(palette.bottom, Color.White, 0.06f),
                        ),
                    ),
                    // Left as a wash over the pane rather than an opaque fill, so the frost the
                    // pill is cut from is still the thing you see.
                    alpha = 0.28f,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(
                    targetState = track.artworkUrl to track.title,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(350)) togetherWith fadeOut(animationSpec = tween(250))
                    },
                    label = "MiniPlayerArtworkCrossfade",
                ) { (artUrl, artTitle) ->
                    ArtworkTile(
                        url = artUrl,
                        description = artTitle,
                        modifier = Modifier
                            .size(44.dp)
                            .onGloballyPositioned { onArtworkBounds?.invoke(it.boundsInRoot()) },
                        radius = 10
                    )
                }
                Spacer(Modifier.width(10.dp))
                AnimatedContent(
                    targetState = track.title to track.artist,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(350, delayMillis = 40)) + slideInVertically(animationSpec = tween(350, delayMillis = 40)) { it / 3 }) togetherWith
                            (fadeOut(animationSpec = tween(200)) + slideOutVertically(animationSpec = tween(200)) { -it / 3 })
                    },
                    label = "MiniPlayerTrackTransition",
                    modifier = Modifier.weight(1f),
                ) { (title, artist) ->
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = CanopyColors.Text,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = CanopyColors.Muted,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                val buttonColor = lerp(palette.accent, Color.White, 0.62f)
                val toggleSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                IconButton(
                    onClick = onTogglePlay,
                    interactionSource = toggleSource,
                    modifier = Modifier
                        .pressScale(toggleSource, 0.82f)
                        .size(40.dp)
                        .background(buttonColor, CircleShape)
                ) {
                    // Glyphs scale past each other so play and pause never just blink.
                    AnimatedContent(
                        targetState = playback.isPlaying,
                        transitionSpec = {
                            (fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 0.4f)) togetherWith
                                (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.4f))
                        },
                        label = "MiniPlayPause",
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            // Keep the glyph readable whatever hue the cover produced.
                            tint = if (buttonColor.luminance() > 0.5f) Color.Black else Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            // Bottom progress bar, which picks up the same rainbow as the full player
            // while a mix is running so the pill tells the same story in miniature.
            val glow = rememberTransitionGlow(mixProgress ?: 0f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            ) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .onSizeChanged { barWidthPx = it.width.toFloat() },
                    color = LocalAccent.current,
                    trackColor = Color.Transparent,
                    strokeCap = StrokeCap.Round,
                )
                if (glow > 0.01f) {
                    val rainbow = rememberRainbowBrush()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .alpha(glow)
                            .drawBehind {
                                drawRect(rainbow, size = size.copy(width = size.width * progress()))
                            },
                    )
                }
            }
        }
    }
}

@Composable
fun PlaybackTargetLabel(name: String, isLocal: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = CanopyColors.Surface,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, CanopyColors.Rule),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (isLocal) CanopyColors.Muted else LocalAccent.current,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = if (isLocal) CanopyColors.Text else LocalAccent.current,
            )
        }
    }
}
