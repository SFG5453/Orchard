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

import android.util.Log
import dev.sfg.orchard.mobile.applemusic.AppleMusicCatalog
import dev.sfg.orchard.mobile.catalog.isAppleMusicId
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Apple Music library while Apple Music is the music source and connected, else null. Kept in
 * memory only; Apple is the source of truth and a refresh replaces it.
 */
internal class AppleLibraryRepository(
    private val catalog: AppleMusicCatalog,
    active: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val showWarning: (String) -> Unit,
) {
    private val mutableLibrary = MutableStateFlow<LibrarySnapshot?>(null)
    val library: StateFlow<LibrarySnapshot?> = mutableLibrary.asStateFlow()
    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    val active: Boolean get() = mutableEnabled.value
    private var loadJob: Job? = null

    init {
        scope.launch {
            active.distinctUntilChanged().collect {
                mutableEnabled.value = it
                refresh()
            }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        if (!active) {
            mutableLibrary.value = null
            return
        }
        loadJob = scope.launch(Dispatchers.IO) {
            runCatching {
                coroutineScope {
                    // History is optional; the library still shows without it.
                    val recent = async { runCatching { catalog.recentTracks() }.getOrElse { if (it is CancellationException) throw it else emptyList() } }
                    catalog.library().copy(recentlyPlayed = recent.await())
                }
            }
                .onSuccess { mutableLibrary.value = it }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "Library load failed", it)
                    // An empty library keeps the YouTube Music one from showing in its place.
                    if (mutableLibrary.value == null) mutableLibrary.value = LibrarySnapshot()
                    showWarning(it.message ?: "Apple Music could not load your library.")
                }
        }
    }

    fun isLiked(track: Track): Boolean = mutableLibrary.value?.likedTracks?.any { it.id == track.id } == true

    suspend fun setLiked(track: Track, liked: Boolean) {
        edit { it.withSong(track, liked) }
        try {
            catalog.setLiked(track, liked)
        } catch (error: Exception) {
            edit { it.withSong(track, !liked) }
            throw error
        }
    }

    suspend fun createPlaylist(title: String, track: Track?): String {
        val id = catalog.createPlaylist(title, track)
        val playlist = Playlist(id, title.trim(), author = OWNER, tracks = listOfNotNull(track))
        if (id.isNotEmpty()) edit { it.copy(savedPlaylists = listOf(playlist) + it.savedPlaylists) }
        return id
    }

    suspend fun addToPlaylist(playlistId: String, track: Track) {
        catalog.addToPlaylist(playlistId, track)
        // Lets the picker mark the song as added; the grid counts come from the next refresh.
        edit { snapshot ->
            snapshot.copy(savedPlaylists = snapshot.savedPlaylists.map {
                if (it.id == playlistId) it.copy(tracks = it.tracks + track) else it
            })
        }
    }

    private fun edit(transform: (LibrarySnapshot) -> LibrarySnapshot) = mutableLibrary.update { it?.let(transform) }

    companion object {
        private const val TAG = "OrchardAppleLibrary"
        /** Author the provider gives playlists the listener can edit. */
        const val OWNER = "You"

        fun canAddTo(playlist: Playlist) = playlist.author == OWNER
    }
}

/** Orchard's recent Apple plays (Apple's history may not list them yet) ahead of Apple's own history. */
internal fun LibrarySnapshot.withHistory(own: LibrarySnapshot): LibrarySnapshot = copy(
    recentlyPlayed = (own.recentlyPlayed.filter { isAppleMusicId(it.id) } + recentlyPlayed).distinctBy { it.id }.take(50),
    playCounts = own.playCounts,
)

private fun LibrarySnapshot.withSong(track: Track, saved: Boolean): LibrarySnapshot {
    val others = likedTracks.filterNot { it.id == track.id }
    return copy(likedTracks = if (saved) listOf(track) + others else others)
}
