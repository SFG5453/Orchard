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

package dev.sfg.orchard.mobile.app

import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.artwork.ArtistImages
import dev.sfg.orchard.mobile.artwork.TrackArtwork
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.catalog.PlaylistActions
import dev.sfg.orchard.mobile.download.OfflineDetailSynthesizer
import dev.sfg.orchard.mobile.local.isLocalPlaylistId
import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The open collection: paged loading, offline fallback, artwork, saving and playlist edits. */
internal class DetailController(
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val showWarning: (String) -> Unit,
) {
    private val mutableDetail = MutableStateFlow<LoadState<BrowseDetail>>(LoadState.Idle)
    val detail: StateFlow<LoadState<BrowseDetail>> = mutableDetail.asStateFlow()
    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()
    private val mutableArtwork = MutableStateFlow<TrackArtwork?>(null)
    val artwork: StateFlow<TrackArtwork?> = mutableArtwork.asStateFlow()
    private val mutableArtistImages = MutableStateFlow<ArtistImages?>(null)
    val artistImages: StateFlow<ArtistImages?> = mutableArtistImages.asStateFlow()
    private var job: Job? = null
    private var loadGeneration = 0

    private val active: BrowseDetail? get() = (mutableDetail.value as? LoadState.Content)?.value
    val activeId: String get() = active?.id.orEmpty()

    /** A playlist on this phone: no network, and it follows every edit as it happens. */
    private fun loadLocal(id: String) {
        job?.cancel()
        loadGeneration++
        mutableRefreshing.value = false
        job = scope.launch {
            graph.localLibrary.detailFlow(id).collect { local ->
                mutableDetail.value = local?.let { LoadState.Content(it) }
                    ?: LoadState.Error("This playlist no longer exists.")
            }
        }
    }

    fun load(id: String, seed: CatalogItem?, preserveContent: Boolean) {
        if (isLocalPlaylistId(id)) {
            loadLocal(id)
            return
        }
        // A collection publishes several times as its pages land, so a load left running after
        // the listener moved on would keep writing its pages over the collection they opened next.
        job?.cancel()
        val generation = ++loadGeneration
        job = scope.launch {
            if (preserveContent) {
                mutableRefreshing.value = true
            } else {
                mutableRefreshing.value = false
                mutableDetail.value = LoadState.Loading
            }

            // When offline, synthesize from downloaded tracks straight away.
            if (!graph.networkMonitor.checkIsOnline()) {
                val offlineDetail = offlineDetail(id, seed)
                if (offlineDetail != null) {
                    mutableDetail.value = LoadState.Content(offlineDetail)
                    mutableRefreshing.value = false
                    return@launch
                }
            }

            // Each page replaces the last, so the collection appears as soon as its first page
            // lands and grows underneath the listener instead of holding a spinner until an
            // endless mix exhausts its continuation budget.
            try {
                graph.catalog.browsePages(id).collect { page ->
                    val resolved = page.withSeed(seed)
                    mutableDetail.value = LoadState.Content(resolved)
                    graph.library.cacheDetail(resolved)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Continuation failures are absorbed by the repository, so reaching here means
                // the first page never arrived and there is nothing on screen to keep.
                mutableDetail.value = offlineDetail(id, seed)?.let { LoadState.Content(it) }
                    ?: LoadState.Error(error.message ?: "This collection could not be loaded.")
            } finally {
                if (loadGeneration == generation) mutableRefreshing.value = false
            }
        }
    }

    private fun offlineDetail(id: String, seed: CatalogItem?): BrowseDetail? =
        OfflineDetailSynthesizer.synthesize(
            id = id,
            seed = seed,
            downloadedItems = graph.downloads.downloads.value.values.toList(),
            library = graph.library.library.value,
        )

    fun observeArtwork() {
        scope.launch {
            detail.collectLatest { state ->
                mutableArtwork.value = null
                mutableArtistImages.value = null
                if (state is LoadState.Content) {
                    val detailVal = state.value
                    when (detailVal.kind) {
                        // The user's own cover and loop; nothing is looked up for a playlist on this phone.
                        CatalogKind.PLAYLIST -> if (isLocalPlaylistId(detailVal.id)) {
                            mutableArtwork.value = TrackArtwork(
                                detailVal.id, detailVal.artworkUrl, graph.localLibrary.animatedCoverOf(detailVal.id),
                            )
                        } else {
                            mutableArtwork.value = graph.artwork.artwork(detailVal)
                            val performer = detailVal.artist
                                .ifBlank { detailVal.tracks.firstOrNull { it.artist.isNotBlank() }?.artist.orEmpty() }
                            if (performer.isNotBlank()) {
                                mutableArtistImages.value = graph.artistImages.images(performer)
                            }
                        }
                        CatalogKind.ALBUM -> {
                            mutableArtwork.value = graph.artwork.artwork(detailVal)
                            val performer = detailVal.artist
                                .ifBlank { detailVal.tracks.firstOrNull { it.artist.isNotBlank() }?.artist.orEmpty() }
                            if (performer.isNotBlank()) {
                                mutableArtistImages.value = graph.artistImages.images(performer)
                            }
                        }
                        // Channel avatars are often a logo; TheAudioDB has a real photograph.
                        CatalogKind.ARTIST ->
                            mutableArtistImages.value = graph.artistImages.images(detailVal.title)
                        else -> Unit
                    }
                }
            }
        }
    }

    fun save(detail: BrowseDetail) {
        when (detail.kind) {
            // `subtitle` is the whole browse line ("Album • 2017"), so it never goes in the artist field.
            CatalogKind.ALBUM -> graph.library.saveAlbum(
                Album(
                    id = detail.id,
                    title = detail.title,
                    artist = detail.artist.ifBlank { detail.tracks.firstOrNull()?.artist.orEmpty() },
                    artworkUrl = detail.artworkUrl,
                    year = detail.year,
                    tracks = detail.tracks,
                    explicit = detail.explicit,
                ),
            )
            CatalogKind.ARTIST -> setArtistSubscription(detail)
            // A playlist on this phone is already stored; it has no YouTube library to join.
            CatalogKind.PLAYLIST -> if (!isLocalPlaylistId(detail.id)) graph.library.savePlaylist(detail.asPlaylist())
            CatalogKind.TRACK -> Unit
        }
    }

    private fun setArtistSubscription(detail: BrowseDetail) {
        if (graph.auth.state.value !is AuthState.SignedIn) {
            showWarning("Sign in to YouTube Music to follow artists.")
            return
        }
        val artist = Artist(detail.id, detail.title, detail.artworkUrl, detail.subtitle)
        val wasSubscribed = graph.library.library.value.savedArtists.any { it.id == artist.id }
        val subscribe = !wasSubscribed
        graph.library.setArtistSaved(artist, subscribe)
        scope.launch {
            runCatching { graph.catalog.setArtistSubscription(artist.id, subscribe) }
                .onFailure { error ->
                    graph.library.setArtistSaved(artist, wasSubscribed)
                    showWarning(
                        error.message ?: if (subscribe) "Could not follow this artist."
                        else "Could not unfollow this artist.",
                    )
                }
        }
    }

    fun createPlaylist(title: String, track: Track?, onCreated: (String) -> Unit) = scope.launch {
        runCatching {
            withContext(Dispatchers.IO) { graph.playlistActions.create(title, track) }
        }
            .onSuccess { id ->
                // Shows up in the library at once; the next sign-in refresh fills in the rest.
                graph.library.savePlaylist(Playlist(id, title.trim(), author = "You"))
                onCreated(id)
            }
            .onFailure { graph.postWarning(it.message ?: "Could not create playlist.") }
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) = scope.launch {
        if (isLocalPlaylistId(playlistId)) {
            graph.localLibrary.addToPlaylist(playlistId, track.id)
            return@launch
        }
        val likedMusic = PlaylistActions.isLikedMusicPlaylist(playlistId)
        runCatching {
            withContext(Dispatchers.IO) {
                // The provider stores the album-audio id playback resolves, so both pick one recording.
                graph.playlistActions.add(playlistId, track)
                graph.catalog.browse(if (likedMusic) PlaylistActions.LIKED_MUSIC_BROWSE_ID else playlistId)
            }
        }.onSuccess {
            if (likedMusic) graph.library.setLiked(track, true)
            applyRefreshedPlaylist(it)
        }
            .onFailure { graph.postWarning(it.message ?: "Could not add track to playlist.") }
    }

    fun removeTrackFromPlaylist(playlistId: String, track: Track) = scope.launch {
        if (isLocalPlaylistId(playlistId)) {
            graph.localLibrary.removeFromPlaylist(playlistId, track.id)
            return@launch
        }
        val likedMusic = PlaylistActions.isLikedMusicPlaylist(playlistId)
        runCatching { withContext(Dispatchers.IO) { graph.playlistActions.remove(playlistId, track) } }
            .onSuccess {
                if (likedMusic) graph.library.setLiked(track, false)
                applyRemovedPlaylistTrack(playlistId, track.id)
            }
            .onFailure { graph.postWarning(it.message ?: "Could not remove track from playlist.") }
    }

    fun deletePlaylist(playlistId: String) = scope.launch {
        if (isLocalPlaylistId(playlistId)) {
            graph.localLibrary.deletePlaylist(playlistId)
            return@launch
        }
        runCatching {
            withContext(Dispatchers.IO) { graph.playlistActions.delete(playlistId) }
            graph.library.removePlaylist(playlistId)
        }.onFailure { graph.postWarning(it.message ?: "Could not delete playlist.") }
    }

    fun moveTrackInActivePlaylist(fromIndex: Int, toIndex: Int) {
        val moving = active ?: return
        if (moving.kind != CatalogKind.PLAYLIST || !moving.editable) return
        if (isLocalPlaylistId(moving.id)) {
            // The repository publishes the new order, and the open detail follows it.
            graph.localLibrary.moveInPlaylist(moving.id, fromIndex, toIndex)
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { graph.playlistActions.move(moving.id, fromIndex, toIndex) }
            }.onSuccess {
                val current = active ?: return@onSuccess
                if (current.id.removePrefix("VL") != moving.id.removePrefix("VL")) return@onSuccess
                val updated = current.withPlaylistTrackMoved(fromIndex, toIndex)
                mutableDetail.value = LoadState.Content(updated)
                graph.library.refreshPlaylist(updated.asPlaylist())
            }.onFailure { graph.postWarning(it.message ?: "Could not reorder the playlist.") }
        }
    }

    /**
     * Reflect a confirmed removal without immediately re-reading YouTube's eventually consistent
     * playlist page. Removing by index matters because playlists may contain the same song twice.
     */
    private fun applyRemovedPlaylistTrack(playlistId: String, videoId: String) {
        val current = active ?: return
        if (current.id.removePrefix("VL") != playlistId.removePrefix("VL")) return
        val updated = current.withPlaylistTrackRemoved(videoId)
        if (updated === current) return
        mutableDetail.value = LoadState.Content(updated)
        graph.library.refreshPlaylist(updated.asPlaylist())
    }

    private fun applyRefreshedPlaylist(refreshed: BrowseDetail) {
        if (activeId.removePrefix("VL") == refreshed.id.removePrefix("VL")) {
            mutableDetail.value = LoadState.Content(refreshed)
        }
        graph.library.refreshPlaylist(refreshed.asPlaylist())
    }
}

private fun BrowseDetail.asPlaylist() = Playlist(id, title, subtitle, artworkUrl, description, tracks)
