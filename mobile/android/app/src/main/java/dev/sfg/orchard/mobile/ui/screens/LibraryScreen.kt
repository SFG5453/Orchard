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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.sfg.orchard.mobile.download.DownloadItem
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.local.toPlaylist
import dev.sfg.orchard.mobile.local.toTrack
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.components.filterCatalogItems
import dev.sfg.orchard.mobile.ui.components.filterTracks
import dev.sfg.orchard.mobile.ui.components.normalizeSearchText
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyColumn as LazyColumn

private const val NEW_RELEASES_ID = "FEmusic_new_releases"

@Composable
fun LibraryScreen(
    library: LibrarySnapshot,
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    downloads: List<DownloadItem> = emptyList(),
    downloadedTrackIds: Set<String> = emptySet(),
    downloadingTrackIds: Set<String> = emptySet(),
    totalBytesUsed: Long = 0L,
    onPlay: (Track) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAddToQueue: ((Track) -> Unit)?,
    onOpenDetail: (String) -> Unit,
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onShare: ((Track) -> Unit)? = null,
    localLibrary: dev.sfg.orchard.mobile.local.LocalLibraryRepository? = null,
    localSnapshot: dev.sfg.orchard.mobile.local.LocalSnapshot = dev.sfg.orchard.mobile.local.LocalSnapshot(),
    localBusy: Boolean = false,
    youtubeAvailable: Boolean = false,
    onCreateYouTubePlaylist: (String) -> Unit = {},
) {
    var searchQuery by remember { mutableStateOf("") }
    val searching = searchQuery.isNotBlank()
    val browse = { onOpenDetail(NEW_RELEASES_ID) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = OrchardChromeHeight)) {
        item {
            androidx.compose.foundation.layout.Column {
                LibraryHeader(library.summary(localSnapshot.songs.size))
                localLibrary?.let { repository ->
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    LocalLibraryActions(
                        repository = repository,
                        busy = localBusy,
                        youtubeAvailable = youtubeAvailable,
                        onCreateYouTubePlaylist = onCreateYouTubePlaylist,
                        onOpenPlaylist = onOpenDetail,
                    )
                }
                LibraryChips(filter, onFilterChange)
                LibraryFilterField(searchQuery, "Filter ${filter.label.lowercase()}") { searchQuery = it }
            }
        }
        when (filter) {
            LibraryFilter.PLAYLISTS -> collections(
                "Your playlists",
                filterCatalogItems(library.savedPlaylists.map { CatalogItem.Collection(it) }, searchQuery),
                Icons.Rounded.LibraryMusic,
                if (searching) "No matching playlists" else "No saved playlists",
                if (searching) "No playlists matching \"$searchQuery\"" else "Save a playlist and it will stay close at hand.",
                onOpenDetail,
                browse.takeUnless { searching },
            )
            LibraryFilter.ARTISTS -> collections(
                "Saved artists",
                filterCatalogItems(library.savedArtists.map { CatalogItem.Performer(it) }, searchQuery),
                Icons.Rounded.Person,
                if (searching) "No matching artists" else "No saved artists",
                if (searching) "No artists matching \"$searchQuery\"" else "Follow an artist to build this shelf.",
                onOpenDetail,
                browse.takeUnless { searching },
            )
            LibraryFilter.ALBUMS -> collections(
                "Saved albums",
                filterCatalogItems(library.savedAlbums.map { CatalogItem.Record(it) }, searchQuery),
                Icons.Rounded.Album,
                if (searching) "No matching albums" else "No saved albums",
                if (searching) "No albums matching \"$searchQuery\"" else "Albums you save will be available here.",
                onOpenDetail,
                browse.takeUnless { searching },
            )
            LibraryFilter.SONGS -> tracks(
                title = "Liked songs",
                values = filterTracks(library.likedTracks, searchQuery),
                emptyIcon = Icons.Rounded.MusicNote,
                emptyTitle = if (searching) "No matching songs" else "No songs saved",
                emptyMessage = if (searching) "No songs matching \"$searchQuery\"" else "Save songs to your library to see them here",
                downloadedTrackIds = downloadedTrackIds,
                downloadingTrackIds = downloadingTrackIds,
                onPlay = onPlay,
                onPlayNext = onPlayNext,
                onAdd = onAddToQueue,
                onDownloadTrack = onDownloadTrack,
                onRemoveDownloadTrack = onRemoveDownloadTrack,
                onShare = onShare,
                onOpen = onOpenDetail,
                onBrowse = browse.takeUnless { searching },
            )
            LibraryFilter.RECENT -> tracks(
                title = "Recently played",
                values = filterTracks(library.recentlyPlayed, searchQuery),
                emptyIcon = Icons.Rounded.History,
                emptyTitle = if (searching) "No matching recent tracks" else "Nothing played yet",
                emptyMessage = if (searching) "No recently played tracks matching \"$searchQuery\"" else "Start a song and Orchard will remember it here.",
                downloadedTrackIds = downloadedTrackIds,
                downloadingTrackIds = downloadingTrackIds,
                onPlay = onPlay,
                onPlayNext = onPlayNext,
                onAdd = onAddToQueue,
                onDownloadTrack = onDownloadTrack,
                onRemoveDownloadTrack = onRemoveDownloadTrack,
                onShare = onShare,
                onOpen = onOpenDetail,
            )
            LibraryFilter.LOCAL -> {
                collections(
                    "Local playlists",
                    filterCatalogItems(localSnapshot.playlists.map { CatalogItem.Collection(it.toPlaylist()) }, searchQuery),
                    Icons.Rounded.LibraryMusic,
                    if (searching) "No matching playlists" else "No local playlists",
                    if (searching) "No playlists matching \"$searchQuery\"" else "Make one with New playlist, then add songs from this phone.",
                    onOpenDetail,
                )
                tracks(
                    title = "Local songs",
                    values = filterTracks(localSnapshot.songs.map { it.toTrack() }, searchQuery),
                    emptyIcon = Icons.Rounded.MusicNote,
                    emptyTitle = if (searching) "No matching songs" else "No local songs",
                    emptyMessage = if (searching) "No songs matching \"$searchQuery\"" else "Use Add local files to bring in music from this phone.",
                    downloadedTrackIds = emptySet(),
                    onPlay = onPlay,
                    onPlayNext = onPlayNext,
                    onAdd = onAddToQueue,
                    onShare = null,
                )
            }
            LibraryFilter.DOWNLOADS -> {
                val query = normalizeSearchText(searchQuery)
                val filtered = if (query.isNotBlank()) {
                    downloads.filter {
                        normalizeSearchText(it.track.title).contains(query) ||
                            normalizeSearchText(it.track.artist).contains(query) ||
                            normalizeSearchText(it.track.album).contains(query)
                    }
                } else downloads
                downloadsList(
                    downloads = filtered,
                    totalBytesUsed = totalBytesUsed,
                    onPlay = onPlay,
                    onRemoveDownload = { id -> onRemoveDownloadTrack?.invoke(id) },
                )
            }
        }
    }
}

private fun LibrarySnapshot.summary(localSongs: Int): String {
    if (likedTracks.isEmpty() && savedAlbums.isEmpty() && savedArtists.isEmpty() && savedPlaylists.isEmpty() && localSongs == 0) {
        return "Nothing saved yet"
    }
    val local = if (localSongs > 0) ", $localSongs on this phone" else ""
    return "${likedTracks.size} songs, ${savedAlbums.size} albums, ${savedArtists.size} artists$local"
}
