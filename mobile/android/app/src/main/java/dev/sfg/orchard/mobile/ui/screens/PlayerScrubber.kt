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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.durationText

/**
 * Expressive scrubber progress bar with active dragging state,
 * buffered range indicator, and high-precision duration readouts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScrubber(
    playback: PlaybackSnapshot,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** The planned transition out of this track, drawn as a band on the track. Null when none. */
    transition: TransitionMarker? = null,
    showBitrate: Boolean = false,
    bitrateKbps: Int = 0,
    audioQuality: dev.sfg.orchard.mobile.model.AudioQuality = dev.sfg.orchard.mobile.model.AudioQuality.HIGH,
) {
    val duration = playback.durationMs.coerceAtLeast(1)
    val buffered = playback.bufferedPositionMs.coerceIn(0, duration)
    var dragging by remember(playback.currentTrack?.id) { mutableStateOf(false) }
    var dragPosition by remember(playback.currentTrack?.id) { mutableFloatStateOf(0f) }

    val interactionSource = remember { MutableInteractionSource() }
    val isUserDragging by interactionSource.collectIsDraggedAsState()
    val isUserPressed by interactionSource.collectIsPressedAsState()
    val isInteracting = dragging || isUserDragging || isUserPressed

    val currentPosition = if (isInteracting) {
        dragPosition
    } else {
        playback.positionMs.toFloat().coerceIn(0f, duration.toFloat())
    }

    val trackHeight by animateDpAsState(
        targetValue = if (isInteracting) 8.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ScrubberTrackHeight",
    )

    val thumbVisibleSize by animateDpAsState(
        targetValue = if (isInteracting) 14.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ScrubberThumbVisibleSize",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = currentPosition,
                onValueChange = {
                    dragging = true
                    dragPosition = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(dragPosition.toLong())
                },
                valueRange = 0f..duration.toFloat(),
                interactionSource = interactionSource,
                thumb = {
                    Box(
                        modifier = Modifier.size(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumbVisibleSize > 0.5.dp) {
                            Box(
                                modifier = Modifier
                                    .size(thumbVisibleSize)
                                    .shadow(4.dp, CircleShape)
                                    .background(Color.White, CircleShape),
                            )
                        }
                    }
                },
                track = { sliderState ->
                    val rangeSpan = (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(1f)
                    val fraction = ((sliderState.value - sliderState.valueRange.start) / rangeSpan).coerceIn(0f, 1f)
                    val bufferFraction = if (duration > 0) {
                        (buffered.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    // Only for this track: a marker left over from the previous one would sit at a
                    // meaningless position for a second after the handoff.
                    val marker = transition?.takeIf {
                        it.trackId.isNotBlank() && it.trackId == playback.currentTrack?.id
                    }
                    val markerStart = marker?.let { (it.startMs.toFloat() / duration).coerceIn(0f, 1f) }
                    val markerEnd = marker?.let { (it.endMs.toFloat() / duration).coerceIn(0f, 1f) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(trackHeight)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.White.copy(alpha = 0.20f)),
                    ) {
                        if (markerStart != null && markerEnd != null && markerEnd > markerStart) {
                            // Drawn as a band rather than a line because a transition has a length,
                            // and its length is the interesting part: a beat-matched blend runs for
                            // bars where a plain fade is a few seconds.
                            Row(Modifier.fillMaxSize()) {
                                if (markerStart > 0f) Spacer(Modifier.weight(markerStart))
                                Box(
                                    Modifier
                                        .weight(markerEnd - markerStart)
                                        .fillMaxHeight()
                                        .background(LocalAccent.current.copy(alpha = 0.55f)),
                                )
                                if (markerEnd < 1f) Spacer(Modifier.weight(1f - markerEnd))
                            }
                        }
                        if (bufferFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(bufferFraction)
                                    .fillMaxHeight()
                                    .background(Color.White.copy(alpha = 0.18f)),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(Color.White),
                        )
                    }
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.20f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Time readouts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = durationText(currentPosition.toLong()),
                color = Color.White.copy(alpha = 0.60f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                ),
            )
            if (showBitrate) {
                val displayKbps = if (bitrateKbps > 0) bitrateKbps else when (audioQuality) {
                    dev.sfg.orchard.mobile.model.AudioQuality.DATA_SAVER -> 70
                    dev.sfg.orchard.mobile.model.AudioQuality.NORMAL -> 128
                    dev.sfg.orchard.mobile.model.AudioQuality.HIGH -> 160
                    dev.sfg.orchard.mobile.model.AudioQuality.MAX -> 256
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$displayKbps kbps",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
            val remainingMs = (duration - currentPosition.toLong()).coerceAtLeast(0)
            Text(
                text = "-${durationText(remainingMs)}",
                color = Color.White.copy(alpha = 0.60f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                ),
            )
        }
    }
}

