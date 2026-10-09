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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.BestMixJob
import dev.sfg.orchard.mobile.model.LocalBestMixJob
import dev.sfg.orchard.mobile.model.Track
import androidx.compose.foundation.interaction.MutableInteractionSource
import dev.sfg.orchard.mobile.ui.motion.pressScale
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.ExplicitBadge

@Composable
internal fun QueueSectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
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
internal fun QueueShuffleButton(onShuffleUpcoming: () -> Unit) {
    IconButton(onClick = onShuffleUpcoming, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Rounded.Shuffle,
            "Shuffle upcoming queue",
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Reorders what is up next for the smoothest mixes; spins while the planner works. */
@Composable
internal fun QueueBestMixButton(
    onBestMixUpcoming: (onProgress: (String) -> Unit, onComplete: () -> Unit) -> Unit,
) {
    val job = LocalBestMixJob.current
    val sorting = job?.key == BestMixJob.QUEUE_KEY
    IconButton(
        onClick = { onBestMixUpcoming({}, {}) },
        enabled = job == null,
        modifier = Modifier.size(36.dp),
    ) {
        if (sorting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White.copy(alpha = 0.75f),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Rounded.AutoAwesome,
                "Order upcoming for the best mix",
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun QueueClearButton(onClearUpcoming: () -> Unit) {
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
internal fun QueueNotice(title: String, message: String) {
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
internal fun QueueTrackRow(
    track: Track,
    index: Int,
    queueSize: Int,
    editable: Boolean,
    isHistory: Boolean,
    onPlay: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onPlay,
        color = Color.Transparent,
        interactionSource = source,
        modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).pressScale(source, 0.97f),
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
