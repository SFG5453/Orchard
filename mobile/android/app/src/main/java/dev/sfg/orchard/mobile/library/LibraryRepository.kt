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

package dev.sfg.orchard.mobile.library

import dev.sfg.orchard.mobile.catalog.CatalogRepository
import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One source of truth for cached and signed-in library content. */
class LibraryRepository(
    private val cache: LibraryCache,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope,
) {
    private val mutableLibrary = MutableStateFlow(cache.load())
    val library: StateFlow<LibrarySnapshot> = mutableLibrary.asStateFlow()
    private val cacheUpdates = Channel<LibrarySnapshot>(Channel.CONFLATED)

    init {
        // One writer prevents rapid likes/history updates from finishing out of
        // order and leaving an older snapshot on disk.
        scope.launch(Dispatchers.IO) {
            for (snapshot in cacheUpdates) cache.save(snapshot)
        }
    }

    fun toggleLiked(track: Track) = update { current ->
        val liked = current.likedTracks.toMutableList()
        val existing = liked.indexOfFirst { it.id == track.id }
        if (existing >= 0) liked.removeAt(existing) else liked.add(0, track)
        current.copy(likedTracks = liked)
    }

    fun saveAlbum(album: Album) = update { current ->
        current.copy(savedAlbums = toggle(current.savedAlbums, album, Album::id))
    }

    fun saveArtist(artist: Artist) = update { current ->
        current.copy(savedArtists = toggle(current.savedArtists, artist, Artist::id))
    }

    fun savePlaylist(playlist: Playlist) = update { current ->
        current.copy(savedPlaylists = toggle(current.savedPlaylists, playlist, Playlist::id))
    }

    fun removePlaylist(playlistId: String) = update { current ->
        current.copy(savedPlaylists = current.savedPlaylists.filterNot { it.id == playlistId.removePrefix("VL") })
    }

    /** Keeps a locally saved playlist in sync after an authenticated mutation. */
    fun refreshPlaylist(playlist: Playlist) = update { current ->
        val id = playlist.id.removePrefix("VL")
        current.copy(savedPlaylists = current.savedPlaylists.map { saved ->
            if (saved.id.removePrefix("VL") == id) playlist else saved
        })
    }

    fun recordPlayed(track: Track) = update { current ->
        val currentCount = current.playCounts[track.id] ?: 0
        val updatedCounts = current.playCounts + (track.id to currentCount + 1)
        current.copy(
            recentlyPlayed = listOf(track) + current.recentlyPlayed.filterNot { it.id == track.id }.take(49),
            playCounts = updatedCounts,
        )
    }

    suspend fun refreshSignedInLibrary(): Result<Unit> = runCatching {
        val sections = catalog.library()
        val items = sections.flatMap { it.items }
        val liked = runCatching { catalog.likedSongs().tracks }.getOrDefault(emptyList())
        val current = mutableLibrary.value
        val refreshed = current.copy(
            likedTracks = liked.ifEmpty { current.likedTracks },
            savedAlbums = items.filterIsInstance<CatalogItem.Record>().map { it.album }.distinctBy(Album::id),
            savedArtists = items.filterIsInstance<CatalogItem.Performer>().map { it.artist }.distinctBy(Artist::id),
            savedPlaylists = items.filterIsInstance<CatalogItem.Collection>().map { it.playlist }.distinctBy(Playlist::id),
        )
        mutableLibrary.value = refreshed
        cacheUpdates.trySend(refreshed).getOrThrow()
    }

    private fun update(transform: (LibrarySnapshot) -> LibrarySnapshot) {
        val next = transform(mutableLibrary.value)
        mutableLibrary.value = next
        cacheUpdates.trySend(next).getOrThrow()
    }

    private fun <T> toggle(values: List<T>, candidate: T, id: (T) -> String): List<T> {
        val existing = values.indexOfFirst { id(it) == id(candidate) }
        return if (existing >= 0) values.toMutableList().apply { removeAt(existing) } else listOf(candidate) + values
    }
}
