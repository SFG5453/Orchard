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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.ExplicitBadge

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
    autoplayEnabled: Boolean = true,
    autoplayLoading: Boolean = false,
    autoplayError: String = "",
    onAutoplayEnabled: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val history = playback.history.takeLast(4)
    val historyStart = playback.currentIndex - history.size
    // The played tracks sit above the fold rather than at the top: opening the queue should land
    // on what plays next, with history there for anyone who scrolls back for it.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = remember { if (history.isEmpty()) 0 else history.size + 1 },
    )
    if (playback.queue.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                QueueNotice("Queue is empty", "Play an album, playlist, or song to get started.")
            }
            AutoplayFooter(
                enabled = autoplayEnabled,
                loading = autoplayLoading,
                error = autoplayError,
                onEnabled = onAutoplayEnabled,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
    ) {
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
        item {
            AutoplayFooter(
                enabled = autoplayEnabled,
                loading = autoplayLoading,
                error = autoplayError,
                onEnabled = onAutoplayEnabled,
            )
        }
    }
}

/**
 * Mirrors desktop's queue-panel footer: the same switch as Settings, plus whatever Autoplay is
 * currently doing. The status line is the only place a listener finds out that recommendations are
 * loading or that the radio ran out, so it stays visible even when the queue is empty.
 */
@Composable
private fun AutoplayFooter(
    enabled: Boolean,
    loading: Boolean,
    error: String,
    onEnabled: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.AllInclusive,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Autoplay",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Text(
                when {
                    !enabled -> "Off"
                    loading -> "Finding more music…"
                    error.isNotBlank() -> error
                    else -> "Keep the music going"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabled,
            enabled = onEnabled != null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LocalAccent.current,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )
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
