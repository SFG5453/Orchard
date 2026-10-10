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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.UpdateState
import dev.sfg.orchard.mobile.artwork.ArtistImages
import dev.sfg.orchard.mobile.artwork.TrackArtwork
import dev.sfg.orchard.mobile.audio.selfDeviceLabel
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.connect.ConnectState
import dev.sfg.orchard.mobile.discord.DiscordPresenceStatus
import dev.sfg.orchard.mobile.download.DownloadItem
import dev.sfg.orchard.mobile.download.DownloadStatus
import dev.sfg.orchard.mobile.lastfm.LastfmState
import dev.sfg.orchard.mobile.listenbrainz.ListenBrainzState
import dev.sfg.orchard.mobile.local.toPlaylist
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.*
import dev.sfg.orchard.mobile.playback.ListeningPartyManager
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import dev.sfg.orchard.mobile.qobuz.QobuzQuality
import dev.sfg.orchard.mobile.qobuz.QobuzStatus
import dev.sfg.orchard.mobile.settings.CacheManager
import dev.sfg.orchard.mobile.social.PartyState
import dev.sfg.orchard.mobile.songlinks.LinkResolution
import dev.sfg.orchard.mobile.songlinks.SongLinksCoordinator
import dev.sfg.orchard.mobile.songlinks.SongShareState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Presentation state holder for the standalone shell and both playback targets. */
class OrchardViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = OrchardGraph.from(application)
    private val songLinksCoordinator = SongLinksCoordinator(graph.songLinks)
    val shareState: StateFlow<SongShareState?> = songLinksCoordinator.shareState
    private val local = LocalPlaybackController(application, viewModelScope)
    val videoPlayer: StateFlow<androidx.media3.common.Player?> = local.player
    private val nowPlaying = NowPlayingMetadata(graph, viewModelScope, local)
    val musicVideo: StateFlow<MusicVideoState> =
        combine(nowPlaying.musicVideo, graph.streams.videoQuality, graph.settings.settings) { state, quality, settings ->
            val current = quality?.takeIf { it.videoId == state.videoId }
            state.copy(height = current?.height ?: 0, heights = current?.heights.orEmpty(), maxHeight = settings.videoMaxHeight)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MusicVideoState())

    /**
     * Scoped here rather than on the graph: it drives [LocalPlaybackController], which is created
     * and closed with this view model, so a longer-lived party would outlive the player it commands.
     */
    private val party = ListeningPartyManager(
        context = application,
        http = graph.http,
        scope = viewModelScope,
        player = local,
        displayName = Build.MODEL.takeIf(String::isNotBlank) ?: application.selfDeviceLabel(),
    )
    val listeningParty: StateFlow<PartyState> = party.state
    private val playbackTargets = PlaybackTargets(application, graph, viewModelScope, party)
    val targets: StateFlow<PlaybackTargetState> = playbackTargets.state
    private val targetPlayback: StateFlow<PlaybackSnapshot> = combine(
        local.snapshot,
        graph.connect.remote,
        targets,
    ) { localSnapshot, remoteSnapshot, targetState ->
        when (targetState.selected) {
            PlaybackTarget.LocalPhone -> localSnapshot
            is PlaybackTarget.Remote -> remoteSnapshot
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSnapshot())
    val playback: StateFlow<PlaybackSnapshot> = combine(
        targetPlayback,
        nowPlaying.artwork,
        nowPlaying.artistCredits,
    ) { snapshot, artwork, artistCredits ->
        val track = snapshot.currentTrack
        if (track == null) return@combine snapshot
        val matchingArtwork = artwork?.takeIf { it.trackId == track.id }
        val matchingArtists = artistCredits?.takeIf { it.first == track.id }?.second.orEmpty()
        snapshot.copy(
            currentTrack = track.copy(
                artworkUrl = matchingArtwork?.staticUrl?.ifBlank { track.artworkUrl } ?: track.artworkUrl,
                animatedArtworkUrl = matchingArtwork?.videoUrl?.ifBlank { track.animatedArtworkUrl }
                    ?: track.animatedArtworkUrl,
                animatedArtworkVerticalUrl = matchingArtwork?.videoUrlVertical
                    ?.ifBlank { track.animatedArtworkVerticalUrl }
                    ?: track.animatedArtworkVerticalUrl,
                artists = matchingArtists.ifEmpty { track.artists },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSnapshot())
    /** [playback] minus its clock: emits on track, queue or transport changes, never on a tick. */
    val playbackState: StateFlow<PlaybackSnapshot> = playback.map { it.withoutClock() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSnapshot())
    /** Stamped only when the values move, so the projection anchor is the real sample time. */
    val playbackClock: StateFlow<PlaybackClock> = playback.map { it.clock(0) }.distinctUntilChanged()
        .map { it.copy(sampledAtMs = android.os.SystemClock.elapsedRealtime()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackClock())

    private val mutableHome = MutableStateFlow<LoadState<List<CatalogSection>>>(LoadState.Loading)
    val home: StateFlow<LoadState<List<CatalogSection>>> = mutableHome.asStateFlow()
    private val details = DetailController(graph, viewModelScope) { showWarning(it) }
    private val searcher = SearchController(graph, viewModelScope, songLinksCoordinator, ::openDetail)
    val query: StateFlow<String> = searcher.query
    val search: StateFlow<LoadState<SearchResults>> = searcher.results
    val detail: StateFlow<LoadState<BrowseDetail>> = details.detail
    val detailRefreshing: StateFlow<Boolean> = details.refreshing
    val detailArtwork: StateFlow<TrackArtwork?> = details.artwork
    val artistImages: StateFlow<ArtistImages?> = details.artistImages
    val library: StateFlow<LibrarySnapshot> = graph.library.library
    /** Songs and playlists kept on this phone, and the one place that edits them. */
    val localLibrary = graph.localLibrary
    val localSnapshot: StateFlow<dev.sfg.orchard.mobile.local.LocalSnapshot> = graph.localLibrary.library
    private val mutableLibraryFilter = MutableStateFlow(LibraryFilter.PLAYLISTS)
    val libraryFilter: StateFlow<LibraryFilter> = mutableLibraryFilter.asStateFlow()
    val settings: StateFlow<OrchardSettings> = graph.settings.settings
    val searchHistory: StateFlow<List<String>> = graph.settings.searchHistory
    val auth: StateFlow<AuthState> = graph.auth.state
    val connect: StateFlow<ConnectState> = graph.connect.state
    val connectRemoteVolume: StateFlow<Float> = graph.connect.remote.map { it.volume }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    val lyrics: StateFlow<LoadState<List<LyricLine>>> = nowPlaying.lyrics
    val discordStatus: StateFlow<DiscordPresenceStatus> = graph.discordPresence.status
    val qobuzStatus: StateFlow<QobuzStatus> = graph.qobuz.status
    val activeTrackIsQobuz: StateFlow<Boolean> = graph.activeTrackIsQobuz.asStateFlow()
    val activeStreamDetail: StateFlow<dev.sfg.orchard.mobile.model.StreamDetail> = graph.activeStreamDetail.asStateFlow()

    fun connectQobuz(token: String, userId: Long) = graph.qobuz.connect(token, userId)
    val maxActive: StateFlow<Boolean> = graph.maxActive

    fun disconnectQobuz() {
        graph.qobuz.disconnect()
        // MAX needs a subscription; fall back to High rather than leave a dead choice selected.
        val current = graph.settings.settings.value
        if (current.audioQuality == AudioQuality.MAX) graph.settings.updateSettings(current.copy(audioQuality = AudioQuality.HIGH))
    }
    suspend fun qobuzAlbumQuality(detail: BrowseDetail) = graph.qobuzResolver.albumQuality(detail)
    fun setQobuzEnabled(enabled: Boolean) = graph.qobuz.setEnabled(enabled)
    fun setQobuzQuality(quality: QobuzQuality) = graph.qobuz.setQuality(quality)

    val lastfmState: StateFlow<LastfmState> = graph.lastfm.state
    val listenBrainzState: StateFlow<ListenBrainzState> = graph.listenBrainz.state

    val activeBitrate: StateFlow<Int> = graph.activeBitrate.asStateFlow()
    val isOnline: StateFlow<Boolean> = graph.networkMonitor.isOnline
    val downloads: StateFlow<Map<String, DownloadItem>> = graph.downloads.downloads
    val downloadedTrackIds: StateFlow<Set<String>> = graph.downloads.downloadedTrackIds
    val downloadingTrackIds: StateFlow<Set<String>> = graph.downloads.downloadingTrackIds
    val totalBytesUsed: StateFlow<Long> = graph.downloads.totalBytesUsedFlow

    fun downloadTrack(track: Track) = graph.downloads.downloadTrack(track)
    fun downloadTracks(tracks: List<Track>) = graph.downloads.downloadTracks(tracks)
    fun removeDownload(videoId: String) = graph.downloads.removeDownload(videoId)
    fun removeAllDownloads() = graph.downloads.removeAllDownloads()
    fun removeDownloads(tracks: List<Track>) = graph.downloads.removeDownloads(tracks.map { it.id })

    /** Playlists a song may join: local songs go to local playlists, online songs to saved ones. */
    fun playlistChoices(track: Track, saved: List<Playlist>): List<Playlist> =
        if (track.isLocal) localSnapshot.value.let { snapshot -> snapshot.playlists.map { it.toPlaylist(snapshot) } } else saved

    fun createPlaylist(title: String, track: Track?, onCreated: (String) -> Unit = {}) =
        details.createPlaylist(title, track, onCreated)
    fun addTrackToPlaylist(playlistId: String, track: Track) = details.addTrackToPlaylist(playlistId, track)
    fun removeTrackFromPlaylist(playlistId: String, track: Track) = details.removeTrackFromPlaylist(playlistId, track)
    fun deletePlaylist(playlistId: String) = details.deletePlaylist(playlistId)

    // Menu entry points. The picker UI supplies the target playlist through the public methods above.
    fun addTrackToPlaylistMenu(track: Track) = graph.postWarning("Choose a playlist to add ${track.title} to.")
    fun removeTrackFromCurrentPlaylist(track: Track) {
        val id = details.activeId
        if (id.isNotBlank()) removeTrackFromPlaylist(id, track)
    }
    fun moveTrackInCurrentPlaylist(fromIndex: Int, toIndex: Int) = details.moveTrackInActivePlaylist(fromIndex, toIndex)

    private val autoplay = AutoplayController(graph, viewModelScope, local, playback, targets)
    val autoplayLoading: StateFlow<Boolean> = autoplay.loading
    val autoplayError: StateFlow<String> = autoplay.error

    private val nonMusicSkipper = NonMusicSkipper(graph, viewModelScope, local, playback, targets, party.state)
    /** The non-music span to offer a Skip button for right now, or null. */
    val nonMusicSegment: StateFlow<dev.sfg.orchard.mobile.model.NonMusicSegment?> = nonMusicSkipper.offered
    fun skipNonMusic() = nonMusicSkipper.skip()

    private val mutableWarning = MutableStateFlow("")
    val warning: StateFlow<String> = mutableWarning.asStateFlow()
    private var warningDismissJob: Job? = null

    private val transport = Transport(graph, viewModelScope, local, party, playback, targets, musicVideo) { showWarning(it) }
    private val sleepTimer = SleepTimer(viewModelScope, playback) {
        transport.remoteOrLocal({ graph.connect.command("pause") }, local::pause)
    }
    val sleepTimerRemainingSeconds: StateFlow<Long> = sleepTimer.remainingSeconds
    val sleepTimerEndOfTrack: StateFlow<Boolean> = sleepTimer.endOfTrack

    private val launcher = QueueLauncher(
        graph, viewModelScope, local, playback, targets, BestMixPreparer(graph, application.cacheDir),
        showWarning = { showWarning(it) }, openDetail = ::openDetail,
    )
    private val accountLinks = AccountLinks(graph, viewModelScope) { showWarning(it) }

    val updateState: StateFlow<UpdateState> = graph.updates.state

    private val mutableCacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = mutableCacheSizeBytes.asStateFlow()

    private val mutableIsClearingCache = MutableStateFlow(false)
    val isClearingCache: StateFlow<Boolean> = mutableIsClearingCache.asStateFlow()

    fun checkForUpdates() = graph.updates.checkForUpdates()
    fun installUpdate(metadata: MobileUpdateMetadata) = graph.updates.downloadAndInstallUpdate(metadata)
    fun dismissUpdate() = graph.updates.dismiss()

    init {
        refreshHome()
        refreshCacheSize()
        observeNetworkState()
        searcher.observe()
        playbackTargets.observe()
        nowPlaying.observeArtwork(targetPlayback)
        nowPlaying.observeArtistCredits(targetPlayback)
        details.observeArtwork()
        nowPlaying.observeLyrics(playback)
        observeAuthentication()
        observeDiscordPresence()
        observeWarnings()
        nowPlaying.observeMusicVideo(targets)
        autoplay.observe()
        nonMusicSkipper.observe()
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
            mutableHome.value = runCatching { graph.catalog.home().sections }
                .fold(
                    onSuccess = { if (it.isEmpty()) LoadState.Empty("No recommendations are available yet.") else LoadState.Content(it) },
                    onFailure = {
                        if (downloads.value.values.any { d -> d.status == DownloadStatus.COMPLETED } || library.value.recentlyPlayed.isNotEmpty()) {
                            LoadState.Error("Orchard is offline. Your downloaded music is available.", true)
                        } else LoadState.Error(it.message ?: "Home could not be loaded.")
                    },
                )
        }
    }

    fun updateQuery(value: String) = searcher.update(value)
    fun runSearch(value: String = query.value) = searcher.run(value)
    fun clearSearchHistory() = graph.settings.clearSearchHistory()
    fun removeSearchHistoryItem(query: String) = graph.settings.removeSearchHistoryItem(query)
    fun selectLibraryFilter(filter: LibraryFilter) { mutableLibraryFilter.value = filter }

    fun openDetail(id: String) = details.load(id, detailSeed(id), preserveContent = false)

    fun refreshDetail() {
        val id = details.activeId
        if (id.isNotBlank()) details.load(id, detailSeed(id), preserveContent = true)
    }

    private fun detailSeed(id: String) = findCatalogItem(id, home.value, search.value, library.value, detail.value)

    suspend fun fetchSectionItems(browseId: String, params: String = ""): List<CatalogItem> {
        if (browseId.isBlank()) return emptyList()
        return runCatching { graph.catalog.sectionItems(browseId, params) }.getOrDefault(emptyList())
    }

    /** The transition Adaptive mix has planned out of the current track, for the scrubber. */
    val transitionMarker = graph.transitionMarker

    fun play(track: Track, contextTitle: String = "") = launcher.play(track, contextTitle)
    fun playAll(tracks: List<Track>, startIndex: Int = 0, contextTitle: String = "", shuffle: Boolean = false) =
        launcher.playAll(tracks, startIndex, contextTitle, shuffle)
    fun playFromSearch(query: String) = launcher.playFromSearch(query)
    fun shuffleAll(tracks: List<Track>, contextTitle: String = "") = launcher.shuffleAll(tracks, contextTitle)
    val bestMixJob: StateFlow<dev.sfg.orchard.mobile.model.BestMixJob?> get() = launcher.bestMixJob
    fun playBestMix(tracks: List<Track>, title: String, onProgress: (String) -> Unit = {}, onComplete: () -> Unit = {}) =
        launcher.playBestMix(tracks, title, onProgress, onComplete)
    fun bestMixUpcoming(onProgress: (String) -> Unit = {}, onComplete: () -> Unit = {}) =
        launcher.bestMixUpcoming(onProgress, onComplete)
    fun playCollection(id: String, contextTitle: String = "", shuffle: Boolean = false) =
        launcher.playCollection(id, contextTitle, shuffle)
    fun playItem(item: CatalogItem, shuffle: Boolean = false) = launcher.playItem(item, shuffle)

    fun saveDetail(detail: BrowseDetail) = details.save(detail)

    fun playNext(track: Track) = transport.playNext(track)
    fun addToQueue(track: Track) = transport.addToQueue(track)
    fun togglePlayback() = transport.togglePlayback()
    fun toggleMusicVideo() = transport.toggleMusicVideo()
    fun setVideoMaxHeight(height: Int) = transport.setVideoMaxHeight(height)
    fun playMusicVideo(track: Track, contextTitle: String = "") {
        play(track, contextTitle)
        transport.showMusicVideoWhenReady(track.id)
    }
    fun next() = transport.next()
    fun previous() = transport.previous()
    fun seek(positionMs: Long) = transport.seek(positionMs)
    fun toggleShuffle() = transport.toggleShuffle()
    fun cycleRepeat() = transport.cycleRepeat()
    fun playQueueIndex(index: Int) = transport.playQueueIndex(index)
    fun removeQueueIndex(index: Int) = transport.removeQueueIndex(index)
    fun moveQueueItem(from: Int, to: Int) = transport.moveQueueItem(from, to)
    fun clearUpcoming() = transport.clearUpcoming()
    fun clearQueue() = transport.clearQueue()

    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes)
    fun startSleepTimerAtEndOfTrack() = sleepTimer.startAtEndOfTrack()
    fun cancelSleepTimer() = sleepTimer.cancel()

    fun setAutoplayEnabled(enabled: Boolean) = autoplay.setEnabled(enabled)

    fun setRemoteVolume(volume: Float) =
        graph.connect.command("set_volume", JSONObject().put("volume", volume.coerceIn(0f, 1f).toDouble()))

    fun selectTarget(target: PlaybackTarget) = playbackTargets.select(target)

    /** Stops controlling another device; this phone becomes the player again. */
    fun disconnectDevice() = graph.connect.stopControlling()

    fun clearConnectMessage() = graph.connect.clearMessage()

    // Only this phone's name is ours to change; other devices name themselves.
    fun renameDevice(device: PlaybackDevice, newName: String) {
        if (device.isLocal) playbackTargets.renameLocal(newName)
    }
    fun toggleLiked(track: Track) {
        val liked = graph.library.library.value.likedTracks.none { it.id == track.id }
        graph.library.setLiked(track, liked)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.playlistActions.setLiked(track, liked) } }
                .onFailure {
                    graph.library.setLiked(track, !liked)
                    graph.postWarning(it.message ?: "Could not update liked music.")
                }
        }
    }
    fun updateSettings(value: OrchardSettings) = graph.settings.updateSettings(value)

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val size = CacheManager.calculateCacheSizeBytes(getApplication())
            mutableCacheSizeBytes.value = size
        }
    }

    fun clearCache(onComplete: ((Long) -> Unit)? = null) {
        if (mutableIsClearingCache.value) return
        viewModelScope.launch {
            mutableIsClearingCache.value = true
            val bytesCleared = withContext(Dispatchers.IO) {
                CacheManager.clearAllCache(getApplication(), graph)
            }
            mutableCacheSizeBytes.value = withContext(Dispatchers.IO) {
                CacheManager.calculateCacheSizeBytes(getApplication())
            }
            mutableIsClearingCache.value = false
            onComplete?.invoke(bytesCleared)
        }
    }

    fun beginSignIn() = graph.auth.beginSignIn()
    fun completeSignIn(cookie: String, visitorData: String, dataSyncId: String, accountIndex: Int = 0, switchingAccount: Boolean = false, pageAvatarUrl: String = "") =
        graph.auth.completeSignIn(cookie, visitorData, dataSyncId, accountIndex, switchingAccount, pageAvatarUrl)
    fun youtubeSession() = graph.auth.session()
    fun cancelSignIn() = graph.auth.cancelSignIn()
    fun signOut() = graph.auth.signOut()

    fun shareTrack(track: Track) =
        if (track.isLocal) showWarning("Files on your phone cannot be shared as links.") else songLinksCoordinator.shareTrack(track)
    fun shareCollection(detail: BrowseDetail) =
        if (dev.sfg.orchard.mobile.local.isLocalPlaylistId(detail.id)) showWarning("Playlists on your phone cannot be shared as links.")
        else songLinksCoordinator.shareCollection(detail)
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
                null -> Unit
            }
        }
    }
    override fun onCleared() {
        party.leaveParty(closeRoom = false)
        local.close()
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
                    graph.discordPresence.updatePlayback(snap)
                }
            }
        }
    }

    private fun observeWarnings() {
        viewModelScope.launch { graph.warningEvent.collect { showWarning(it) } }
        viewModelScope.launch { party.messages.collect { showWarning(it) } }
    }

    fun connectLastfm(context: android.content.Context) = accountLinks.connectLastfm(context)
    fun completeLastfmConnection() = accountLinks.completeLastfmConnection()
    fun disconnectLastfm() = graph.lastfm.disconnect()
    fun connectListenBrainz(token: String) = accountLinks.connectListenBrainz(token)
    fun disconnectListenBrainz() = graph.listenBrainz.disconnect()

    fun createListeningParty() = viewModelScope.launch {
        runCatching { party.createParty() }
            .onFailure { showWarning(it.message ?: "Could not start the listening party.") }
    }

    fun joinListeningParty(code: String) = viewModelScope.launch {
        runCatching { party.joinParty(code) }
            .onFailure { showWarning(it.message ?: "Could not join that listening party.") }
    }

    fun leaveListeningParty() = party.leaveParty()

    fun transferListeningPartyHost(participantId: String) = party.transferHost(participantId)
    private companion object {
        const val TAG = "OrchardViewModel"
    }
}
