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
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
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
        current.withLiked(track, current.likedTracks.none { it.id == track.id })
    }

    fun setLiked(track: Track, liked: Boolean) = update { current -> current.withLiked(track, liked) }

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

    /** Stores full collection membership after a browse page loads, without changing saved state. */
    fun cacheDetail(detail: BrowseDetail) {
        val saved = when (detail.kind) {
            CatalogKind.PLAYLIST -> mutableLibrary.value.savedPlaylists.any {
                it.id.removePrefix("VL") == detail.id.removePrefix("VL")
            }
            CatalogKind.ALBUM -> mutableLibrary.value.savedAlbums.any { it.id == detail.id }
            else -> false
        }
        if (!saved) return

        update { current ->
            when (detail.kind) {
                CatalogKind.PLAYLIST -> current.copy(
                    savedPlaylists = current.savedPlaylists.map { saved ->
                        if (saved.id.removePrefix("VL") == detail.id.removePrefix("VL")) {
                            Playlist(
                                id = saved.id,
                                title = detail.title.ifBlank { saved.title },
                                author = detail.artist.ifBlank { detail.subtitle.ifBlank { saved.author } },
                                artworkUrl = detail.artworkUrl.ifBlank { saved.artworkUrl },
                                description = detail.description.ifBlank { saved.description },
                                tracks = detail.tracks.ifEmpty { saved.tracks },
                                explicit = detail.explicit || saved.explicit,
                            )
                        } else saved
                    },
                )
                CatalogKind.ALBUM -> current.copy(
                    savedAlbums = current.savedAlbums.map { saved ->
                        if (saved.id == detail.id) saved.copy(
                            title = detail.title.ifBlank { saved.title },
                            artist = detail.artist.ifBlank { detail.subtitle.ifBlank { saved.artist } },
                            artworkUrl = detail.artworkUrl.ifBlank { saved.artworkUrl },
                            year = detail.year.ifBlank { saved.year },
                            tracks = detail.tracks.ifEmpty { saved.tracks },
                            explicit = detail.explicit || saved.explicit,
                        ) else saved
                    },
                )
                else -> current
            }
        }
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
        // The landing page contains recent-activity shelves, not the complete saved collections.
        // Albums and playlists each have their own browse id, so use those and retain the landing
        // page only as a fallback.
        val savedAlbums = runCatching { catalog.sectionItems(LIKED_ALBUMS) }
            .getOrDefault(emptyList())
            .filterIsInstance<CatalogItem.Record>()
            .map { it.album }
            .distinctBy(Album::id)
        val savedPlaylists = runCatching { catalog.sectionItems(LIKED_PLAYLISTS) }
            .getOrDefault(emptyList())
            .filterIsInstance<CatalogItem.Collection>()
            .map { it.playlist }
            .distinctBy(Playlist::id)
        val current = mutableLibrary.value
        val refreshed = current.copy(
            likedTracks = liked.ifEmpty { current.likedTracks },
            // Never let a failed or empty remote fetch wipe a collection already cached by Orchard.
            savedAlbums = savedAlbums
                .ifEmpty { items.filterIsInstance<CatalogItem.Record>().map { it.album }.distinctBy(Album::id) }
                .ifEmpty { current.savedAlbums },
            savedArtists = items.filterIsInstance<CatalogItem.Performer>().map { it.artist }.distinctBy(Artist::id),
            savedPlaylists = savedPlaylists
                .ifEmpty { items.filterIsInstance<CatalogItem.Collection>().map { it.playlist }.distinctBy(Playlist::id) }
                .ifEmpty { current.savedPlaylists }
                .map { remote -> remote.withCachedTracksFrom(current.savedPlaylists) },
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

    private companion object {
        /** YouTube Music's own Albums tab — the albums the user actually saved. */
        const val LIKED_ALBUMS = "FEmusic_liked_albums"
        /** The complete saved-playlist grid; the library landing page contains only recent items. */
        const val LIKED_PLAYLISTS = "FEmusic_liked_playlists"
    }
}

private fun LibrarySnapshot.withLiked(track: Track, liked: Boolean): LibrarySnapshot {
    val withoutTrack = likedTracks.filterNot { it.id == track.id }
    return copy(likedTracks = if (liked) listOf(track) + withoutTrack else withoutTrack)
}

/** Library grid payloads are metadata-only; never let one erase cached offline membership. */
internal fun Playlist.withCachedTracksFrom(cached: List<Playlist>): Playlist {
    if (tracks.isNotEmpty()) return this
    val previous = cached.firstOrNull { it.id.removePrefix("VL") == id.removePrefix("VL") }
    return if (previous?.tracks?.isNotEmpty() == true) copy(tracks = previous.tracks) else this
}
