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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/** Bottom sheet popup shown when tapping the "..." button on a track row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackActionsPopup(
    track: Track,
    onDismiss: () -> Unit,
    // Null where the action does not apply; the now playing player already plays this track,
    // so it offers no "Play Now" but does offer the queue.
    onPlay: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onViewQueue: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onViewAlbum: (() -> Unit)? = null,
    onViewArtist: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CanopyColors.Surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CanopyColors.Muted.copy(alpha = 0.4f)) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            // Track preview header
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkTile(track.artworkUrl, track.title, Modifier.size(52.dp), 10)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = CanopyColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (track.artist.isNotBlank()) {
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CanopyColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(CanopyColors.Rule))
            Spacer(Modifier.height(6.dp))

            // Action items
            onPlay?.let { action ->
                PopupActionRow(Icons.Rounded.PlayArrow, "Play Now") { onDismiss(); action() }
            }
            onPlayNext?.let { action ->
                PopupActionRow(Icons.Rounded.SkipNext, "Play Next") { onDismiss(); action() }
            }
            onAddToQueue?.let { action ->
                PopupActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to Queue") { onDismiss(); action() }
            }
            onViewQueue?.let { action ->
                PopupActionRow(Icons.AutoMirrored.Rounded.List, "View Queue") { onDismiss(); action() }
            }
            onDownload?.let { action ->
                PopupActionRow(Icons.Rounded.Download, "Download Offline") { onDismiss(); action() }
            }
            onRemoveDownload?.let { action ->
                PopupActionRow(Icons.Rounded.Delete, "Remove Download") { onDismiss(); action() }
            }
            onViewAlbum?.let { action ->
                if (track.albumId.isNotBlank()) {
                    PopupActionRow(Icons.Rounded.Album, "View Album") { onDismiss(); action() }
                }
            }
            onViewArtist?.let { action ->
                if (track.artistId.isNotBlank()) {
                    PopupActionRow(Icons.Rounded.Person, "View Artist") { onDismiss(); action() }
                }
            }
            onShare?.let { action ->
                PopupActionRow(Icons.Rounded.Share, "Share Song") { onDismiss(); action() }
            }
        }
    }
}

@Composable
private fun PopupActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = CanopyColors.Text,
            )
        }
    }
}
