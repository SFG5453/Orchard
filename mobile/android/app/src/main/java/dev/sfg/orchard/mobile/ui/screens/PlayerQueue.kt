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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.ExplicitBadge
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/**
 * The queue as a mode of the player rather than a destination: the transport below stays put and
 * the list takes the space the artwork was using, so reordering never loses sight of what is
 * playing. Styled for the artwork backdrop, so everything here is white-on-translucent.
 */
@Composable
fun PlayerQueuePanel(
    playback: PlaybackSnapshot,
    editable: Boolean,
    onPlayIndex: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClearUpcoming: () -> Unit,
    onShuffleUpcoming: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onRepeat: (() -> Unit)? = null,
    autoplayEnabled: Boolean = true,
    autoplayLoading: Boolean = false,
    autoplayError: String = "",
    onAutoplayEnabled: ((Boolean) -> Unit)? = null,
    smartCrossfade: Boolean = false,
    onBestMixUpcoming: ((onProgress: (String) -> Unit, onComplete: () -> Unit) -> Unit)? = null,
    sleepTimerRemainingSeconds: Long = 0L,
    sleepTimerEndOfTrack: Boolean = false,
    onSleepTimer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val history = playback.history.takeLast(4)
    val historyStart = playback.currentIndex - history.size
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = remember { 0 },
    )
    if (playback.queue.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                QueueTopControls(
                    shuffle = playback.shuffle,
                    repeatMode = playback.repeatMode,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    smartCrossfade = smartCrossfade,
                    onBestMixUpcoming = onBestMixUpcoming,
                    upcomingCount = 0,
                    autoplayEnabled = autoplayEnabled,
                    autoplayLoading = autoplayLoading,
                    autoplayError = autoplayError,
                    onAutoplayEnabled = onAutoplayEnabled,
                    sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                    sleepTimerEndOfTrack = sleepTimerEndOfTrack,
                    onSleepTimer = onSleepTimer,
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QueueNotice("Queue is empty", "Play an album, playlist, or song to get started.")
                }
            }
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        item {
            QueueTopControls(
                shuffle = playback.shuffle,
                repeatMode = playback.repeatMode,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                smartCrossfade = smartCrossfade,
                onBestMixUpcoming = onBestMixUpcoming,
                upcomingCount = playback.upcoming.size,
                autoplayEnabled = autoplayEnabled,
                autoplayLoading = autoplayLoading,
                autoplayError = autoplayError,
                onAutoplayEnabled = onAutoplayEnabled,
                sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                sleepTimerEndOfTrack = sleepTimerEndOfTrack,
                onSleepTimer = onSleepTimer,
            )
        }
        if (history.isNotEmpty()) {
            item { QueueSectionHeader("Played") }
            itemsIndexed(history, key = { index, track -> "history:$index:${track.id}" }) { offset, track ->
                val index = historyStart + offset
                QueueTrackRow(
                    track = track, index = index, queueSize = playback.queue.size,
                    editable = false, isHistory = true,
                    onPlay = { onPlayIndex(index) }, onRemove = onRemove, onMove = onMove,
                )
            }
        }
        item {
            QueueSectionHeader(
                title = "Playing next",
                trailing = if (playback.upcoming.isNotEmpty() && editable) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (playback.upcoming.size > 1 && onShuffleUpcoming != null) {
                                QueueShuffleButton(onShuffleUpcoming)
                                Spacer(Modifier.width(4.dp))
                            }
                            QueueClearButton(onClearUpcoming)
                        }
                    }
                } else {
                    null
                },
            )
        }
        if (playback.upcoming.isEmpty()) {
            item { QueueNotice("End of the queue", "Add more music from Search or Library.") }
        }
        itemsIndexed(playback.upcoming, key = { index, track -> "upcoming:$index:${track.id}" }) { offset, track ->
            val index = playback.currentIndex + 1 + offset
            QueueTrackRow(
                track = track, index = index, queueSize = playback.queue.size,
                editable = editable, isHistory = false,
                onPlay = { onPlayIndex(index) }, onRemove = onRemove, onMove = onMove,
            )
        }
    }
}

/**
 * Top control card and quick pill row for the queue screen.
 * Displays Best Mix in the center when smart crossfade is active, alongside Autoplay and Sleep Timer pills.
 */
@Composable
fun QueueTopControls(
    shuffle: Boolean,
    repeatMode: dev.sfg.orchard.mobile.model.RepeatMode,
    onShuffle: (() -> Unit)?,
    onRepeat: (() -> Unit)?,
    smartCrossfade: Boolean,
    onBestMixUpcoming: ((onProgress: (String) -> Unit, onComplete: () -> Unit) -> Unit)?,
    upcomingCount: Int,
    autoplayEnabled: Boolean,
    autoplayLoading: Boolean,
    autoplayError: String,
    onAutoplayEnabled: ((Boolean) -> Unit)?,
    sleepTimerRemainingSeconds: Long,
    sleepTimerEndOfTrack: Boolean,
    onSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isSorting by remember { mutableStateOf(false) }
    var sortStatusText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QueueTopPill(
                icon = Icons.Rounded.Shuffle,
                active = shuffle,
                onClick = { onShuffle?.invoke() },
                modifier = Modifier.weight(1f)
            )
            QueueTopPill(
                icon = if (repeatMode == dev.sfg.orchard.mobile.model.RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                active = repeatMode != dev.sfg.orchard.mobile.model.RepeatMode.OFF,
                onClick = { onRepeat?.invoke() },
                modifier = Modifier.weight(1f)
            )
            QueueTopPill(
                icon = Icons.Rounded.AllInclusive,
                active = autoplayEnabled,
                onClick = { onAutoplayEnabled?.invoke(!autoplayEnabled) },
                modifier = Modifier.weight(1f)
            )
            QueueTopPill(
                icon = Icons.Rounded.AutoAwesome,
                active = smartCrossfade || isSorting,
                onClick = {
                    if (!isSorting && upcomingCount > 1 && onBestMixUpcoming != null) {
                        isSorting = true
                        onBestMixUpcoming(
                            { status -> sortStatusText = status },
                            { isSorting = false; sortStatusText = "" },
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Keep sleep timer below if active or available
        val isSleepActive = sleepTimerRemainingSeconds > 0 || sleepTimerEndOfTrack
        if (isSleepActive || onAutoplayEnabled != null) {
            val sleepTimerText = when {
                sleepTimerEndOfTrack -> "End of track"
                sleepTimerRemainingSeconds > 0 -> "${(sleepTimerRemainingSeconds + 59) / 60}m remaining"
                else -> "Off"
            }

            Surface(
                color = Color.White.copy(alpha = if (isSleepActive) 0.14f else 0.07f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onSleepTimer),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                if (isSleepActive) Color(0xFFB39DDB).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Bedtime,
                            contentDescription = "Sleep Timer",
                            tint = if (isSleepActive) Color(0xFFCE93D8) else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Sleep Timer",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                        Text(
                            sleepTimerText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSleepActive) Color(0xFFCE93D8) else Color.White.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTopPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (active) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f)
    val iconColor = if (active) Color.Black else Color.White
    Surface(
        color = backgroundColor,
        shape = CircleShape,
        modifier = modifier
            .height(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
private fun QueueSectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            ),
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun QueueShuffleButton(onShuffleUpcoming: () -> Unit) {
    IconButton(onClick = onShuffleUpcoming, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Rounded.Shuffle,
            "Shuffle upcoming queue",
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun QueueClearButton(onClearUpcoming: () -> Unit) {
    IconButton(onClick = onClearUpcoming, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Rounded.ClearAll,
            "Clear upcoming queue",
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun QueueNotice(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QueueTrackRow(
    track: Track,
    index: Int,
    queueSize: Int,
    editable: Boolean,
    isHistory: Boolean,
    onPlay: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Surface(
        onClick = onPlay,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkTile(track.artworkUrl, "Artwork for ${track.title}", Modifier.size(44.dp), 8)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isHistory) Color.White.copy(alpha = 0.55f) else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (track.explicit) {
                        Spacer(Modifier.width(6.dp))
                        ExplicitBadge()
                    }
                }
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = if (isHistory) 0.4f else 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editable && !isHistory) {
                QueueRowButton(
                    icon = Icons.Rounded.KeyboardArrowUp,
                    description = "Move ${track.title} up",
                    enabled = index > 0,
                    onClick = { onMove(index, index - 1) },
                )
                QueueRowButton(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    description = "Move ${track.title} down",
                    enabled = index < queueSize - 1,
                    onClick = { onMove(index, index + 1) },
                )
                QueueRowButton(
                    icon = Icons.Rounded.DeleteOutline,
                    description = "Remove ${track.title} from queue",
                    enabled = true,
                    onClick = { onRemove(index) },
                )
            }
        }
    }
}

@Composable
private fun QueueRowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            description,
            tint = Color.White.copy(alpha = if (enabled) 0.7f else 0.25f),
            modifier = Modifier.size(20.dp),
        )
    }
}
