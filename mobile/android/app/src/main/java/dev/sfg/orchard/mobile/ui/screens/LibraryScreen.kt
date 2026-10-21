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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.sfg.orchard.mobile.download.DownloadItem
import dev.sfg.orchard.mobile.download.DownloadStatus
import dev.sfg.orchard.mobile.local.LocalLibraryRepository
import dev.sfg.orchard.mobile.local.LocalSnapshot
import dev.sfg.orchard.mobile.local.toPlaylist
import dev.sfg.orchard.mobile.local.toTrack
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.model.LibrarySnapshot
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
    onPlayAll: (tracks: List<Track>, shuffle: Boolean) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAddToQueue: ((Track) -> Unit)?,
    onOpenDetail: (String) -> Unit,
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onShare: ((Track) -> Unit)? = null,
    localLibrary: LocalLibraryRepository? = null,
    localSnapshot: LocalSnapshot = LocalSnapshot(),
    localBusy: Boolean = false,
    /** Where online playlists are created, or null when no account can hold one. */
    onlineService: String? = null,
    onCreateOnlinePlaylist: (String) -> Unit = {},
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.ADDED) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    val searching = searchQuery.isNotBlank()
    val browse = { onOpenDetail(NEW_RELEASES_ID) }.takeUnless { searching }
    val completedDownloads = downloads.count { it.status == DownloadStatus.COMPLETED }

    fun LazyListScope.shelf(key: String, values: List<CatalogItem>, noun: Pair<String, String>, columns: Int, empty: ShelfEmpty) {
        if (values.isEmpty()) return emptyShelf(key, empty)
        item(key = "$key:toolbar") {
            LibraryToolbar(countLabel(values.size, noun.first, noun.second), sort, { sort = it }, grid, { grid = it })
        }
        collections(key, values.sortedBy(sort), grid, columns, onOpenDetail)
    }

    fun LazyListScope.songs(key: String, values: List<Track>, count: String, sortable: Boolean, empty: ShelfEmpty, local: Boolean = false) {
        if (values.isEmpty()) return emptyShelf(key, empty)
        val shown = if (sortable) values.sortedBy(sort) else values
        item(key = "$key:play") { LibraryPlayButtons({ onPlayAll(shown, false) }, { onPlayAll(shown, true) }) }
        item(key = "$key:toolbar") { LibraryToolbar(count, sort.takeIf { sortable }, { sort = it }) }
        tracks(
            key = key,
            values = shown,
            downloadedTrackIds = if (local) emptySet() else downloadedTrackIds,
            downloadingTrackIds = if (local) emptySet() else downloadingTrackIds,
            onPlay = onPlay,
            onPlayNext = onPlayNext,
            onAdd = onAddToQueue,
            onDownloadTrack = onDownloadTrack.takeUnless { local },
            onRemoveDownloadTrack = onRemoveDownloadTrack.takeUnless { local },
            onShare = onShare.takeUnless { local },
            onOpen = onOpenDetail.takeUnless { local },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = OrchardChromeHeight)) {
        item(key = "header") {
            Column {
                LibraryHeader(
                    summary = library.summary(localSnapshot.songs.size),
                    searchOpen = searchOpen,
                    onToggleSearch = {
                        if (searchOpen) searchQuery = ""
                        searchOpen = !searchOpen
                    },
                    busy = localBusy,
                    onCreate = localLibrary?.let { { creating = true } },
                )
                LibraryChips(filter, onFilterChange)
                if (searchOpen) LibraryFilterField(searchQuery, "Filter ${filter.label.lowercase()}") { searchQuery = it }
            }
        }
        when (filter) {
            LibraryFilter.PLAYLISTS -> {
                if (!searching) {
                    item(key = "shortcuts") {
                        LibraryShortcuts(
                            likedCount = library.likedTracks.size,
                            downloadedCount = completedDownloads,
                            onLiked = { onFilterChange(LibraryFilter.SONGS) },
                            onDownloads = { onFilterChange(LibraryFilter.DOWNLOADS) },
                        )
                    }
                }
                shelf(
                    "playlists",
                    filterCatalogItems(library.savedPlaylists.map { CatalogItem.Collection(it) }, searchQuery),
                    "playlist" to "playlists", 2,
                    if (searching) ShelfEmpty(Icons.Rounded.LibraryMusic, "No matching playlists", "No playlists matching \"$searchQuery\"")
                    else ShelfEmpty(Icons.Rounded.LibraryMusic, "No saved playlists", "Save a playlist and it will stay close at hand.", browse),
                )
            }
            LibraryFilter.ARTISTS -> shelf(
                "artists",
                filterCatalogItems(library.savedArtists.map { CatalogItem.Performer(it) }, searchQuery),
                "artist" to "artists", 3,
                if (searching) ShelfEmpty(Icons.Rounded.Person, "No matching artists", "No artists matching \"$searchQuery\"")
                else ShelfEmpty(Icons.Rounded.Person, "No saved artists", "Follow an artist to build this shelf.", browse),
            )
            LibraryFilter.ALBUMS -> shelf(
                "albums",
                filterCatalogItems(library.savedAlbums.map { CatalogItem.Record(it) }, searchQuery),
                "album" to "albums", 2,
                if (searching) ShelfEmpty(Icons.Rounded.Album, "No matching albums", "No albums matching \"$searchQuery\"")
                else ShelfEmpty(Icons.Rounded.Album, "No saved albums", "Albums you save will be available here.", browse),
            )
            LibraryFilter.SONGS -> {
                val values = filterTracks(library.likedTracks, searchQuery)
                songs(
                    "liked", values, countLabel(values.size, "liked song", "liked songs"), sortable = true,
                    empty = if (searching) ShelfEmpty(Icons.Rounded.MusicNote, "No matching songs", "No songs matching \"$searchQuery\"")
                    else ShelfEmpty(Icons.Rounded.MusicNote, "No songs saved", "Save songs to your library to see them here", browse),
                )
            }
            LibraryFilter.RECENT -> {
                val values = filterTracks(library.recentlyPlayed, searchQuery)
                songs(
                    "recent", values, countLabel(values.size, "song played recently", "songs played recently"), sortable = false,
                    empty = if (searching) ShelfEmpty(Icons.Rounded.History, "No matching recent tracks", "No recently played tracks matching \"$searchQuery\"")
                    else ShelfEmpty(Icons.Rounded.History, "Nothing played yet", "Start a song and Orchard will remember it here."),
                )
            }
            LibraryFilter.LOCAL -> {
                val playlists = filterCatalogItems(localSnapshot.playlists.map { CatalogItem.Collection(it.toPlaylist()) }, searchQuery)
                val songs = filterTracks(localSnapshot.songs.map { it.toTrack() }, searchQuery)
                item(key = "local:playlists-label") { LibraryLabel("Playlists on this phone") }
                shelf(
                    "local-playlists", playlists, "playlist" to "playlists", 2,
                    if (searching) ShelfEmpty(Icons.Rounded.LibraryMusic, "No matching playlists", "No playlists matching \"$searchQuery\"")
                    else ShelfEmpty(Icons.Rounded.LibraryMusic, "No local playlists", "Tap + and choose New playlist, then add songs from this phone."),
                )
                item(key = "local:songs-label") { LibraryLabel("Songs on this phone") }
                songs(
                    "local-songs", songs, countLabel(songs.size, "song", "songs"), sortable = true, local = true,
                    empty = if (searching) ShelfEmpty(Icons.Rounded.MusicNote, "No matching songs", "No songs matching \"$searchQuery\"")
                    else ShelfEmpty(Icons.Rounded.MusicNote, "No local songs", "Tap + and choose Add songs or Add a folder."),
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

    localLibrary?.let { repository ->
        LibraryCreateSheet(
            open = creating,
            onDismiss = { creating = false },
            repository = repository,
            service = onlineService,
            onCreateOnlinePlaylist = onCreateOnlinePlaylist,
            onOpenPlaylist = onOpenDetail,
        )
    }
}

@JvmName("sortedCatalogItems")
private fun List<CatalogItem>.sortedBy(sort: LibrarySort): List<CatalogItem> =
    if (sort == LibrarySort.TITLE) sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) else this

@JvmName("sortedTracks")
private fun List<Track>.sortedBy(sort: LibrarySort): List<Track> =
    if (sort == LibrarySort.TITLE) sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) else this

private fun LibrarySnapshot.summary(localSongs: Int): String {
    if (likedTracks.isEmpty() && savedAlbums.isEmpty() && savedArtists.isEmpty() && savedPlaylists.isEmpty() && localSongs == 0) {
        return "Nothing saved yet"
    }
    val local = if (localSongs > 0) " · $localSongs on this phone" else ""
    return "${likedTracks.size} songs · ${savedAlbums.size} albums · ${savedArtists.size} artists$local"
}
