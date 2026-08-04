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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.OrchardFilterChips
import dev.sfg.orchard.mobile.ui.components.OrchardSectionHeader
import dev.sfg.orchard.mobile.ui.components.TrackRow
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight

@Composable
fun LibraryScreen(
    library: LibrarySnapshot,
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    onPlay: (Track) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAddToQueue: ((Track) -> Unit)?,
    onOpenDetail: (String) -> Unit,
    onShare: ((Track) -> Unit)? = null,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = OrchardChromeHeight)) {
        item {
            Column {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 12.dp)) {
                    Text("Library", style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold))
                }
                OrchardFilterChips(
                    options = LibraryFilter.entries,
                    selected = filter,
                    label = { it.label },
                    onSelect = onFilterChange,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        when (filter) {
            LibraryFilter.PLAYLISTS -> collections(
                "Your playlists",
                library.savedPlaylists.map { CatalogItem.Collection(it) },
                "No saved playlists",
                "Save a playlist and it will stay close at hand.",
                onOpenDetail,
            )
            LibraryFilter.ARTISTS -> collections(
                "Saved artists",
                library.savedArtists.map { CatalogItem.Performer(it) },
                "No saved artists",
                "Follow an artist to build this shelf.",
                onOpenDetail,
            )
            LibraryFilter.ALBUMS -> collections(
                "Saved albums",
                library.savedAlbums.map { CatalogItem.Record(it) },
                "No saved albums",
                "Albums you save will be available here.",
                onOpenDetail,
            )
            LibraryFilter.SONGS -> tracks(
                "Liked songs",
                library.likedTracks,
                "No liked songs",
                "Tap the heart in Now Playing to save a track.",
                onPlay,
                onPlayNext,
                onAddToQueue,
                onShare,
                onOpenDetail,
            )
            LibraryFilter.RECENT -> tracks(
                "Recently played",
                library.recentlyPlayed,
                "Nothing played yet",
                "Start a song and Orchard will remember it here.",
                onPlay,
                onPlayNext,
                onAddToQueue,
                onShare,
                onOpenDetail,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.collections(
    title: String,
    values: List<CatalogItem>,
    emptyTitle: String,
    emptyMessage: String,
    onOpen: (String) -> Unit,
) {
    if (values.isEmpty()) {
        item {
            MessagePanel(emptyTitle, emptyMessage)
        }
        return
    }

    item {
        OrchardSectionHeader(title)
    }

    val rows = values.chunked(2)

    items(
        items = rows,
        key = { row ->
            row.joinToString("|") { item -> item.stableId }
        },
    ) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            row.forEach { catalogItem ->
                Box(
                    modifier = Modifier.weight(1f),
                ) {
                    CatalogCard(
                        catalogItem,
                        { onOpen(catalogItem.stableId) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (row.size < 2) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.tracks(
    title: String,
    values: List<Track>,
    emptyTitle: String,
    emptyMessage: String,
    onPlay: (Track) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAdd: ((Track) -> Unit)?,
    onShare: ((Track) -> Unit)? = null,
    onOpen: ((String) -> Unit)? = null,
) {
    if (values.isEmpty()) {
        item { MessagePanel(emptyTitle, emptyMessage) }
        return
    }
    item { OrchardSectionHeader(title) }
    items(values, key = Track::id) { track ->
        TrackRow(
            track = track,
            onPlay = { onPlay(track) },
            onPlayNext = onPlayNext?.let { action -> { action(track) } },
            onAddToQueue = onAdd?.let { action -> { action(track) } },
            onShare = onShare?.let { action -> { action(track) } },
            onViewAlbum = onOpen?.takeIf { track.albumId.isNotBlank() }?.let { nav -> { nav(track.albumId) } },
            onViewArtist = onOpen?.takeIf { track.artistId.isNotBlank() }?.let { nav -> { nav(track.artistId) } },
        )
    }
}

private val LibraryFilter.label: String
    get() = when (this) {
        LibraryFilter.PLAYLISTS -> "Playlists"
        LibraryFilter.ARTISTS -> "Artists"
        LibraryFilter.ALBUMS -> "Albums"
        LibraryFilter.SONGS -> "Songs"
        LibraryFilter.RECENT -> "Recent"
    }
