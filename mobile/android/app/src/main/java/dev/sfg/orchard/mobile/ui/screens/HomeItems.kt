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

import dev.sfg.orchard.mobile.catalog.PlaylistActions
import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track

// Pure derivations behind the Home sections. Recomposition keys live with the callers.

internal fun offlinePlaylistItems(
    downloadedTracks: List<Track>,
    downloadedTrackIds: Set<String>,
    savedPlaylists: List<Playlist>,
): List<CatalogItem.Collection> = buildList {
    if (downloadedTracks.isNotEmpty()) {
        add(
            CatalogItem.Collection(
                Playlist(
                    id = "offline_downloads",
                    title = "Downloaded Music",
                    author = "Orchard",
                    artworkUrl =
                        downloadedTracks
                            .firstOrNull { it.artworkUrl.isNotBlank() }
                            ?.artworkUrl
                            .orEmpty(),
                    description = "All offline tracks on this device",
                    tracks = downloadedTracks,
                )
            )
        )
    }
    savedPlaylists.forEach { playlist ->
        val matching = playlist.tracks.filter { it.id in downloadedTrackIds }
        if (matching.isNotEmpty()) {
            add(CatalogItem.Collection(playlist.copy(tracks = matching)))
        }
    }
}

internal fun offlineArtistItems(
    downloadedTracks: List<Track>,
    savedArtists: List<Artist>,
): List<CatalogItem.Performer> {
    val grouped = mutableMapOf<String, MutableList<Track>>()
    downloadedTracks.forEach { track ->
        if (track.artist.isNotBlank()) {
            grouped.getOrPut(track.artist) { mutableListOf() }.add(track)
        }
    }
    return grouped.map { (artistName, artistTracks) ->
        val artistId =
            artistTracks.firstOrNull { it.artistId.isNotBlank() }?.artistId ?: artistName
        val artworkUrl =
            savedArtists
                .firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                ?.artworkUrl
                ?: artistTracks
                    .firstOrNull { it.artworkUrl.isNotBlank() }
                    ?.artworkUrl
                    .orEmpty()
        CatalogItem.Performer(
            Artist(
                id = artistId,
                name = artistName,
                artworkUrl = artworkUrl,
                subtitle =
                    "${artistTracks.size} downloaded ${if (artistTracks.size == 1) "song" else "songs"}",
            )
        )
    }
}

internal fun offlineAlbumItems(downloadedTracks: List<Track>): List<CatalogItem.Record> {
    val grouped = mutableMapOf<String, MutableList<Track>>()
    downloadedTracks.forEach { track ->
        if (track.album.isNotBlank()) {
            grouped.getOrPut(track.album) { mutableListOf() }.add(track)
        }
    }
    return grouped.map { (albumTitle, albumTracks) ->
        val albumId =
            albumTracks.firstOrNull { it.albumId.isNotBlank() }?.albumId ?: albumTitle
        val artist = albumTracks.firstOrNull()?.artist.orEmpty()
        val artworkUrl =
            albumTracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
        CatalogItem.Record(
            Album(
                id = albumId,
                title = albumTitle,
                artist = artist,
                artworkUrl = artworkUrl,
                year = "",
                tracks = albumTracks,
            )
        )
    }
}

/** Spotlight cards: lead album or playlist of each shelf, library shelves last, saved playlists only to fill. */
internal fun discoverEntries(
    state: LoadState<List<CatalogSection>>,
    library: LibrarySnapshot,
): List<DiscoverEntry> {
    val editorial = mutableListOf<DiscoverEntry>()
    val fromLibrary = mutableListOf<DiscoverEntry>()
    if (state is LoadState.Content) {
        for (section in state.value) {
            // Liked Music art is a flat glyph that falls apart at spotlight size.
            val lead = section.items.firstOrNull {
                it.artworkUrl.isNotBlank() &&
                    (it is CatalogItem.Record || it is CatalogItem.Collection) &&
                    !(it is CatalogItem.Collection && PlaylistActions.isLikedMusicPlaylist(it.playlist.id))
            } ?: continue
            val bucket = if (section.title.contains("library", ignoreCase = true)) fromLibrary else editorial
            bucket.add(DiscoverEntry(lead, section.title))
        }
    }
    val saved = library.savedPlaylists
        .filterNot { PlaylistActions.isLikedMusicPlaylist(it.id) }
        .map { DiscoverEntry(CatalogItem.Collection(it), "From your library") }
    return (editorial + fromLibrary + saved)
        .distinctBy { it.item.stableId }
        .take(6)
}

/** Spotify-style 6-grid: playlists, then saved albums, then catalog items. */
internal fun quickGridItems(
    library: LibrarySnapshot,
    state: LoadState<List<CatalogSection>>,
    onOpenDetail: (String) -> Unit,
    openCatalogItem: (CatalogItem) -> Unit,
    playCatalogItem: (CatalogItem) -> Unit,
): List<QuickGridItem> = buildList {
    library.savedPlaylists.take(6).forEach { playlist ->
        add(
            QuickGridItem(
                id = "pl_${playlist.id}",
                title = playlist.title,
                artworkUrl = playlist.artworkUrl,
                onClick = { onOpenDetail(playlist.id) },
                onPlay = { playCatalogItem(CatalogItem.Collection(playlist)) },
            )
        )
    }
    if (size < 6) {
        library.savedAlbums.take(6 - size).forEach { album ->
            add(
                QuickGridItem(
                    id = "alb_${album.id}",
                    title = album.title,
                    artworkUrl = album.artworkUrl,
                    onClick = { onOpenDetail(album.id) },
                    onPlay = { playCatalogItem(CatalogItem.Record(album)) },
                )
            )
        }
    }
    if (size < 6 && state is LoadState.Content) {
        val stateItems = state.value.flatMap { it.items }.filter { it.artworkUrl.isNotBlank() }
        stateItems.distinctBy { it.stableId }.take(6 - size).forEach { item ->
            add(
                QuickGridItem(
                    id = "st_${item.stableId}",
                    title = item.title,
                    artworkUrl = item.artworkUrl,
                    onClick = { openCatalogItem(item) },
                    onPlay = { playCatalogItem(item) },
                )
            )
        }
    }
}.take(6)
