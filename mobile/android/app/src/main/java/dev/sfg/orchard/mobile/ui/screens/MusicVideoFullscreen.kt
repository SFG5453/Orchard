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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ZoomInMap
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.sfg.orchard.mobile.app.MusicVideoState
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.LocalPlayerClock
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlinx.coroutines.delay

/** Landscape video. Tap toggles chrome; chrome hides itself while playing. */
@Composable
internal fun MusicVideoFullscreen(
    musicVideo: MusicVideoState,
    player: Player?,
    track: Track,
    playback: PlaybackSnapshot,
    localControls: Boolean,
    actions: MusicVideoActions,
    onSong: () -> Unit,
    onExit: () -> Unit,
) {
    var chrome by remember { mutableStateOf(true) }
    var fill by rememberSaveable { mutableStateOf(false) }
    // Bumped by every control press so the hide timer restarts.
    var touches by remember { mutableIntStateOf(0) }
    val touch = { touches++ }
    LaunchedEffect(chrome, playback.isPlaying, touches) {
        if (chrome && playback.isPlaying) {
            delay(CHROME_TIMEOUT_MS)
            chrome = false
        }
    }
    val clock = LocalPlayerClock.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = if (chrome) "Hide controls" else "Show controls",
            ) { chrome = !chrome }
            // After the click, so a pinch is consumed before it can count as a tap.
            .pinchToFill { fill = it },
    ) {
        MusicVideoSurface(player, Modifier.fillMaxSize(), fill = fill)
        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Scrim(top = true, Modifier.align(Alignment.TopCenter))
                Scrim(top = false, Modifier.align(Alignment.BottomCenter))
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Rounded.ExpandMore, "Exit full screen", tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(track.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = CanopyColors.MutedStrong, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SongVideoSwitch(video = true, onSelect = { if (!it) onSong() })
                }
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl(Icons.Rounded.SkipPrevious, "Previous", 56.dp, localControls) { touch(); actions.onPrevious() }
                    RoundControl(
                        if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playback.isPlaying) "Pause" else "Play",
                        72.dp,
                        localControls,
                    ) { touch(); actions.onToggle() }
                    RoundControl(Icons.Rounded.SkipNext, "Next", 56.dp, localControls) { touch(); actions.onNext() }
                }
                Row(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerScrubber(playback, { touch(); actions.onSeek(it) }, Modifier.weight(1f))
                    VideoQualityButton(musicVideo, { touch(); actions.onQuality(it) }, Modifier.padding(start = 12.dp))
                    IconButton(onClick = { touch(); fill = !fill }) {
                        Icon(
                            if (fill) Icons.Rounded.ZoomInMap else Icons.Rounded.ZoomOutMap,
                            if (fill) "Fit to screen" else "Fill screen",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onExit) {
                        Icon(Icons.Rounded.FullscreenExit, "Exit full screen", tint = Color.White)
                    }
                }
            }
        }
        if (!chrome) {
            // Hairline progress keeps time visible without covering the picture.
            LinearProgressIndicator(
                progress = {
                    val head = clock.reported()
                    if (head.durationMs > 0) head.positionMs.toFloat() / head.durationMs else 0f
                },
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter),
                color = CanopyColors.Accent,
                trackColor = Color.White.copy(alpha = 0.12f),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
    }
}

/** Spreading two fingers fills the screen and pinching fits it again; one finger passes through. */
private fun Modifier.pinchToFill(onFill: (Boolean) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (zoom > PINCH_THRESHOLD) onFill(true) else if (zoom < 1f / PINCH_THRESHOLD) onFill(false)
    }
}

@Composable
private fun Scrim(top: Boolean, modifier: Modifier) {
    val dark = Color.Black.copy(alpha = 0.75f)
    Box(
        modifier
            .fillMaxWidth()
            .height(if (top) 110.dp else 130.dp)
            .background(Brush.verticalGradient(if (top) listOf(dark, Color.Transparent) else listOf(Color.Transparent, dark))),
    )
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size).background(Color.Black.copy(alpha = 0.38f), CircleShape),
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.45f))
    }
}

private const val CHROME_TIMEOUT_MS = 3_000L
private const val PINCH_THRESHOLD = 1.12f
