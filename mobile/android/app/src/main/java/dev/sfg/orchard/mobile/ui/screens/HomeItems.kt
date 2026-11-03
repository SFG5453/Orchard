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
import dev.sfg.orchard.mobile.model.APPLE_PERSONAL_MIXES_SECTION
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

private const val DISCOVER_MIXES_MAX = 2
private const val DISCOVER_MAX = 8

/**
 * Spotlight cards: up to two Apple Music personal mixes, then albums, songs, artists and
 * playlists in turn so no one kind fills the carousel. Feed picks rotate each [day]; library
 * items only back a kind the feed runs short on.
 */
internal fun discoverEntries(
    state: LoadState<List<CatalogSection>>,
    library: LibrarySnapshot,
    day: Long = 0,
): List<DiscoverEntry> {
    val random = kotlin.random.Random(day)
    val mixes = mutableListOf<DiscoverEntry>()
    val feed = mutableListOf<DiscoverEntry>()
    if (state is LoadState.Content) {
        for (section in state.value) {
            val usable = section.items.filter { it.artworkUrl.isNotBlank() && isDiscoverable(it) }
            if (section.id == APPLE_PERSONAL_MIXES_SECTION) {
                usable.forEach { mixes.add(DiscoverEntry(it, section.title)) }
            } else {
                usable.forEach { feed.add(DiscoverEntry(it, section.title)) }
            }
        }
    }
    val fromLibrary = buildList {
        library.likedTracks.shuffled(random).forEach { add(DiscoverEntry(CatalogItem.Song(it), "From your likes")) }
        library.savedAlbums.forEach { add(DiscoverEntry(CatalogItem.Record(it), "From your library")) }
        library.savedArtists.forEach { add(DiscoverEntry(CatalogItem.Performer(it), "Artists you follow")) }
        library.savedPlaylists.forEach { add(DiscoverEntry(CatalogItem.Collection(it), "From your library")) }
    }.filter { it.item.artworkUrl.isNotBlank() && isDiscoverable(it.item) }

    val queues = DiscoverKind.entries.map { kind ->
        ArrayDeque(feed.filter { kindOf(it.item) == kind }.shuffled(random) + fromLibrary.filter { kindOf(it.item) == kind })
    }
    val picked = mixes.take(DISCOVER_MIXES_MAX).toMutableList()
    val seen = picked.mapTo(HashSet()) { it.item.stableId }
    while (picked.size < DISCOVER_MAX && queues.any { it.isNotEmpty() }) {
        for (queue in queues) {
            if (picked.size >= DISCOVER_MAX) break
            while (queue.isNotEmpty()) {
                val entry = queue.removeFirst()
                if (seen.add(entry.item.stableId)) {
                    picked.add(entry)
                    break
                }
            }
        }
    }
    return picked
}

private enum class DiscoverKind { ALBUM, SONG, ARTIST, PLAYLIST }

private fun kindOf(item: CatalogItem): DiscoverKind? = when (item) {
    is CatalogItem.Record -> DiscoverKind.ALBUM
    is CatalogItem.Song -> DiscoverKind.SONG
    is CatalogItem.Performer -> DiscoverKind.ARTIST
    is CatalogItem.Collection -> DiscoverKind.PLAYLIST
    is CatalogItem.Category -> null
}

// Liked Music art is a flat glyph that falls apart at spotlight size.
private fun isDiscoverable(item: CatalogItem): Boolean =
    kindOf(item) != null && !(item is CatalogItem.Collection && PlaylistActions.isLikedMusicPlaylist(item.playlist.id))

internal const val QUICK_PICKS_PER_PAGE = 4
private const val QUICK_PICKS_MAX = 20

/**
 * Songs to start a mix from: liked songs and the feed's songs, reshuffled each [day]. Recent
 * plays are left out so the list never just echoes the session; they only fill a short pool.
 */
internal fun quickPickTracks(
    library: LibrarySnapshot,
    sections: List<CatalogSection>,
    day: Long,
): List<Track> {
    val recent = library.recentlyPlayed.mapTo(HashSet()) { it.id }
    val feed = sections.flatMap { it.items }.filterIsInstance<CatalogItem.Song>().map { it.track }
    val pool = (library.likedTracks + feed).distinctBy { it.id }
    val fresh = pool.filterNot { it.id in recent }.shuffled(kotlin.random.Random(day))
    val filler = (pool.filter { it.id in recent } + library.recentlyPlayed).distinctBy { it.id }
    return (fresh + filler).distinctBy { it.id }.take(QUICK_PICKS_MAX)
}
