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

import android.app.Application
import android.os.Build
import dev.sfg.orchard.mobile.audio.selfDeviceLabel
import dev.sfg.orchard.mobile.audio.selfDeviceWord
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sfg.orchard.connect.protocol.ConnectCommand
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.artwork.ArtistImages
import dev.sfg.orchard.mobile.artwork.TrackArtwork
import dev.sfg.orchard.mobile.model.*
import dev.sfg.orchard.mobile.connect.PlaybackTargetCoordinator
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import dev.sfg.orchard.mobile.playback.QueueEditor
import dev.sfg.orchard.mobile.songlinks.LinkResolution
import dev.sfg.orchard.mobile.songlinks.SongLinksCoordinator
import dev.sfg.orchard.mobile.songlinks.SongShareState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Presentation state holder for the standalone shell and both playback targets. */
@OptIn(FlowPreview::class)
class OrchardViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = OrchardGraph.from(application)
    private val songLinksCoordinator = SongLinksCoordinator(graph.songLinks, viewModelScope)
    val shareState: StateFlow<SongShareState?> = songLinksCoordinator.shareState
    private val local = LocalPlaybackController(application, viewModelScope)
    private val targetCoordinator = PlaybackTargetCoordinator(
        PlaybackDevice(
            id = "local-phone",
            name = Build.MODEL.takeIf(String::isNotBlank) ?: application.selfDeviceLabel(),
            type = DeviceType.PHONE,
            availability = DeviceAvailability.ONLINE,
            isLocal = true,
        ),
        selfWord = application.selfDeviceWord(),
    )
    private val mutableTargets = MutableStateFlow(targetCoordinator.state)
    val targets: StateFlow<PlaybackTargetState> = mutableTargets.asStateFlow()
    private val targetPlayback: StateFlow<PlaybackSnapshot> = combine(
        local.snapshot,
        graph.connect.snapshot,
        mutableTargets,
    ) { localSnapshot, remoteSnapshot, targetState ->
        when (targetState.selected) {
            PlaybackTarget.LocalPhone -> localSnapshot
            is PlaybackTarget.Remote -> remoteSnapshot
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSnapshot())
    private val mutableArtwork = MutableStateFlow<TrackArtwork?>(null)
    val playback: StateFlow<PlaybackSnapshot> = combine(targetPlayback, mutableArtwork) { snapshot, artwork ->
        val track = snapshot.currentTrack
        if (track == null || artwork?.trackId != track.id) snapshot else snapshot.copy(
            currentTrack = track.copy(
                artworkUrl = artwork.staticUrl.ifBlank { track.artworkUrl },
                animatedArtworkUrl = artwork.videoUrl.ifBlank { track.animatedArtworkUrl },
                animatedArtworkVerticalUrl = artwork.videoUrlVertical.ifBlank { track.animatedArtworkVerticalUrl },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSnapshot())

    private val mutableHome = MutableStateFlow<LoadState<List<CatalogSection>>>(LoadState.Loading)
    val home: StateFlow<LoadState<List<CatalogSection>>> = mutableHome.asStateFlow()
    private val searchQuery = MutableStateFlow("")
    val query: StateFlow<String> = searchQuery.asStateFlow()
    private val mutableSearch = MutableStateFlow<LoadState<SearchResults>>(LoadState.Idle)
    val search: StateFlow<LoadState<SearchResults>> = mutableSearch.asStateFlow()
    private val mutableDetail = MutableStateFlow<LoadState<BrowseDetail>>(LoadState.Idle)
    val detail: StateFlow<LoadState<BrowseDetail>> = mutableDetail.asStateFlow()
    private val mutableDetailArtwork = MutableStateFlow<TrackArtwork?>(null)
    val detailArtwork: StateFlow<TrackArtwork?> = mutableDetailArtwork.asStateFlow()
    private val mutableArtistImages = MutableStateFlow<ArtistImages?>(null)
    val artistImages: StateFlow<ArtistImages?> = mutableArtistImages.asStateFlow()
    val library: StateFlow<LibrarySnapshot> = graph.library.library
    private val mutableLibraryFilter = MutableStateFlow(LibraryFilter.PLAYLISTS)
    val libraryFilter: StateFlow<LibraryFilter> = mutableLibraryFilter.asStateFlow()
    val settings: StateFlow<OrchardSettings> = graph.settings.settings
    val searchHistory: StateFlow<List<String>> = graph.settings.searchHistory
    val auth: StateFlow<AuthState> = graph.auth.state
    val connectMessage: StateFlow<String> = graph.connect.message
    val connectProtocolVersion: StateFlow<Int> = graph.connect.protocolVersion
    val connectAudioEngine: StateFlow<dev.sfg.orchard.connect.protocol.ConnectAudioEngine> = graph.connect.audioEngine
    val connectRemoteVolume: StateFlow<Float> = graph.connect.remoteVolume
    private val mutableLyrics = MutableStateFlow<LoadState<List<LyricLine>>>(LoadState.Idle)
    val lyrics: StateFlow<LoadState<List<LyricLine>>> = mutableLyrics.asStateFlow()
    val discordAuth: StateFlow<dev.sfg.orchard.mobile.discord.DiscordAuthState> = graph.discordAuth.authState
    val discordConnection: StateFlow<dev.sfg.orchard.mobile.discord.GatewayConnectionState> = graph.discordPresence.connectionState

    val activeBitrate: StateFlow<Int> = graph.activeBitrate.asStateFlow()
    private val mutableWarning = MutableStateFlow("")
    val warning: StateFlow<String> = mutableWarning.asStateFlow()
    private var warningDismissJob: Job? = null

    init {
        refreshHome()
        observeSearch()
        observeRemoteDevice()
        observeArtwork()
        observeDetailArtwork()
        observeLyrics()
        observeAuthentication()
        observeDiscordPresence()
        observeWarnings()
    }

    fun refreshHome() {
        viewModelScope.launch {
            mutableHome.value = LoadState.Loading
            mutableHome.value = runCatching { graph.catalog.home() }
                .fold(
                    onSuccess = { if (it.isEmpty()) LoadState.Empty("No recommendations are available yet.") else LoadState.Content(it) },
                    onFailure = {
                        if (library.value.recentlyPlayed.isNotEmpty()) {
                            LoadState.Error("Orchard is offline. Your recent music is still available.", true)
                        } else LoadState.Error(it.message ?: "Home could not be loaded.")
                    },
                )
        }
    }

    fun updateQuery(value: String) {
        searchQuery.value = value
    }

    fun runSearch(value: String = query.value) {
        searchQuery.value = value.trim()
        graph.settings.recordSearch(value)
    }

    fun clearSearchHistory() = graph.settings.clearSearchHistory()
    fun selectLibraryFilter(filter: LibraryFilter) { mutableLibraryFilter.value = filter }

    fun openDetail(id: String) {
        val seed = findCatalogItem(id, home.value, search.value, library.value, detail.value)
        viewModelScope.launch {
            mutableDetail.value = LoadState.Loading
            mutableDetail.value = runCatching { graph.catalog.browse(id) }
                .fold(
                    onSuccess = { LoadState.Content(it.withSeed(seed)) },
                    onFailure = { LoadState.Error(it.message ?: "This collection could not be loaded.") },
                )
        }
    }

    /** The transition Smart Crossfade has planned out of the current track, for the scrubber. */
    val transitionMarker = graph.transitionMarker

    fun play(track: Track, contextTitle: String = "") {
        Log.d(TAG, "play: track=${track.id} ('${track.title}'), contextTitle='$contextTitle'")
        playAll(listOf(track), contextTitle = contextTitle)
    }

    fun playAll(tracks: List<Track>, startIndex: Int = 0, contextTitle: String = "") {
        Log.d(TAG, "playAll: ${tracks.size} tracks, startIndex=$startIndex, contextTitle='$contextTitle'")
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(tracks.indices)
        val source = contextTitle.takeIf { it.isMeaningfulPlaybackSource() }
            ?: tracks[safeIndex].album.takeIf { it.isMeaningfulPlaybackSource() }
            ?: "Your queue"

        viewModelScope.launch {
            val start = graph.audioVersions.audioVersion(tracks[safeIndex])
            Log.d(TAG, "playAll: starting playback with track ${start.id} ('${start.title}')")
            val queue = tracks.toMutableList().apply { this[safeIndex] = start }

            when (targets.value.selected) {
                PlaybackTarget.LocalPhone -> local.replaceQueue(queue, safeIndex, contextTitle = source)
                is PlaybackTarget.Remote -> graph.connect.transfer(start)
            }
            graph.library.recordPlayed(start)

            if (targets.value.selected is PlaybackTarget.LocalPhone) {
                resolveRemainingAudioVersions(queue, safeIndex)
            }
        }
    }

    /** Replaces queued music videos with their album audio as each lookup completes. */
    private fun CoroutineScope.resolveRemainingAudioVersions(queue: List<Track>, startIndex: Int) {
        queue.forEachIndexed { index, track ->
            if (index == startIndex || !track.isVideoUpload) return@forEachIndexed
            launch {
                val audio = graph.audioVersions.audioVersion(track)
                if (audio.id != track.id) local.replaceQueued(index, track.id, audio)
            }
        }
    }

    /**
     * Plays whatever a spoken request resolves to. A blank query is Assistant asking for music
     * with no preference, which the user's own liked songs answer better than a search would.
     */
    fun playFromSearch(query: String) {
        Log.d(TAG, "playFromSearch: '$query'")
        viewModelScope.launch {
            val tracks = if (query.isBlank()) {
                graph.library.library.value.likedTracks.let(QueueEditor::shuffle)
            } else {
                runCatching { graph.catalog.search(query).tracks }.getOrDefault(emptyList())
            }
            if (tracks.isEmpty()) {
                showWarning(
                    if (query.isBlank()) "Nothing to play yet" else "Nothing found for \"$query\"",
                )
                return@launch
            }
            playAll(tracks, contextTitle = query)
        }
    }

    fun shuffleAll(tracks: List<Track>, contextTitle: String = "") {
        val shuffled = QueueEditor.shuffle(tracks)
        playAll(shuffled, contextTitle = contextTitle)
        if (targets.value.selected is PlaybackTarget.LocalPhone) local.setShuffle(true)
    }

    fun saveDetail(detail: BrowseDetail) {
        when (detail.kind) {
            CatalogKind.ALBUM -> graph.library.saveAlbum(
                Album(detail.id, detail.title, detail.subtitle, detail.artworkUrl, detail.year, detail.tracks),
            )
            CatalogKind.ARTIST -> graph.library.saveArtist(
                Artist(detail.id, detail.title, detail.artworkUrl, detail.subtitle),
            )
            CatalogKind.PLAYLIST -> graph.library.savePlaylist(
                Playlist(detail.id, detail.title, detail.subtitle, detail.artworkUrl, detail.description, detail.tracks),
            )
            CatalogKind.TRACK -> Unit
        }
    }

    fun playNext(track: Track) = remoteOrLocal(
        { if (connectProtocolVersion.value >= 2) graph.connect.playNext(track) },
        { local.playNext(track) },
    )

    fun addToQueue(track: Track) = remoteOrLocal(
        { if (connectProtocolVersion.value >= 2) graph.connect.addToQueue(track) },
        { local.addToQueue(track) },
    )

    fun togglePlayback() = remoteOrLocal({ graph.connect.send(ConnectCommand.TogglePlayback) }, local::toggle)
    fun next() = remoteOrLocal({ graph.connect.send(ConnectCommand.Next) }, local::next)
    fun previous() = remoteOrLocal({ graph.connect.send(ConnectCommand.Previous) }, local::previous)
    fun seek(positionMs: Long) = remoteOrLocal(
        { graph.connect.send(ConnectCommand.Seek(positionMs / 1_000.0)) },
        { local.seek(positionMs) },
    )
    fun toggleShuffle() = remoteOrLocal(
        { if (connectProtocolVersion.value >= 2) graph.connect.toggleShuffle() },
        {
            val enable = !playback.value.shuffle
            if (enable) local.shuffleUpcoming()
            local.setShuffle(enable)
        },
    )
    fun cycleRepeat() = remoteOrLocal(
        { if (connectProtocolVersion.value >= 2) graph.connect.cycleRepeat() },
        local::cycleRepeat,
    )
    fun playQueueIndex(index: Int) = remoteOrLocal(
        { graph.connect.send(ConnectCommand.PlayQueueIndex(index)) },
        { local.playQueueIndex(index) },
    )
    fun removeQueueIndex(index: Int) = remoteOrLocal(
        { graph.connect.send(ConnectCommand.RemoveQueueIndex(index)) },
        { local.remove(index) },
    )
    fun moveQueueItem(from: Int, to: Int) = remoteOrLocal(
        { if (connectProtocolVersion.value >= 2) graph.connect.moveQueueIndex(from, to) },
        { local.move(from, to) },
    )
    fun clearUpcoming() = remoteOrLocal(
        { if (connectProtocolVersion.value >= 2) graph.connect.clearUpcoming() },
        local::clearUpcoming,
    )

    fun setRemoteVolume(volume: Float) = graph.connect.setVolume(volume)
    fun setAudioEnginePreset(preset: String) = graph.connect.setAudioEnginePreset(preset)
    fun toggleAutoEq(enabled: Boolean) = graph.connect.toggleAutoEq(enabled)
    fun toggleManualEq(enabled: Boolean) = graph.connect.toggleManualEq(enabled)

    fun selectTarget(target: PlaybackTarget) {
        if (target == targets.value.selected || !targetCoordinator.beginTransfer(target)) {
            mutableTargets.value = targetCoordinator.state
            return
        }
        mutableTargets.value = targetCoordinator.state
        viewModelScope.launch {
            when (target) {
                PlaybackTarget.LocalPhone -> transferToPhone()
                is PlaybackTarget.Remote -> transferToRemote(target)
            }
        }
    }

    fun pairDevice(input: String) = graph.connect.pair(input)
    fun disconnectDevice() = graph.connect.disconnect()
    fun toggleLiked(track: Track) = graph.library.toggleLiked(track)
    fun updateSettings(value: OrchardSettings) = graph.settings.updateSettings(value)
    fun beginSignIn() = graph.auth.beginSignIn()
    fun completeSignIn(cookie: String, visitorData: String, dataSyncId: String) = graph.auth.completeSignIn(cookie, visitorData, dataSyncId)
    fun cancelSignIn() = graph.auth.cancelSignIn()
    fun signOut() = graph.auth.signOut()

    fun shareTrack(track: Track, albumContext: String? = null, artistContext: String? = null) =
        songLinksCoordinator.shareTrack(track, albumContext, artistContext)
    fun shareCollection(detail: BrowseDetail) = songLinksCoordinator.shareCollection(detail)
    fun dismissShare() = songLinksCoordinator.dismissShare()

    /** Post a warning that auto-dismisses after [durationMs]. */
    fun showWarning(message: String, durationMs: Long = 8_000L) {
        mutableWarning.value = message
        warningDismissJob?.cancel()
        warningDismissJob = viewModelScope.launch {
            delay(durationMs)
            mutableWarning.value = ""
        }
    }

    fun dismissWarning() {
        warningDismissJob?.cancel()
        mutableWarning.value = ""
    }

    fun handleIncomingLink(rawUrl: String, onNavigateDetail: (String) -> Unit = {}) {
        viewModelScope.launch {
            when (val resolution = songLinksCoordinator.resolveLink(rawUrl)) {
                is LinkResolution.PlayTrack -> play(resolution.track, "Shared link")
                is LinkResolution.OpenCollection -> {
                    openDetail(resolution.browseId)
                    onNavigateDetail(resolution.browseId)
                }
                is LinkResolution.PlayCollectionTracks -> playAll(resolution.tracks, contextTitle = resolution.title)
                null -> Unit
            }
        }
    }

    override fun onCleared() {
        local.close()
        super.onCleared()
    }

    private fun observeSearch() {
        viewModelScope.launch {
            searchQuery.debounce(350).distinctUntilChanged().collectLatest { value ->
                if (value.isBlank()) {
                    mutableSearch.value = LoadState.Idle
                    return@collectLatest
                }
                mutableSearch.value = LoadState.Loading

                val linkTarget = graph.songLinks.parseLink(value)
                if (linkTarget != null) {
                    when (val res = songLinksCoordinator.resolveLink(value)) {
                        is LinkResolution.PlayTrack -> {
                            mutableSearch.value = LoadState.Content(
                                SearchResults(tracks = listOf(res.track)),
                            )
                            return@collectLatest
                        }
                        is LinkResolution.PlayCollectionTracks -> {
                            mutableSearch.value = LoadState.Content(
                                SearchResults(tracks = res.tracks),
                            )
                            return@collectLatest
                        }
                        is LinkResolution.OpenCollection -> {
                            openDetail(res.browseId)
                        }
                        null -> Unit
                    }
                }

                mutableSearch.value = runCatching { graph.catalog.search(value) }
                    .fold(
                        onSuccess = { if (it.isEmpty) LoadState.Empty("No music matched “$value”.") else LoadState.Content(it) },
                        onFailure = { LoadState.Error(it.message ?: "Search is unavailable.") },
                    )
            }
        }
    }

    private fun observeRemoteDevice() {
        viewModelScope.launch {
            graph.connect.device.collect { remote ->
                targetCoordinator.updateRemoteDevices(listOfNotNull(remote))
                mutableTargets.value = targetCoordinator.state
            }
        }
    }

    private fun observeLyrics() {
        viewModelScope.launch {
            playback.map { it.currentTrack }.distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { track ->
                    if (track == null) {
                        mutableLyrics.value = LoadState.Idle
                        return@collectLatest
                    }
                    mutableLyrics.value = LoadState.Loading
                    mutableLyrics.value = runCatching { graph.lyrics.lyrics(track) }
                        .fold(
                            onSuccess = {
                                if (it.isEmpty()) LoadState.Empty("Lyrics are not available for this track.")
                                else LoadState.Content(it)
                            },
                            onFailure = { LoadState.Error(it.message ?: "Lyrics could not be loaded.") },
                        )
                }
        }
    }

    private fun observeArtwork() {
        viewModelScope.launch {
            targetPlayback.map { it.currentTrack }.distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { track ->
                    mutableArtwork.value = when {
                        track == null -> null
                        track.animatedArtworkVerticalUrl.isNotBlank() || track.animatedArtworkUrl.isNotBlank() ->
                            TrackArtwork(track.id, track.artworkUrl, track.animatedArtworkUrl, track.animatedArtworkVerticalUrl)
                        else -> graph.artwork.artwork(track)
                    }
                }
        }
    }

    private fun observeDetailArtwork() {
        viewModelScope.launch {
            detail.collectLatest { state ->
                mutableDetailArtwork.value = null
                mutableArtistImages.value = null
                if (state is LoadState.Content) {
                    val detailVal = state.value
                    when (detailVal.kind) {
                        CatalogKind.ALBUM, CatalogKind.PLAYLIST -> {
                            mutableDetailArtwork.value = graph.artwork.artwork(detailVal)
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

    private fun observeAuthentication() {
        viewModelScope.launch {
            auth.collect { state ->
                if (state is AuthState.SignedIn) {
                    graph.library.refreshSignedInLibrary()
                    refreshHome()
                }
            }
        }
    }

    private fun observeDiscordPresence() {
        viewModelScope.launch {
            combine(playback, settings) { snap, set -> snap to set }.collectLatest { (snap, set) ->
                graph.discordPresence.setEnabled(set.discordPresenceEnabled)
                if (set.discordPresenceEnabled) {
                    val override = if (set.discordAnimatedArtwork) null else ""
                    graph.discordPresence.updatePlayback(snap, animatedArtworkUrlOverride = override)
                }
            }
        }
    }

    private fun observeWarnings() {
        viewModelScope.launch { graph.warningEvent.collect { showWarning(it) } }
    }

    fun connectDiscord(context: android.content.Context) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(graph.discordAuth.buildAuthorizationUrl()))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun disconnectDiscord() = viewModelScope.launch { graph.discordAuth.signOut() }
    fun handleDiscordAuthCallback(code: String, state: String?) =
        viewModelScope.launch { graph.discordAuth.handleAuthorizationCode(code, state) }

    private suspend fun transferToRemote(target: PlaybackTarget.Remote) {
        performTransferToRemote(target, local, graph, targetCoordinator)
        mutableTargets.value = targetCoordinator.state
    }

    private suspend fun transferToPhone() {
        performTransferToPhone(local, graph, targetCoordinator)
        mutableTargets.value = targetCoordinator.state
    }

    private fun remoteOrLocal(remote: () -> Unit, localAction: () -> Unit) {
        if (targets.value.selected is PlaybackTarget.Remote) remote() else localAction()
    }

    private companion object {
        const val TAG = "OrchardViewModel"
    }
}
