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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.download.DownloadItem
import dev.sfg.orchard.mobile.download.DownloadStatus
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.components.TrackRow

/** Empty shelf with an optional jump to the new releases page. */
private fun LazyListScope.emptyShelf(
    icon: ImageVector,
    title: String,
    message: String,
    onBrowse: (() -> Unit)?,
) {
    item { LibraryEmpty(icon, title, message, onBrowse?.let { "Browse new releases" }, onBrowse ?: {}) }
}

internal fun LazyListScope.downloadsList(
    downloads: List<DownloadItem>,
    totalBytesUsed: Long,
    onPlay: (Track) -> Unit,
    onRemoveDownload: (String) -> Unit,
) {
    val completed = downloads.filter { it.status == DownloadStatus.COMPLETED }
    val active = downloads.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
    val failed = downloads.filter { it.status == DownloadStatus.FAILED }

    if (active.isEmpty() && completed.isEmpty() && failed.isEmpty()) {
        emptyShelf(
            Icons.Rounded.Download,
            "No downloaded tracks",
            "Tap the download icon on any track, album, or playlist to listen offline.",
            null,
        )
        return
    }

    if (active.isNotEmpty()) {
        item { LibraryLabel("Downloading (${active.size})") }
        itemsIndexed(active, key = { _, it -> "active_${it.track.id}" }) { index, item ->
            DownloadingRow(
                item = item,
                onCancel = { onRemoveDownload(item.track.id) },
                modifier = Modifier.animateItem().panelSegment(index == 0, index == active.lastIndex),
            )
        }
    }

    if (completed.isNotEmpty()) {
        item { LibraryLabel("Downloaded (${completed.size}) \u00b7 ${formatStorageSize(totalBytesUsed)} used") }
        itemsIndexed(completed, key = { _, it -> "completed_${it.track.id}" }) { index, item ->
            DownloadedTrackRow(
                modifier = Modifier.animateItem().panelSegment(index == 0, index == completed.lastIndex),
                item = item,
                onPlay = { onPlay(item.track) },
                onDelete = { onRemoveDownload(item.track.id) },
            )
        }
    }

    if (failed.isNotEmpty()) {
        item { LibraryLabel("Failed (${failed.size})", MaterialTheme.colorScheme.error) }
        itemsIndexed(failed, key = { _, it -> "failed_${it.track.id}" }) { index, item ->
            DownloadingRow(
                item = item,
                onCancel = { onRemoveDownload(item.track.id) },
                modifier = Modifier.animateItem().panelSegment(index == 0, index == failed.lastIndex),
            )
        }
    }
}

internal fun LazyListScope.tracks(
    title: String,
    values: List<Track>,
    emptyIcon: ImageVector,
    emptyTitle: String,
    emptyMessage: String,
    downloadedTrackIds: Set<String>,
    downloadingTrackIds: Set<String> = emptySet(),
    onPlay: (Track) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAdd: ((Track) -> Unit)?,
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onShare: ((Track) -> Unit)? = null,
    onOpen: ((String) -> Unit)? = null,
    onBrowse: (() -> Unit)? = null,
) {
    if (values.isEmpty()) {
        emptyShelf(emptyIcon, emptyTitle, emptyMessage, onBrowse)
        return
    }
    item { LibraryLabel(title) }
    itemsIndexed(values, key = { index, track -> "${track.id}_$index" }) { index, track ->
        TrackRow(
            track = track,
            onPlay = { onPlay(track) },
            modifier = Modifier
                .animateItem(fadeInSpec = null)
                .riseIn(index, cascadeOnScroll = true)
                .panelSegment(index == 0, index == values.lastIndex),
            onPlayNext = onPlayNext?.let { action -> { action(track) } },
            onAddToQueue = onAdd?.let { action -> { action(track) } },
            onDownload = onDownloadTrack?.let { action -> { action(track) } },
            onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
            isDownloaded = downloadedTrackIds.contains(track.id),
            isDownloading = downloadingTrackIds.contains(track.id),
            onShare = onShare?.let { action -> { action(track) } },
            onViewAlbum = onOpen?.takeIf { track.albumId.isNotBlank() }?.let { nav -> { nav(track.albumId) } },
            onViewArtist = onOpen?.takeIf { track.artistId.isNotBlank() }?.let { nav -> { nav(track.artistId) } },
        )
    }
}

internal fun LazyListScope.collections(
    title: String,
    values: List<CatalogItem>,
    emptyIcon: ImageVector,
    emptyTitle: String,
    emptyMessage: String,
    onOpen: (String) -> Unit,
    onBrowse: (() -> Unit)? = null,
) {
    if (values.isEmpty()) {
        emptyShelf(emptyIcon, emptyTitle, emptyMessage, onBrowse)
        return
    }

    item { LibraryLabel(title) }

    val rows = values.chunked(2)

    itemsIndexed(
        items = rows,
        key = { _, row -> row.joinToString("|") { item -> item.stableId } },
    ) { index, row ->
        Row(
            modifier = Modifier
                .animateItem(fadeInSpec = null)
                .panelSegment(index == 0, index == rows.lastIndex)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            row.forEachIndexed { col, catalogItem ->
                Box(Modifier.weight(1f).riseIn(col, fromScale = 0.9f, cascadeOnScroll = true)) {
                    CatalogCard(catalogItem, { onOpen(catalogItem.stableId) }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (row.size < 2) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

internal val LibraryFilter.label: String
    get() = when (this) {
        LibraryFilter.PLAYLISTS -> "Playlists"
        LibraryFilter.ARTISTS -> "Artists"
        LibraryFilter.ALBUMS -> "Albums"
        LibraryFilter.SONGS -> "Songs"
        LibraryFilter.RECENT -> "Recent"
        LibraryFilter.DOWNLOADS -> "Downloads"
        LibraryFilter.LOCAL -> "Local"
    }
