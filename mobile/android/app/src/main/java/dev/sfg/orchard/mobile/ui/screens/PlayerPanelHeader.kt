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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.TrackActionsPopup

/**
 * Header while lyrics or the queue fill the cover's slot: a drag handle, then the playing track
 * as a compact row. The title row below the panel is hidden so the panel gets the room.
 */
@Composable
internal fun PlayerPanelHeader(
    track: Track,
    liked: Boolean,
    showLike: Boolean,
    onCollapse: () -> Unit,
    handleModifier: Modifier,
    onViewQueue: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    isDownloaded: Boolean,
    onDownload: (() -> Unit)?,
    onRemoveDownload: (() -> Unit)?,
    onWatchVideo: (() -> Unit)? = null,
    onLiked: () -> Unit,
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
            onWatchVideo = onWatchVideo,
            onViewArtist = onOpenArtist,
            onViewAlbum = onOpenAlbum,
        )
    }

    Column(Modifier.fillMaxWidth()) {
        PlayerTopHandle(onDismiss = onCollapse, modifier = handleModifier)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkTile(track.artworkUrl, "Artwork for ${track.title}", Modifier.size(56.dp), 8)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onOpenArtist != null) Modifier.clickable(onClick = onOpenArtist) else Modifier,
                )
            }
            if (showLike) {
                TrackActionButtons(liked = liked, onLiked = onLiked, onOpenMenu = { menuOpen = true })
            } else {
                OverflowButton(onClick = { menuOpen = true })
            }
        }
    }
}
