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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val isOnline: StateFlow<Boolean> = graph.networkMonitor.isOnline
    val downloads: StateFlow<Map<String, dev.sfg.orchard.mobile.download.DownloadItem>> = graph.downloads.downloads
    val downloadedTrackIds: StateFlow<Set<String>> = graph.downloads.downloadedTrackIds
    val downloadingTrackIds: StateFlow<Set<String>> = graph.downloads.downloadingTrackIds
    val totalBytesUsed: StateFlow<Long> = downloads.map { map ->
        map.values.filter { it.status == dev.sfg.orchard.mobile.download.DownloadStatus.COMPLETED }
            .sumOf { it.bytesDownloaded }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), graph.downloads.totalBytesUsed())

    fun downloadTrack(track: Track) = graph.downloads.downloadTrack(track)
    fun downloadTracks(tracks: List<Track>) = graph.downloads.downloadTracks(tracks)
    fun removeDownload(videoId: String) = graph.downloads.removeDownload(videoId)
    fun removeDownloads(tracks: List<Track>) = graph.downloads.removeDownloads(tracks.map { it.id })

    fun createPlaylist(title: String, track: Track, onCreated: (String) -> Unit = {}) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { graph.playlistActions.create(title, track.id) } }
            .onSuccess(onCreated)
            .onFailure { graph.postWarning(it.message ?: "Could not create playlist.") }
    }
    fun addTrackToPlaylist(playlistId: String, track: Track) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                graph.playlistActions.add(playlistId, track.id)
                graph.catalog.browse(playlistId)
            }
        }.onSuccess(::applyRefreshedPlaylist)
            .onFailure { graph.postWarning(it.message ?: "Could not add track to playlist.") }
    }
    fun removeTrackFromPlaylist(playlistId: String, track: Track) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                graph.playlistActions.remove(playlistId, track.id)
                graph.catalog.browse(playlistId)
            }
        }.onSuccess(::applyRefreshedPlaylist)
            .onFailure { graph.postWarning(it.message ?: "Could not remove track from playlist.") }
    }

    private fun applyRefreshedPlaylist(refreshed: BrowseDetail) {
        val activeId = (mutableDetail.value as? LoadState.Content)?.value?.id.orEmpty().removePrefix("VL")
        if (activeId == refreshed.id.removePrefix("VL")) mutableDetail.value = LoadState.Content(refreshed)
        graph.library.refreshPlaylist(
            Playlist(refreshed.id, refreshed.title, refreshed.subtitle, refreshed.artworkUrl, refreshed.description, refreshed.tracks),
        )
    }
    fun deletePlaylist(playlistId: String) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) { graph.playlistActions.delete(playlistId) }
            graph.library.removePlaylist(playlistId)
        }.onFailure { graph.postWarning(it.message ?: "Could not delete playlist.") }
    }

    // Menu entry points. The picker UI supplies the target playlist through the public methods above.
    fun addTrackToPlaylistMenu(track: Track) = graph.postWarning("Choose a playlist to add ${track.title} to.")
    fun removeTrackFromCurrentPlaylist(track: Track) {
        val id = (detail.value as? LoadState.Content)?.value?.id.orEmpty()
        if (id.isNotBlank()) removeTrackFromPlaylist(id, track)
    }

    /**
     * Autoplay: once the queue is nearly out, ask YouTube Music what would come next after the last
     * queued track and append it. Only the local player is refilled, because a Connect device owns
     * its own queue and would fight us for it.
     */
    private val mutableAutoplayLoading = MutableStateFlow(false)
    val autoplayLoading: StateFlow<Boolean> = mutableAutoplayLoading.asStateFlow()
    private val mutableAutoplayError = MutableStateFlow("")
    val autoplayError: StateFlow<String> = mutableAutoplayError.asStateFlow()

    /** Seed of the request in flight, so a burst of queue updates cannot fan out into duplicates. */
    private var autoplaySeedInFlight = ""

    /** Seed that already came back with nothing usable; retrying it would fail the same way. */
    private var autoplayExhaustedSeed = ""

    /**
     * Whether refills are allowed, tracked separately from the persisted setting because DataStore
     * writes land asynchronously. Reading the setting here would let the refill observer see a
     * stale `true` in the moment after switching off — emptying the queue and immediately refilling
     * it from the same radio.
     */
    private val autoplayGate = MutableStateFlow(settings.value.autoplayEnabled)

    private val mutableWarning = MutableStateFlow("")
    val warning: StateFlow<String> = mutableWarning.asStateFlow()
    private var warningDismissJob: Job? = null

    val updateState: StateFlow<dev.sfg.orchard.mobile.UpdateState> = graph.updates.state

    fun installUpdate(metadata: dev.sfg.orchard.mobile.MobileUpdateMetadata) =
        graph.updates.downloadAndInstallUpdate(metadata)

    fun dismissUpdate() = graph.updates.dismiss()

    init {
        refreshHome()
        observeNetworkState()
        observeSearch()
        observeRemoteDevice()
        observeArtwork()
        observeDetailArtwork()
        observeLyrics()
        observeAuthentication()
        observeDiscordPresence()
        observeWarnings()
        observeAutoplay()
    }

    private fun observeNetworkState() {
        viewModelScope.launch {
            graph.networkMonitor.isOnline.collect { online ->
                if (online && mutableHome.value is LoadState.Error) {
                    refreshHome()
                }
            }
        }
    }

    fun refreshHome() {
        viewModelScope.launch {
            mutableHome.value = LoadState.Loading
            mutableHome.value = runCatching { graph.catalog.home() }
                .fold(
                    onSuccess = { if (it.isEmpty()) LoadState.Empty("No recommendations are available yet.") else LoadState.Content(it) },
                    onFailure = {
                        if (downloads.value.values.any { d -> d.status == dev.sfg.orchard.mobile.download.DownloadStatus.COMPLETED } || library.value.recentlyPlayed.isNotEmpty()) {
                            LoadState.Error("Orchard is offline. Your downloaded music is available.", true)
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

            // When offline or if network is down, immediately synthesize from downloaded tracks
            if (!graph.networkMonitor.checkIsOnline()) {
                val offlineDetail = dev.sfg.orchard.mobile.download.OfflineDetailSynthesizer.synthesize(
                    id = id,
                    seed = seed,
                    downloadedItems = downloads.value.values.toList(),
                    library = library.value,
                )
                if (offlineDetail != null) {
                    mutableDetail.value = LoadState.Content(offlineDetail)
                    return@launch
                }
            }

            val result = runCatching { graph.catalog.browse(id) }
                .map { it.withSeed(seed) }
                .recoverCatching { error ->
                    dev.sfg.orchard.mobile.download.OfflineDetailSynthesizer.synthesize(
                        id = id,
                        seed = seed,
                        downloadedItems = downloads.value.values.toList(),
                        library = library.value,
                    ) ?: throw error
                }

            mutableDetail.value = result.fold(
                onSuccess = { LoadState.Content(it) },
                onFailure = { LoadState.Error(it.message ?: "This collection could not be loaded.") },
            )
        }
    }

    suspend fun fetchSectionItems(browseId: String, params: String = ""): List<CatalogItem> {
        if (browseId.isBlank()) return emptyList()
        return runCatching { graph.catalog.sectionItems(browseId, params) }.getOrDefault(emptyList())
    }

    /** The transition Smart Crossfade has planned out of the current track, for the scrubber. */
    val transitionMarker = graph.transitionMarker

    fun play(track: Track, contextTitle: String = "") {
        Log.d(TAG, "play: track=${track.id} ('${track.title}'), contextTitle='$contextTitle'")
        playAll(listOf(track), contextTitle = contextTitle)
    }

    fun playAll(
        tracks: List<Track>,
        startIndex: Int = 0,
        contextTitle: String = "",
        shuffle: Boolean = false,
    ) {
        Log.d(TAG, "playAll: ${tracks.size} tracks, startIndex=$startIndex, contextTitle='$contextTitle'")
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(tracks.indices)
        val source = contextTitle.takeIf { it.isMeaningfulPlaybackSource() }
            ?: tracks[safeIndex].album.takeIf { it.isMeaningfulPlaybackSource() }
            ?: "Your queue"

        viewModelScope.launch {
            val start = graph.audioVersions.audioVersion(tracks[safeIndex])
            Log.d(TAG, "playAll: starting playback with track ${start.id} ('${start.title}')")
            val requested = tracks.toMutableList().apply { this[safeIndex] = start }
            // Normalized here rather than only inside replaceQueue, because the audio-version
            // lookups below address the queue by index. Letting the player dedupe on its own would
            // shift every index past the first duplicate and aim each swap at the wrong track.
            val edited = QueueEditor.replaceAndPlay(requested, safeIndex)
            val queue = edited.tracks

            when (targets.value.selected) {
                PlaybackTarget.LocalPhone -> {
                    local.replaceQueue(queue, edited.currentIndex, contextTitle = source)
                    // After the queue lands, never before: the service remembers the order it
                    // shuffles over, and enabling shuffle first would have it remember the queue
                    // this one is replacing — leaving nothing to restore when shuffle goes off.
                    if (shuffle) local.setShuffle(true)
                }
                is PlaybackTarget.Remote -> graph.connect.transfer(start)
            }
            graph.library.recordPlayed(start)

            if (targets.value.selected is PlaybackTarget.LocalPhone) {
                resolveRemainingAudioVersions(queue, edited.currentIndex)
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

    /**
     * Plays a collection shuffled. The queue goes in unshuffled and the service shuffles what
     * follows the randomly chosen opener, so the collection's own order is what shuffle is
     * remembered as being turned on over and switching it off restores the album or playlist.
     */
    fun shuffleAll(tracks: List<Track>, contextTitle: String = "") {
        val playable = tracks.distinctBy(Track::id).filter { it.id.isNotBlank() }
        if (playable.isEmpty()) return
        playAll(
            playable,
            startIndex = kotlin.random.Random.Default.nextInt(playable.size),
            contextTitle = contextTitle,
            shuffle = true,
        )
    }

    fun saveDetail(detail: BrowseDetail) {
        when (detail.kind) {
            // `subtitle` is the whole browse line — "Album • 2017" — so putting it in the
            // artist field rendered as "Album • 2017 • 2017" once the year was appended again.
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
            // The service reshuffles the upcoming items itself when the flag turns on, so doing it
            // here too would rewrite the queue twice for one toggle.
            local.setShuffle(!playback.value.shuffle)
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

    fun setAutoplayEnabled(enabled: Boolean) {
        autoplayGate.value = enabled
        graph.settings.updateSettings(settings.value.copy(autoplayEnabled = enabled))
        if (enabled) return
        // Turning it off should undo what it added, not leave the queue full of unasked-for music.
        mutableAutoplayError.value = ""
        autoplayExhaustedSeed = ""
        if (targets.value.selected !is PlaybackTarget.LocalPhone) return
        val upcoming = playback.value.upcoming
        val kept = upcoming.filterNot(Track::autoplayGenerated)
        if (kept.size != upcoming.size) local.replaceUpcoming(kept)
    }

    private data class AutoplayTrigger(
        val seedId: String,
        val remaining: Int,
        val enabled: Boolean,
        val isLocal: Boolean,
    )

    private fun observeAutoplay() {
        // Persisted changes from anywhere else (the Settings screen, the first DataStore read)
        // still have to reach the gate.
        viewModelScope.launch {
            settings.map { it.autoplayEnabled }
                .distinctUntilChanged()
                .collect { autoplayGate.value = it }
        }
        viewModelScope.launch {
            combine(playback, autoplayGate, targets) { snapshot, enabled, target ->
                val upcoming = snapshot.upcoming
                AutoplayTrigger(
                    // The tail of the queue is the seed: recommendations should follow the music the
                    // listener will actually reach, not the track playing several songs earlier.
                    seedId = upcoming.lastOrNull()?.id.orEmpty().ifBlank { snapshot.currentTrack?.id.orEmpty() },
                    remaining = upcoming.size,
                    enabled = enabled,
                    isLocal = target.selected is PlaybackTarget.LocalPhone,
                )
            }
                // Playback ticks every second; without this the refill check would run with it.
                .distinctUntilChanged()
                .collect(::refillAutoplay)
        }
    }

    private fun refillAutoplay(trigger: AutoplayTrigger) {
        if (!trigger.enabled || !trigger.isLocal) return
        if (trigger.remaining > AUTOPLAY_REFILL_THRESHOLD) return
        if (trigger.seedId.isBlank() || !isOnline.value) return
        if (trigger.seedId == autoplaySeedInFlight || trigger.seedId == autoplayExhaustedSeed) return

        autoplaySeedInFlight = trigger.seedId
        mutableAutoplayLoading.value = true
        mutableAutoplayError.value = ""
        viewModelScope.launch {
            runCatching { graph.catalog.upNext(trigger.seedId) }
                .onSuccess { candidates -> appendAutoplayTracks(trigger.seedId, candidates) }
                .onFailure { mutableAutoplayError.value = it.message ?: "Could not load Autoplay recommendations." }
            mutableAutoplayLoading.value = false
            autoplaySeedInFlight = ""
        }
    }

    private fun appendAutoplayTracks(seedId: String, candidates: List<Track>) {
        // Filtered against the queue as it stands rather than against the trigger, because the
        // listener may have queued something, or skipped, while the request was in the air. The
        // append itself filters again on the player's own state, which is the authoritative one;
        // this pass only decides whether the seed is worth reporting as exhausted.
        val snapshot = playback.value
        val known = snapshot.queue.mapTo(mutableSetOf(), Track::id)
        snapshot.currentTrack?.id?.let(known::add)
        val additions = candidates
            .filter { it.id.isNotBlank() && known.add(it.id) }
            .take(AUTOPLAY_QUEUE_LIMIT)
            .map { it.copy(autoplayGenerated = true) }
        if (additions.isEmpty()) {
            autoplayExhaustedSeed = seedId
            mutableAutoplayError.value = "No more recommendations were found."
            return
        }
        // Appended, never written back as a whole tail: the tail in this snapshot is already stale.
        local.appendUpcoming(additions, AUTOPLAY_TOTAL_LIMIT)
    }

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

        /** Refill once the queue is this short, so the fetch lands well before the music stops. */
        const val AUTOPLAY_REFILL_THRESHOLD = 3
        const val AUTOPLAY_QUEUE_LIMIT = 20
        const val AUTOPLAY_TOTAL_LIMIT = 100
    }
}
