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
import dev.sfg.orchard.mobile.catalog.needsAudioVersionLookup
import dev.sfg.orchard.mobile.model.*
import dev.sfg.orchard.mobile.connect.PlaybackTargetCoordinator
import dev.sfg.orchard.mobile.playback.AutoplayRecommendations
import dev.sfg.orchard.mobile.playback.ListeningPartyManager
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import dev.sfg.orchard.mobile.playback.QueueEditor
import dev.sfg.orchard.mobile.auth.SupabaseSyncService
import dev.sfg.orchard.mobile.playback.smart.BestMixSorter
import dev.sfg.orchard.mobile.playback.smart.TrackFeatures
import dev.sfg.orchard.mobile.social.PartyState
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import dev.sfg.orchard.mobile.songlinks.LinkResolution
import dev.sfg.orchard.mobile.songlinks.SongLinksCoordinator
import dev.sfg.orchard.mobile.songlinks.SongShareState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Presentation state holder for the standalone shell and both playback targets. */
@OptIn(FlowPreview::class)
class OrchardViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = OrchardGraph.from(application)
    private val songLinksCoordinator = SongLinksCoordinator(graph.songLinks, viewModelScope)
    val shareState: StateFlow<SongShareState?> = songLinksCoordinator.shareState
    private val local = LocalPlaybackController(application, viewModelScope)

    /**
     * Scoped here rather than on the graph, unlike most repositories: it drives
     * [LocalPlaybackController], which is itself created and closed with this view model, so a
     * longer-lived party would outlive the player it commands.
     */
    private val party = ListeningPartyManager(
        context = application,
        http = graph.http,
        scope = viewModelScope,
        player = local,
        displayName = Build.MODEL.takeIf(String::isNotBlank) ?: application.selfDeviceLabel(),
    )
    val listeningParty: StateFlow<PartyState> = party.state
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
        runCatching { withContext(Dispatchers.IO) { graph.playlistActions.remove(playlistId, track.id) } }
            .onSuccess { applyRemovedPlaylistTrack(playlistId, track.id) }
            .onFailure { graph.postWarning(it.message ?: "Could not remove track from playlist.") }
    }

    /**
     * Reflect a confirmed removal without immediately re-reading YouTube's eventually consistent
     * playlist page. Removing by index matters because playlists may contain the same song twice.
     */
    private fun applyRemovedPlaylistTrack(playlistId: String, videoId: String) {
        val active = (mutableDetail.value as? LoadState.Content)?.value ?: return
        if (active.id.removePrefix("VL") != playlistId.removePrefix("VL")) return
        val updated = active.withPlaylistTrackRemoved(videoId)
        if (updated === active) return
        mutableDetail.value = LoadState.Content(updated)
        graph.library.refreshPlaylist(
            Playlist(updated.id, updated.title, updated.subtitle, updated.artworkUrl, updated.description, updated.tracks),
        )
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

    private val mutableSleepTimerRemainingSeconds = MutableStateFlow(0L)
    val sleepTimerRemainingSeconds: StateFlow<Long> = mutableSleepTimerRemainingSeconds.asStateFlow()
    private val mutableSleepTimerEndOfTrack = MutableStateFlow(false)
    val sleepTimerEndOfTrack: StateFlow<Boolean> = mutableSleepTimerEndOfTrack.asStateFlow()
    private var sleepTimerJob: Job? = null
    private var detailJob: Job? = null

    val updateState: StateFlow<dev.sfg.orchard.mobile.UpdateState> = graph.updates.state

    fun checkForUpdates() = graph.updates.checkForUpdates()

    fun installUpdate(metadata: dev.sfg.orchard.mobile.MobileUpdateMetadata) =
        graph.updates.downloadAndInstallUpdate(metadata)

    fun dismissUpdate() = graph.updates.dismiss()

    init {
        refreshHome()
        observeNetworkState()
        observeSearch()
        observeRemoteDevice()
        observeLocalDeviceName()
        observeArtwork()
        observeDetailArtwork()
        observeLyrics()
        observeAuthentication()
        observeDiscordPresence()
        observeWarnings()
        observeLocalAudioVersion()
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
    fun removeSearchHistoryItem(query: String) = graph.settings.removeSearchHistoryItem(query)
    fun selectLibraryFilter(filter: LibraryFilter) { mutableLibraryFilter.value = filter }

    fun openDetail(id: String) {
        val seed = findCatalogItem(id, home.value, search.value, library.value, detail.value)
        // A collection now publishes several times as its pages land, so a load left running after
        // the listener moved on would keep writing its pages over the collection they opened next.
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
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

            // Each page replaces the last, so the collection appears as soon as its first page
            // lands and grows underneath the listener instead of holding a spinner until an
            // endless mix exhausts its continuation budget.
            try {
                graph.catalog.browsePages(id).collect { page ->
                    mutableDetail.value = LoadState.Content(page.withSeed(seed))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Continuation failures are already absorbed by the repository, so reaching here
                // means the first page never arrived and there is nothing on screen to keep.
                val offline = dev.sfg.orchard.mobile.download.OfflineDetailSynthesizer.synthesize(
                    id = id,
                    seed = seed,
                    downloadedItems = downloads.value.values.toList(),
                    library = library.value,
                )
                mutableDetail.value = offline?.let { LoadState.Content(it) }
                    ?: LoadState.Error(error.message ?: "This collection could not be loaded.")
            }
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

    /** Verifies queued versions in playback order without bursting one search per explicit row. */
    private fun CoroutineScope.resolveRemainingAudioVersions(queue: List<Track>, startIndex: Int) {
        val lookupOrder = (startIndex + 1 until queue.size) + (0 until startIndex)
        launch {
            lookupOrder.forEach { index ->
                val track = queue[index]
                if (!track.needsAudioVersionLookup()) return@forEach
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

    /**
     * Orders [tracks] via Best Mix algorithm and starts playback.
     * If cloud sync is enabled, attempts to fetch features from Supabase first.
     * Otherwise, ensures undownloaded tracks are downloaded, then extracts audio features
     * locally via native AudioDecoder & TrackFeatures before sorting.
     */
    fun playBestMix(
        tracks: List<Track>,
        title: String,
        onProgress: (String) -> Unit = {},
        onComplete: () -> Unit = {},
    ) = viewModelScope.launch {
        if (tracks.isEmpty()) {
            onComplete()
            return@launch
        }
        val currentSettings = settings.value
        val syncService = SupabaseSyncService(getApplication())
        val featuresMap = mutableMapOf<String, TrackFeatures.Features>()

        // 1. Check in-memory cached features first
        for (track in tracks) {
            BestMixSorter.getCachedFeatures(track.id)?.let { featuresMap[track.id] = it }
        }

        // 2. Fetch from Supabase if enabled and tracks are missing
        if (currentSettings.bestMixSupabaseSync && featuresMap.size < tracks.size) {
            val neededIds = tracks.map { it.id }.filter { it !in featuresMap }
            onProgress("Checking cloud analysis...")
            val cloudFeatures = withContext(Dispatchers.IO) {
                syncService.fetchTrackFeatures(neededIds)
            }
            cloudFeatures.forEach { (id, features) ->
                featuresMap[id] = features
                BestMixSorter.cacheFeatures(id, features)
            }
        }

        // 3. For remaining tracks missing features, analyze downloaded files in parallel
        val missingTracks = tracks.filter { it.id !in featuresMap }
        if (missingTracks.isNotEmpty()) {
            // Check if any tracks need downloading
            val currentDownloads = graph.downloads.downloads.value
            val undownloaded = missingTracks.filter { track ->
                val item = currentDownloads[track.id]
                item == null || item.status != dev.sfg.orchard.mobile.download.DownloadStatus.COMPLETED ||
                    item.filePath.isBlank() || !File(item.filePath).exists()
            }

            if (undownloaded.isNotEmpty()) {
                onProgress("Downloading tracks (0/${undownloaded.size})...")
                graph.downloads.downloadTracks(undownloaded)
                val targetIds = undownloaded.map { it.id }.toSet()
                // Wait for downloads to complete or timeout (up to 90s)
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 90_000L) {
                    val completed = graph.downloads.downloads.value
                    val finishedCount = targetIds.count { id ->
                        val item = completed[id]
                        item?.status == dev.sfg.orchard.mobile.download.DownloadStatus.COMPLETED ||
                            item?.status == dev.sfg.orchard.mobile.download.DownloadStatus.FAILED
                    }
                    onProgress("Downloading tracks ($finishedCount/${undownloaded.size})...")
                    if (finishedCount >= targetIds.size) break
                    delay(500)
                }
            }

            // High-speed multi-core parallel local audio analysis
            val allDownloads = graph.downloads.store.loadAll()
            val totalToAnalyze = missingTracks.size
            val completedCount = AtomicInteger(0)
            val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val semaphore = Semaphore(parallelism)

            onProgress("Analyzing audio (0/$totalToAnalyze)...")
            coroutineScope {
                missingTracks.map { track ->
                    async(Dispatchers.Default) {
                        semaphore.withPermit {
                            val file = allDownloads[track.id]?.filePath?.let { File(it) }
                            if (file != null && file.exists()) {
                                val localFeatures = BestMixSorter.analyzeLocalTrack(track, file)
                                if (localFeatures != null) {
                                    synchronized(featuresMap) {
                                        featuresMap[track.id] = localFeatures
                                    }
                                }
                            }
                            val done = completedCount.incrementAndGet()
                            onProgress("Analyzing audio ($done/$totalToAnalyze)...")
                        }
                    }
                }.awaitAll()
            }
        }

        onProgress("Sorting Best Mix...")
        val sorted = withContext(Dispatchers.Default) {
            BestMixSorter.sort(tracks, featuresMap)
        }
        playAll(sorted, contextTitle = "$title • Best Mix")
        onComplete()
    }

    /**
     * Orders only the upcoming tracks in the active queue with Best Mix harmonic & tempo transitions
     * without interrupting current playback.
     */
    fun bestMixUpcoming(
        onProgress: (String) -> Unit = {},
        onComplete: () -> Unit = {},
    ) = viewModelScope.launch {
        val currentSnapshot = playback.value
        val upcoming = currentSnapshot.upcoming
        if (upcoming.size <= 1) {
            onComplete()
            return@launch
        }
        val currentSettings = settings.value
        val syncService = SupabaseSyncService(getApplication())
        val featuresMap = mutableMapOf<String, TrackFeatures.Features>()

        // 1. Check in-memory cache
        for (track in upcoming) {
            BestMixSorter.getCachedFeatures(track.id)?.let { featuresMap[track.id] = it }
        }

        // 2. Fetch from Supabase if enabled
        if (currentSettings.bestMixSupabaseSync && featuresMap.size < upcoming.size) {
            val neededIds = upcoming.map { it.id }.filter { it !in featuresMap }
            onProgress("Checking cloud analysis...")
            val cloudFeatures = withContext(Dispatchers.IO) { syncService.fetchTrackFeatures(neededIds) }
            cloudFeatures.forEach { (id, features) ->
                featuresMap[id] = features
                BestMixSorter.cacheFeatures(id, features)
            }
        }

        // 3. Analyze local files in parallel for remaining
        val missing = upcoming.filter { it.id !in featuresMap }
        if (missing.isNotEmpty()) {
            val allDownloads = graph.downloads.store.loadAll()
            val totalToAnalyze = missing.size
            val completedCount = AtomicInteger(0)
            val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val semaphore = Semaphore(parallelism)

            onProgress("Analyzing audio (0/$totalToAnalyze)...")
            coroutineScope {
                missing.map { track ->
                    async(Dispatchers.Default) {
                        semaphore.withPermit {
                            val file = allDownloads[track.id]?.filePath?.let { File(it) }
                            if (file != null && file.exists()) {
                                val localFeatures = BestMixSorter.analyzeLocalTrack(track, file)
                                if (localFeatures != null) {
                                    synchronized(featuresMap) { featuresMap[track.id] = localFeatures }
                                }
                            }
                            val done = completedCount.incrementAndGet()
                            onProgress("Analyzing audio ($done/$totalToAnalyze)...")
                        }
                    }
                }.awaitAll()
            }
        }

        onProgress("Sorting queue...")
        val sortedUpcoming = withContext(Dispatchers.Default) {
            BestMixSorter.sort(upcoming, featuresMap)
        }
        local.replaceUpcoming(sortedUpcoming)
        onComplete()
    }

    /**
     * Plays all tracks from a collection (playlist, album, artist, or mix) given its id.
     */
    fun playCollection(id: String, contextTitle: String = "", shuffle: Boolean = false) {
        Log.d(TAG, "playCollection: id='$id', contextTitle='$contextTitle', shuffle=$shuffle")
        viewModelScope.launch {
            val detail = runCatching { graph.catalog.browse(id) }.getOrNull()
            val tracks = detail?.tracks.orEmpty().filter { it.id.isNotBlank() }
            if (tracks.isNotEmpty()) {
                playAll(
                    tracks = tracks,
                    startIndex = if (shuffle) kotlin.random.Random.Default.nextInt(tracks.size) else 0,
                    contextTitle = contextTitle.ifBlank { detail?.title.orEmpty() },
                    shuffle = shuffle,
                )
            } else {
                showWarning("No playable tracks found")
            }
        }
    }

    fun playItem(item: CatalogItem, shuffle: Boolean = false) {
        when (item) {
            is CatalogItem.Song -> play(item.track, contextTitle = item.track.title)
            is CatalogItem.Collection -> playCollection(item.playlist.id, contextTitle = item.title, shuffle = shuffle)
            is CatalogItem.Record -> playCollection(item.album.id, contextTitle = item.title, shuffle = shuffle)
            is CatalogItem.Performer -> playCollection(item.artist.id, contextTitle = item.title, shuffle = shuffle)
            is CatalogItem.Category -> openDetail(item.stableId)
        }
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

    fun playNext(track: Track) {
        remoteOrLocal(
            { if (connectProtocolVersion.value >= 2) graph.connect.playNext(track) },
            { viewModelScope.launch { local.playNext(graph.audioVersions.audioVersion(track)) } },
        )
    }

    fun addToQueue(track: Track) {
        remoteOrLocal(
            { if (connectProtocolVersion.value >= 2) graph.connect.addToQueue(track) },
            { viewModelScope.launch { local.addToQueue(graph.audioVersions.audioVersion(track)) } },
        )
    }

    fun togglePlayback() = partyOrLocal(
        if (playback.value.isPlaying) "pause" else "play",
        { graph.connect.send(ConnectCommand.TogglePlayback) },
        local::toggle,
    )
    fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) return
        cancelSleepTimer()
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        mutableSleepTimerRemainingSeconds.value = minutes * 60L
        sleepTimerJob = viewModelScope.launch {
            while (true) {
                val remaining = ((deadline - System.currentTimeMillis() + 999L) / 1_000L).coerceAtLeast(0L)
                mutableSleepTimerRemainingSeconds.value = remaining
                if (remaining == 0L) break
                delay(1_000L)
            }
            pauseForSleepTimer()
            clearSleepTimerState()
        }
    }

    fun startSleepTimerAtEndOfTrack() {
        val trackId = playback.value.currentTrack?.id ?: return
        cancelSleepTimer()
        mutableSleepTimerEndOfTrack.value = true
        sleepTimerJob = viewModelScope.launch {
            playback.map { it.currentTrack?.id }
                .dropWhile { it == trackId }
                .first()
            pauseForSleepTimer()
            clearSleepTimerState()
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        clearSleepTimerState()
    }

    private fun clearSleepTimerState() {
        mutableSleepTimerRemainingSeconds.value = 0L
        mutableSleepTimerEndOfTrack.value = false
    }

    private fun pauseForSleepTimer() {
        if (!playback.value.isPlaying) return
        remoteOrLocal({ graph.connect.send(ConnectCommand.TogglePlayback) }, local::pause)
    }

    fun next() = partyOrLocal("next", { graph.connect.send(ConnectCommand.Next) }, local::next)
    fun previous() = partyOrLocal("previous", { graph.connect.send(ConnectCommand.Previous) }, local::previous)
    fun seek(positionMs: Long) {
        if (party.requestSeek(positionMs)) return
        remoteOrLocal(
            { graph.connect.send(ConnectCommand.Seek(positionMs / 1_000.0)) },
            { local.seek(positionMs) },
        )
    }
    fun toggleShuffle() = partyOrLocal(
        "toggle-shuffle",
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
        val known = snapshot.queue + listOfNotNull(snapshot.currentTrack)
        val additions = AutoplayRecommendations
            .select(known, candidates, AUTOPLAY_QUEUE_LIMIT)
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
        if (target is PlaybackTarget.Remote) {
            graph.connect.connectTo(target.deviceId)
        }
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
    fun disconnectDevice(deviceId: String? = null) {
        if (deviceId != null) {
            graph.connect.removeDevice(deviceId)
        } else {
            graph.connect.disconnect()
        }
    }

    fun renameDevice(device: PlaybackDevice, newName: String) {
        if (device.isLocal) {
            val trimmed = newName.trim()
            val updated = settings.value.copy(customDeviceName = trimmed)
            graph.settings.updateSettings(updated)
            val base = Build.MODEL.takeIf(String::isNotBlank) ?: getApplication<Application>().selfDeviceLabel()
            val effective = trimmed.ifBlank { base }
            targetCoordinator.updateLocalDeviceName(name = effective, customName = trimmed)
            mutableTargets.value = targetCoordinator.state
            party.updateDisplayName(effective)
        } else {
            graph.connect.renameDevice(device.id, newName.trim())
        }
    }

    fun removeDevice(deviceId: String) = graph.connect.removeDevice(deviceId)
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
        party.leaveParty(closeRoom = false)
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
            graph.connect.devices.collect { remotes ->
                targetCoordinator.updateRemoteDevices(remotes)
                mutableTargets.value = targetCoordinator.state
            }
        }
    }

    private fun observeLocalDeviceName() {
        viewModelScope.launch {
            settings.map { it.customDeviceName }.distinctUntilChanged().collect { custom ->
                val base = Build.MODEL.takeIf(String::isNotBlank) ?: getApplication<Application>().selfDeviceLabel()
                val effective = custom.ifBlank { base }
                targetCoordinator.updateLocalDeviceName(name = effective, customName = custom)
                mutableTargets.value = targetCoordinator.state
                party.updateDisplayName(effective)
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
        viewModelScope.launch { party.messages.collect { showWarning(it) } }
    }

    /** Repairs an explicit id restored from an older queue before it can keep playing clean audio. */
    private fun observeLocalAudioVersion() {
        viewModelScope.launch {
            local.snapshot
                .map { it.currentTrack }
                .filterNotNull()
                .distinctUntilChangedBy { Triple(it.id, it.explicit, it.musicVideoType) }
                .collectLatest { track ->
                    if (!track.needsAudioVersionLookup()) return@collectLatest
                    val audio = graph.audioVersions.audioVersion(track)
                    if (audio.id != track.id) local.replaceCurrent(track.id, audio)
                }
        }
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

    /**
     * Transport dispatch for a device that may be a listening-party guest.
     *
     * A guest sends the intent to the host and changes nothing locally; the host's answering
     * snapshot is what actually moves this player, so every device in the room turns over
     * together instead of one running ahead.
     */
    private fun partyOrLocal(action: String, remote: () -> Unit, localAction: () -> Unit) {
        if (party.interceptTransport(action)) return
        remoteOrLocal(remote, localAction)
    }

    // ---------------------------------------------------------------- listening party

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

        /** Refill once the queue is this short, so the fetch lands well before the music stops. */
        const val AUTOPLAY_REFILL_THRESHOLD = 3
        const val AUTOPLAY_QUEUE_LIMIT = 20
        const val AUTOPLAY_TOTAL_LIMIT = 100
    }
}

/** Returns the same instance when [videoId] is absent; otherwise removes one playlist row. */
internal fun BrowseDetail.withPlaylistTrackRemoved(videoId: String): BrowseDetail {
    val index = tracks.indexOfFirst { it.id == videoId }
    if (index < 0) return this
    return copy(tracks = tracks.toMutableList().apply { removeAt(index) })
}
