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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.TrackActionsPopup

/**
 * Collapse, where the queue came from, and the track menu. The whole row is the drag handle,
 * which gives the dismiss gesture a full-width grab target.
 */
@Composable
internal fun PlayerHeader(
    track: Track,
    playingFrom: String,
    onCollapse: () -> Unit,
    modifier: Modifier,
    onViewQueue: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    isDownloaded: Boolean,
    onDownload: (() -> Unit)?,
    onRemoveDownload: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    if (menuOpen) {
        TrackActionsPopup(
            track = track,
            onDismiss = { menuOpen = false },
            onViewQueue = onViewQueue,
            onAddToPlaylist = onAddToPlaylist,
            onDownload = if (!isDownloaded) onDownload else null,
            onRemoveDownload = if (isDownloaded) onRemoveDownload else null,
            onShare = onShare,
            onViewArtist = onOpenArtist,
            onViewAlbum = onOpenAlbum,
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = "Close player",
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "PLAYING FROM",
                color = Color.White.copy(alpha = 0.60f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                ),
            )
            Text(
                text = playingFrom,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Filled.MoreHoriz,
                contentDescription = "More options",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
