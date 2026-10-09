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

import androidx.compose.foundation.background
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
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.PlaybackSnapshot

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
    modifier: Modifier = Modifier,
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
                    modifier = Modifier.riseIn(offset, cascadeOnScroll = true),
                )
            }
        }
        item {
            QueueSectionHeader(
                title = "Playing next",
                trailing = if (playback.upcoming.isNotEmpty() && editable) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (playback.upcoming.size > 1 && onBestMixUpcoming != null) {
                                QueueBestMixButton(onBestMixUpcoming)
                                Spacer(Modifier.width(4.dp))
                            }
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
                modifier = Modifier.riseIn(offset, cascadeOnScroll = true),
            )
        }
    }
}

/**
 * Top control card and quick pill row for the queue screen.
 * Displays Best Mix in the center when adaptive mix is on, alongside Autoplay and Sleep Timer pills.
 */
/** Two big tiles for the queue's standing modes. Shuffle and repeat live on the transport row. */
@Composable
fun QueueTopControls(
    autoplayEnabled: Boolean,
    autoplayLoading: Boolean,
    autoplayError: String,
    onAutoplayEnabled: ((Boolean) -> Unit)?,
    sleepTimerRemainingSeconds: Long,
    sleepTimerEndOfTrack: Boolean,
    onSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sleepActive = sleepTimerRemainingSeconds > 0 || sleepTimerEndOfTrack
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QueueModeTile(
            icon = Icons.Rounded.Bedtime,
            title = "Sleep timer",
            status = when {
                sleepTimerEndOfTrack -> "End of track"
                sleepTimerRemainingSeconds > 0 -> "${(sleepTimerRemainingSeconds + 59) / 60} min left"
                else -> "Off"
            },
            active = sleepActive,
            tint = SleepTint,
            onClick = onSleepTimer,
            modifier = Modifier.weight(1f),
        )
        if (onAutoplayEnabled != null) {
            QueueModeTile(
                icon = Icons.Rounded.AllInclusive,
                title = "Autoplay",
                status = when {
                    !autoplayEnabled -> "Off"
                    autoplayLoading -> "Finding songs…"
                    autoplayError.isNotBlank() -> autoplayError
                    else -> "On"
                },
                active = autoplayEnabled,
                tint = AutoplayTint,
                onClick = { onAutoplayEnabled(!autoplayEnabled) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QueueModeTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    status: String,
    active: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = if (active) 0.16f else 0.07f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.height(76.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(if (active) tint.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (active) tint else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) tint else Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val SleepTint = Color(0xFFCE93D8)
private val AutoplayTint = Color(0xFF80DEEA)
