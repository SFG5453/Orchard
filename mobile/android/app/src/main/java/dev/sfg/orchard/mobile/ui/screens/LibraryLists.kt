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

import dev.sfg.orchard.mobile.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.download.DownloadItem
import dev.sfg.orchard.mobile.download.DownloadStatus
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.components.TrackRow
import dev.sfg.orchard.mobile.ui.components.catalogSubtitle
import dev.sfg.orchard.mobile.ui.motion.riseIn

/** What an empty shelf says, plus an optional jump to the new releases page. */
internal data class ShelfEmpty(
    val icon: ImageVector,
    val title: String,
    val message: String,
    val onBrowse: (() -> Unit)? = null,
)

internal fun LazyListScope.emptyShelf(key: String, empty: ShelfEmpty) {
    item(key = "$key:empty") {
        LibraryEmpty(empty.icon, empty.title, empty.message, empty.onBrowse?.let { "Browse new releases" }, empty.onBrowse ?: {})
    }
}

/** Playlists, albums or artists as a grid of cards or a list of rows. */
internal fun LazyListScope.collections(
    key: String,
    values: List<CatalogItem>,
    grid: Boolean,
    columns: Int,
    onOpen: (String) -> Unit,
) {
    if (!grid) {
        items(values, key = { "$key:${it.stableId}" }) { item ->
            CollectionRow(item, { onOpen(item.stableId) }, Modifier.animateItem(fadeInSpec = null))
        }
        return
    }
    val rows = values.chunked(columns)
    itemsIndexed(rows, key = { _, row -> "$key:" + row.joinToString("|") { it.stableId } }) { _, row ->
        Row(
            Modifier
                .animateItem(fadeInSpec = null)
                .fillMaxWidth()
                .padding(horizontal = LibraryGutter)
                .padding(top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (columns > 2) 14.dp else 16.dp),
        ) {
            row.forEachIndexed { col, item ->
                Box(Modifier.weight(1f).riseIn(col, fromScale = 0.9f, cascadeOnScroll = true)) {
                    CatalogCard(item, { onOpen(item.stableId) }, modifier = Modifier.fillMaxWidth())
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun CollectionRow(item: CatalogItem, onClick: () -> Unit, modifier: Modifier) {
    val round = item is CatalogItem.Performer
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LibraryGutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkTile(item.artworkUrl, item.title, Modifier.size(56.dp), if (round) 999 else 10)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tr(item.title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = SettingsStyle.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = catalogSubtitle(item)
            if (subtitle.isNotBlank()) {
                Text(
                    tr(subtitle),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = SettingsStyle.Description,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun LazyListScope.tracks(
    key: String,
    values: List<Track>,
    downloadedTrackIds: Set<String>,
    downloadingTrackIds: Set<String> = emptySet(),
    onPlay: (Track) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAdd: ((Track) -> Unit)?,
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onShare: ((Track) -> Unit)? = null,
    onOpen: ((String) -> Unit)? = null,
) {
    itemsIndexed(values, key = { index, track -> "$key:${track.id}_$index" }) { index, track ->
        TrackRow(
            track = track,
            onPlay = { onPlay(track) },
            modifier = Modifier
                .animateItem(fadeInSpec = null)
                .riseIn(index, cascadeOnScroll = true),
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
            "downloads",
            ShelfEmpty(Icons.Rounded.Download, tr("No downloaded tracks"), "Tap the download icon on any track, album, or playlist to listen offline."),
        )
        return
    }

    if (active.isNotEmpty()) {
        item(key = "downloads:active") { LibraryLabel("Downloading (${active.size})") }
        items(active, key = { "active_${it.track.id}" }) { item ->
            DownloadingRow(item = item, onCancel = { onRemoveDownload(item.track.id) }, modifier = Modifier.animateItem())
        }
    }

    if (completed.isNotEmpty()) {
        item(key = "downloads:completed") {
            LibraryLabel("Downloaded (${completed.size}) · ${formatStorageSize(totalBytesUsed)} used")
        }
        items(completed, key = { "completed_${it.track.id}" }) { item ->
            DownloadedTrackRow(
                modifier = Modifier.animateItem(),
                item = item,
                onPlay = { onPlay(item.track) },
                onDelete = { onRemoveDownload(item.track.id) },
            )
        }
    }

    if (failed.isNotEmpty()) {
        item(key = "downloads:failed") { LibraryLabel("Failed (${failed.size})", MaterialTheme.colorScheme.error) }
        items(failed, key = { "failed_${it.track.id}" }) { item ->
            DownloadingRow(item = item, onCancel = { onRemoveDownload(item.track.id) }, modifier = Modifier.animateItem())
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
