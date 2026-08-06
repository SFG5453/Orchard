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

package dev.sfg.orchard.mobile.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.sfg.orchard.connect.R
import dev.sfg.orchard.connect.app.MainActivity
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.playback.smart.CrossfadeMode
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Process-level owner of local phone playback.
 *
 * UI components connect through Media3 controllers and can disappear without interrupting playback.
 * ExoPlayer owns audio focus, noisy-output handling, Bluetooth/headset media buttons, automatic
 * queue progression, and buffering.
 */
@UnstableApi
class OrchardPlaybackService : MediaLibraryService() {
    // Crossfade needs two decoders overlapping, so playback always runs on a pair of players
    // and
    // `player` is whichever one currently owns the session and the queue.
    private lateinit var player: ExoPlayer
    private lateinit var spare: ExoPlayer
    private lateinit var crossfade: CrossfadeEngine
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var browseTree: OrchardMediaLibrary
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var streamResolver: YouTubeStreamResolver
    private lateinit var streamCache: StreamCache
    private lateinit var analyzer: dev.sfg.orchard.mobile.playback.smart.TrackAnalyzer
    private lateinit var preparer: dev.sfg.orchard.mobile.playback.smart.TransitionPreparer
    // One filter per player, inserted in each one's audio pipeline. They follow the players
    // through
    // a handoff rather than the roles, so a filter never ends up automating the wrong track.
    private lateinit var playerFilter: dev.sfg.orchard.mobile.playback.smart.TransitionFilter
    private lateinit var spareFilter: dev.sfg.orchard.mobile.playback.smart.TransitionFilter
    private val handler = Handler(Looper.getMainLooper())
    // Browse requests hit the network, so they are answered off the session thread.
    private val browseScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
    private var retriedMediaId = ""
    private var artworkHydrationId = ""

    private val positionSaver =
        object : Runnable {
            override fun run() {
                persistPlayback()
                if (::player.isInitialized && player.isPlaying)
                    handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }

    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(this)
        val graph = OrchardGraph.from(this)
        browseTree = OrchardMediaLibrary(graph)
        streamResolver =
            YouTubeStreamResolver(
                client = graph.http,
                qualityProvider = { graph.settings.settings.value.audioQuality },
                visitorStore = PrefsVisitorIdentityStore(this),
                onWarning = { message -> graph.postWarning(message) },
                sessionProvider = graph.auth,
                downloadManager = graph.downloads,
            )
        streamResolver.warmUp()
        streamCache =
            StreamCache(context = this, maxBytes = graph.settings.settings.value.cacheSizeBytes) {
                graph.settings.settings.value.audioQuality
            }
        analyzer = dev.sfg.orchard.mobile.playback.smart.TrackAnalyzer(this, streamCache)
        preparer = dev.sfg.orchard.mobile.playback.smart.TransitionPreparer(this, streamCache)
        graph.analysisLookup = analyzer::analysisFor
        // Caching finishes seconds after the player events that asked for it, so completion
        // has to
        // re-drive analysis itself. Hopped onto the main thread because prefetchAround
        // reads player
        // state, which is not safe to touch from the prefetch pool.
        streamCache.onCached = {
            handler.post {
                if (::player.isInitialized) {
                    prefetchAround(player)
                    // The moment the current track finishes caching is the
                    // moment its true
                    // bitrate becomes measurable, and no player event marks it.
                    publishBitrate()
                }
            }
        }
        analyzer.onAnalysed = {
            handler.post { if (::player.isInitialized) prefetchAround(player) }
        }
        playerFilter = dev.sfg.orchard.mobile.playback.smart.TransitionFilter()
        spareFilter = dev.sfg.orchard.mobile.playback.smart.TransitionFilter()
        player = buildPlayer(graph.http, handlesAudioFocus = true, filter = playerFilter)
        spare = buildPlayer(graph.http, handlesAudioFocus = false, filter = spareFilter)
        restorePlayback()
        mediaSession =
            MediaLibrarySession.Builder(this, OrchardSessionPlayer(player), sessionCallback)
                .setSessionActivity(mainActivityIntent())
                // Use the same OkHttp stack as the app's artwork/UI requests. The default
                // DataSourceBitmapLoader uses URLConnection, which can fail on provider artwork
                // URLs even though the cover displays correctly in the app; WearOS then falls
                // back to its gray notification background.
                .setBitmapLoader(
                    DataSourceBitmapLoader.Builder(this)
                        .setDataSourceFactory(OkHttpDataSource.Factory(graph.http))
                        .setMaximumOutputDimension(512)
                        .setMakeShared(true)
                        .build()
                )
                .build()
        updateCustomLayout()
        handler.post { hydrateCurrentArtwork() }
        // Media3 ships a generic play glyph as the notification's small icon; the media
        // player badges that icon, so without this the tile is stamped with a play symbol
        // instead of the Orchard mark.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )
        crossfade =
            CrossfadeEngine(
                handler = handler,
                config = {
                    val settings = graph.settings.settings.value
                    CrossfadeEngine.Config(
                        enabled = settings.crossfadeMs > 0,
                        fadeSeconds = settings.crossfadeMs / 1000.0,
                        mode =
                            if (settings.smartCrossfade) CrossfadeMode.SMART
                            else CrossfadeMode.STANDARD,
                    )
                },
                analysisFor = analyzer::analysisFor,
                preparedFor = { outgoing, incoming -> preparer.preparedFor(outgoing, incoming) },
                filters = { playerFilter to spareFilter },
                onPlan = { plan ->
                    if (plan != null) prepareTransition(plan)
                    graph.transitionMarker.value = plan?.let {
                        dev.sfg.orchard.mobile.model.TransitionMarker(
                            trackId = player.currentMediaItem?.mediaId.orEmpty(),
                            startMs = (it.transitionStart * 1000).toLong(),
                            endMs = (it.transitionEnd * 1000).toLong(),
                            style = it.transitionStyle.name.lowercase(),
                        )
                    }
                },
                onHandoff = ::adoptPlayer,
            )
        crossfade.start(player, spare)
        player.addListener(playbackListener)
    }

    /**
     * Moves the session onto the player the crossfade just faded up. The outgoing player keeps its
     * queue only until the engine stops it, so everything authoritative moves across here.
     */
    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        // Released first so the incoming player's request is uncontested.
        setFocusOwner(outgoing, owns = false)
        setFocusOwner(incoming, owns = true)
        outgoing.removeListener(playbackListener)
        incoming.addListener(playbackListener)
        player = incoming
        spare = outgoing
        // The filters travel with their players, so the pair swaps too.
        val heldFilter = playerFilter
        playerFilter = spareFilter
        spareFilter = heldFilter
        mediaSession.player = OrchardSessionPlayer(incoming)
        persistPlayback()
        updateCustomLayout()
        prefetchAround(incoming)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaSession

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        browseScope.cancel()
        if (::crossfade.isInitialized) crossfade.release()
        if (::player.isInitialized) {
            persistPlayback()
            player.release()
        }
        if (::spare.isInitialized) spare.release()
        if (::mediaSession.isInitialized) mediaSession.release()
        OrchardGraph.from(this).analysisLookup = null
        if (::preparer.isInitialized) preparer.release()
        if (::analyzer.isInitialized) analyzer.release()
        if (::streamCache.isInitialized) streamCache.release()
        super.onDestroy()
    }

    /**
     * Only one player may handle audio focus at a time. Two focus-handling players in the same
     * process fight: the standby's `play()` takes focus during a crossfade and ExoPlayer pauses the
     * player that lost it, cutting the outgoing track instead of fading it. Focus follows the
     * session, so it is handed over with it.
     */
    private fun setFocusOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, owns)
    }

    private fun buildPlayer(
        client: OkHttpClient,
        handlesAudioFocus: Boolean,
        filter: dev.sfg.orchard.mobile.playback.smart.TransitionFilter,
    ): ExoPlayer {
        val httpFactory =
            OkHttpDataSource.Factory(client).setUserAgent(YouTubeStreamResolver.CLIENT_USER_AGENT)
        val upstreamFactory = DefaultDataSource.Factory(this, httpFactory)
        val resolvingFactory =
            ResolvingDataSource.Factory(upstreamFactory) { original ->
                Log.d(TAG, "resolvingFactory: request uri=${original.uri}")
                if (!MediaItemMapper.isOrchardUri(original.uri)) return@Factory original
                val videoId = original.uri.lastPathSegment.orEmpty()
                Log.d(TAG, "resolvingFactory: resolving videoId=$videoId")
                val stream = streamResolver.resolve(videoId)
                if (stream.bitrateKbps > 0)
                    OrchardGraph.from(this@OrchardPlaybackService).activeBitrate.value =
                        stream.bitrateKbps
                Log.d(TAG, "resolvingFactory: resolved $videoId to url=${stream.url.take(60)}...")
                original.withUri(stream.url.toUri())
            }
        // Cache above resolution: it keys on the stable orchard:// URI, and a hit skips the
        // resolver entirely rather than re-resolving a CDN URL it does not need.
        val cachingFactory = streamCache.dataSourceFactory(resolvingFactory)
        val mediaSourceFactory =
            DefaultMediaSourceFactory(this).setDataSourceFactory(cachingFactory)
        val loadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    WHOLE_TRACK_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        // A renderers factory rather than the default, so the transition filter sits in the
        // audio
        // pipeline itself. Automating gain on the player only scales the whole signal; the
        // filter
        // ride that makes a blend read as a mix has to happen inside the sink.
        val renderersFactory =
            object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
                @Suppress(
                    "DEPRECATION"
                ) // TODO: MIGRATE THIS LATER, don't want to break audio for now
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): androidx.media3.exoplayer.audio.AudioSink =
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(filter))
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
            }
        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(AUDIO_ATTRIBUTES, handlesAudioFocus)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }

    private fun restorePlayback() {
        val restored = stateStore.load()
        if (restored.queue.isEmpty()) return
        player.setMediaItems(
            restored.queue.map(MediaItemMapper::toMediaItem),
            restored.currentIndex.coerceIn(0, restored.queue.lastIndex),
            restored.positionMs,
        )
        player.setPlaylistMetadata(MediaMetadata.Builder().setTitle(restored.contextTitle).build())
        player.shuffleModeEnabled = restored.shuffle
        player.repeatMode = restored.repeatMode.toPlayerMode()
        player.playWhenReady = restored.playWhenReady
    }

    private fun persistPlayback() {
        if (!::player.isInitialized) return
        val queue = buildList {
            for (index in 0 until player.mediaItemCount) add(
                MediaItemMapper.toTrack(player.getMediaItemAt(index))
            )
        }
        stateStore.save(
            RestoredPlayback(
                queue = queue,
                currentIndex = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode.toRepeatMode(),
                contextTitle = player.playlistMetadata.title?.toString().orEmpty(),
                playWhenReady = player.playWhenReady,
            )
        )
    }

    /**
     * Publishes the bitrate of what is actually playing, for the readout under the scrubber.
     *
     * Prefers the rate measured from the cached file, which is what the bytes on disk really are,
     * over the rate a resolver declared for the stream it opened, which is the encoder's nominal
     * target. Falls to 0 when neither is known, and 0 renders as nothing: the readout is allowed to
     * say what you are hearing or to say nothing, never to guess.
     *
     * Main thread only, since it reads player state.
     */
    private fun publishBitrate() {
        if (!::player.isInitialized || !::streamCache.isInitialized) return
        val item = player.currentMediaItem
        val graph = OrchardGraph.from(this)
        if (item == null) {
            graph.activeBitrate.value = 0
            return
        }
        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val measured = uri?.let { streamCache.cachedBitrateKbps(it, duration) } ?: 0
        graph.activeBitrate.value =
            if (measured > 0) measured else streamResolver.knownBitrateKbps(item.mediaId)
    }

    /**
     * WearOS media controls do not reliably dereference remote artworkUri values. Embed the
     * compressed cover in the session metadata as well, which lets the watch render it directly.
     */
    private fun hydrateCurrentArtwork() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        val artworkUrl = item.mediaMetadata.artworkUri?.toString().orEmpty()
        if (artworkUrl.isBlank() || item.mediaMetadata.artworkData != null || artworkHydrationId == item.mediaId) return
        artworkHydrationId = item.mediaId
        browseScope.launch {
            val bytes = runCatching {
                graphHttp().newCall(Request.Builder().url(artworkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body.bytes().takeIf { it.size <= 2 * 1024 * 1024 }
                }
            }.getOrNull() ?: return@launch
            handler.post {
                if (!::player.isInitialized) return@post
                val index = player.currentMediaItemIndex
                if (index !in 0 until player.mediaItemCount) return@post
                val current = player.getMediaItemAt(index)
                if (current.mediaId != item.mediaId) return@post
                val metadata = current.mediaMetadata.buildUpon()
                    .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
                player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(metadata).build())
            }
        }
    }

    private fun graphHttp(): OkHttpClient = OrchardGraph.from(this).http

    private val playbackListener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                Log.d(
                    TAG,
                    "playbackListener.onEvents: isPlaying=${player.isPlaying}, state=${player.playbackState}, playWhenReady=${player.playWhenReady}, item=${player.currentMediaItem?.mediaId}, count=${player.mediaItemCount}",
                )
                if (
                    events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_PLAYLIST_METADATA_CHANGED,
                    )
                ) {
                    persistPlayback()
                    updateCustomLayout()
                }
                if (
                    events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                    )
                ) {
                    prefetchAround(player)
                    // A transition alone cannot measure anything: the duration
                    // the measurement
                    // divides by is not known until the timeline lands. This is
                    // where it is.
                    publishBitrate()
                    hydrateCurrentArtwork()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled && ::player.isInitialized) shuffleUpcomingItems(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(positionSaver)
                if (isPlaying) handler.postDelayed(positionSaver, POSITION_SAVE_INTERVAL_MS)
                else persistPlayback()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(
                    TAG,
                    "playbackListener.onMediaItemTransition: item=${mediaItem?.mediaId}, reason=$reason",
                )
                retriedMediaId = ""
                artworkHydrationId = ""
                hydrateCurrentArtwork()
                publishBitrate()
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) crossfade.abort()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.d(
                    TAG,
                    "playbackListener.onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reason",
                )
                if (
                    !playWhenReady &&
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                )
                    crossfade.abort()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) crossfade.abort()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    TAG,
                    "playbackListener.onPlayerError: ${error.errorCodeName} - ${error.message}",
                    error,
                )
                val mediaId = player.currentMediaItem?.mediaId.orEmpty()
                if (mediaId.isBlank() || retriedMediaId == mediaId) {
                    Log.e(TAG, "Playback failed after stream refresh", error)
                    return
                }
                retriedMediaId = mediaId
                streamResolver.invalidate(mediaId)
                Log.w(TAG, "Refreshing the failed stream once", error)
                player.prepare()
                player.play()
            }
        }

    private fun shuffleUpcomingItems(targetPlayer: Player) {
        val current = targetPlayer.currentMediaItemIndex
        val total = targetPlayer.mediaItemCount
        val from = (current + 1).coerceAtLeast(0)
        if (from >= total - 1) return
        val upcoming = (from until total).map { targetPlayer.getMediaItemAt(it) }
        val shuffled = FisherYates.shuffle(upcoming)
        targetPlayer.removeMediaItems(from, total)
        targetPlayer.addMediaItems(from, shuffled)
    }

    private val sessionCallback =
        object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootParams =
                    MediaLibraryService.LibraryParams.Builder()
                        .setExtras(OrchardMediaLibrary.rootExtras())
                        .build()
                return Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), rootParams))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?,
            ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
                val future =
                    SettableFuture.create<
                        LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>
                    >()
                browseScope.launch {
                    val result =
                        runCatching { browseTree.children(parentId) }
                            .onFailure { Log.w(TAG, "Browse of $parentId failed", it) }
                            .getOrDefault(emptyList())
                    // A playlist can run to thousands of rows, so hand back
                    // only the window
                    // the browser asked for rather than the whole list every
                    // time.
                    val window = result.drop(page * pageSize).take(pageSize)
                    future.set(
                        LibraryResult.ofItemList(
                            com.google.common.collect.ImmutableList.copyOf(window),
                            params,
                        )
                    )
                }
                return future
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val item = browseTree.item(mediaId)
                return Futures.immediateFuture(
                    if (item == null) {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    } else {
                        LibraryResult.ofItem(item, null)
                    }
                )
            }

            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                // The library commands are what let a browser ask for the root at
                // all:
                // MediaLibraryServiceLegacyStub.onGetRoot hands back null unless
                // the
                // controller holds COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT, and
                // Android Auto
                // then sits on a spinner with no error of its own.
                val sessionCommands =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(COMMAND_TOGGLE_SHUFFLE)
                        .add(COMMAND_TOGGLE_REPEAT)
                        .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .build()
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                Log.d(
                    TAG,
                    "sessionCallback.onSetMediaItems: ${mediaItems.size} items, startIndex=$startIndex, pos=$startPositionMs; rawIds=${mediaItems.map { it.mediaId }}",
                )
                // Assistant does not know any media ids, so a voice request arrives
                // as an
                // item carrying only a search query. Resolving it here is what
                // makes
                // "play something on Orchard" play anything at all.
                val spoken =
                    mediaItems
                        .singleOrNull()
                        ?.takeIf { it.mediaId.isBlank() }
                        ?.requestMetadata
                        ?.searchQuery
                if (spoken != null) {
                    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    browseScope.launch {
                        val found = browseTree.search(spoken)
                        Log.d(
                            TAG,
                            "sessionCallback.onSetMediaItems: search '$spoken' -> ${found.size} tracks",
                        )
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                found.map { resolveMediaItem(it) },
                                0,
                                C.TIME_UNSET,
                            )
                        )
                    }
                    return future
                }

                // A car browser sends back the single row that was tapped.
                // Orchard's own UI
                // always sends the queue it means to play, so only an outside
                // controller gets
                // its selection expanded into the list it was browsing.
                val external = controller.packageName != packageName
                val expanded =
                    if (external && mediaItems.size == 1) {
                        browseTree.queueFor(mediaItems.single().mediaId)
                    } else {
                        null
                    }
                if (expanded != null) {
                    val (queue, index) = expanded
                    Log.d(
                        TAG,
                        "sessionCallback.onSetMediaItems expanded to ${queue.size} items at $index",
                    )
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            queue.map { resolveMediaItem(it) },
                            index,
                            startPositionMs,
                        )
                    )
                }
                val updated = mediaItems.map { resolveMediaItem(it) }
                Log.d(
                    TAG,
                    "sessionCallback.onSetMediaItems resolved: ${updated.map { "${it.mediaId}->${it.localConfiguration?.uri}" }}",
                )
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(updated, startIndex, startPositionMs)
                )
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> {
                Log.d(TAG, "sessionCallback.onAddMediaItems: ${mediaItems.size} items")
                val updated = mediaItems.map { resolveMediaItem(it) }.toMutableList()
                return Futures.immediateFuture(updated)
            }

            private fun resolveMediaItem(request: MediaItem): MediaItem {
                // Browsers hand back a bare media id with no metadata, which would
                // leave the
                // car screen showing an untitled track. Restore the row we served.
                val item =
                    if (request.mediaMetadata.title.isNullOrBlank()) {
                        browseTree.item(request.mediaId) ?: request
                    } else {
                        request
                    }
                val uri =
                    item.localConfiguration?.uri
                        ?: item.requestMetadata.mediaUri
                        ?: if (item.mediaId.isNotBlank()) {
                            android.net.Uri.Builder()
                                .scheme("orchard")
                                .authority("stream")
                                .appendPath(item.mediaId)
                                .build()
                        } else null
                return if (uri != null && item.localConfiguration?.uri == null) {
                    item
                        .buildUpon()
                        .setUri(uri)
                        .setRequestMetadata(
                            item.requestMetadata.buildUpon().setMediaUri(uri).build()
                        )
                        .build()
                } else {
                    item
                }
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    ACTION_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                    ACTION_TOGGLE_REPEAT ->
                        player.repeatMode =
                            when (player.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                }
                updateCustomLayout()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

    private fun updateCustomLayout() {
        if (!::mediaSession.isInitialized || !::player.isInitialized) return
        val shuffleOn = player.shuffleModeEnabled
        val shuffleIcon =
            if (shuffleOn) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        val shuffleRes = if (shuffleOn) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle
        val shuffleButton =
            CommandButton.Builder(shuffleIcon)
                .setCustomIconResId(shuffleRes)
                .setDisplayName(if (shuffleOn) "Shuffle on" else "Shuffle off")
                .setSessionCommand(COMMAND_TOGGLE_SHUFFLE)
                .build()

        val (repeatIcon, repeatRes, repeatTitle) =
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE ->
                    Triple(CommandButton.ICON_REPEAT_ONE, R.drawable.ic_repeat_one_on, "Repeat one")
                Player.REPEAT_MODE_ALL ->
                    Triple(CommandButton.ICON_REPEAT_ALL, R.drawable.ic_repeat_on, "Repeat all")
                else -> Triple(CommandButton.ICON_REPEAT_OFF, R.drawable.ic_repeat, "Repeat off")
            }
        val repeatButton =
            CommandButton.Builder(repeatIcon)
                .setCustomIconResId(repeatRes)
                .setDisplayName(repeatTitle)
                .setSessionCommand(COMMAND_TOGGLE_REPEAT)
                .build()

        mediaSession.setCustomLayout(listOf(shuffleButton, repeatButton))
    }

    private fun prefetchAround(player: Player) {
        val current = player.currentMediaItemIndex
        for (index in current..current + 1) {
            if (index !in 0 until player.mediaItemCount) continue
            streamResolver.prefetch(player.getMediaItemAt(index).mediaId)
        }

        val wanted =
            (current..current + 1)
                .filter { it in 0 until player.mediaItemCount }
                .mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri }
        streamCache.retainOnly(wanted)
        wanted.forEach(streamCache::prefetch)

        if (!smartCrossfadeWanted()) {
            Log.d(TAG, "Smart crossfade is off; not analysing")
            return
        }

        // Nothing started this late can finish in time, and a model pass is not free to
        // lose.
        //
        // Arriving in the last minute of a track means the listener skipped or seeked
        // there:
        // ordinary playback asks for this analysis when the track begins, minutes ahead.
        // Starting
        // one now would spend two decodes and two inferences to answer a question the
        // transition
        // has already passed, while competing for CPU and heap with the playback it is
        // meant to
        // improve. The plain fade is the right answer here, and it costs nothing to reach.
        val remainingSeconds =
            if (player.duration != C.TIME_UNSET) {
                (player.duration - player.currentPosition) / 1000.0
            } else {
                Double.MAX_VALUE
            }
        if (remainingSeconds < MODEL_PASS_MIN_LEAD_SECONDS) {
            Log.d(
                TAG,
                "Only ${remainingSeconds}s left; skipping the model pass for this transition",
            )
            return
        }

        for (index in current..current + 1) {
            if (index !in 0 until player.mediaItemCount) continue
            val item = player.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri ?: continue
            val track = MediaItemMapper.toTrack(item)
            val duration =
                if (index == player.currentMediaItemIndex && player.duration != C.TIME_UNSET) {
                    player.duration / 1000.0
                } else {
                    track.durationMs / 1000.0
                }
            analyzer.request(track, uri, duration)
        }
    }

    /**
     * Asks for the overlap of the upcoming transition to be rendered, well ahead of the seam.
     *
     * Called from the plan callback rather than from the transition itself, because rendering means
     * decoding two stereo regions and running a phase vocoder over one of them: seconds of work
     * that has to be finished before the playhead arrives, not started when it does.
     */
    private fun prepareTransition(plan: dev.sfg.orchard.mobile.playback.smart.TransitionPlan) {
        if (!::preparer.isInitialized || !::player.isInitialized) return
        val current = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val next = player.getMediaItemAt(nextIndex)
        val currentUri = current.localConfiguration?.uri ?: return
        val nextUri = next.localConfiguration?.uri ?: return
        val outgoing = MediaItemMapper.toTrack(current)
        val incoming = MediaItemMapper.toTrack(next)

        preparer.retainOnly(setOf(preparer.key(outgoing, incoming)))
        preparer.prepare(
            outgoing = outgoing,
            outgoingUri = currentUri,
            outgoingAnalysis = analyzer.analysisFor(outgoing),
            incoming = incoming,
            incomingUri = nextUri,
            incomingAnalysis = analyzer.analysisFor(incoming),
            plan = plan,
        )
    }

    /** Analysis costs battery, so it only runs when the listener has actually asked for it. */
    private fun smartCrossfadeWanted(): Boolean {
        val settings = OrchardGraph.from(this).settings.settings.value
        return settings.crossfadeMs > 0 && settings.smartCrossfade
    }

    private fun mainActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun Int.toRepeatMode(): RepeatMode =
        when (this) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

    private fun RepeatMode.toPlayerMode(): Int =
        when (this) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        }

    private fun extractPlaybackErrorMessage(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            val msg = current.message.orEmpty()
            if (msg.startsWith("YouTube could not play this track: ")) {
                val reason = msg.removePrefix("YouTube could not play this track: ").trim()
                return if (reason.isNotBlank()) reason else "This track is unavailable."
            }
            if (msg.contains("inappropriate for some users", ignoreCase = true)) {
                return "This video may be inappropriate for some users."
            }
            if (msg.contains("confirm your age", ignoreCase = true)) {
                return "This track requires age verification on YouTube."
            }
            if (msg.contains("not a bot", ignoreCase = true)) {
                return "YouTube bot check: sign in to play this track."
            }
            if (msg.contains("refused this track", ignoreCase = true)) {
                return "YouTube refused this track."
            }
            if (
                current is java.net.UnknownHostException ||
                    current is java.net.SocketTimeoutException
            ) {
                return "Network error: check your connection."
            }
            current = current.cause
        }
        val fallback = error.cause?.message ?: error.message
        return fallback?.takeIf { it.isNotBlank() } ?: "Playback failed."
    }

    private fun isUnrecoverablePlaybackError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val msg = current.message.orEmpty()
            if (
                msg.contains("inappropriate for some users", ignoreCase = true) ||
                    msg.contains("confirm your age", ignoreCase = true) ||
                    msg.contains("private", ignoreCase = true) ||
                    msg.contains("not available in your country", ignoreCase = true) ||
                    msg.contains("removed", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    companion object {
        private const val TAG = "OrchardPlayback"
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val BUFFER_FOR_PLAYBACK_MS = 500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
        private const val WHOLE_TRACK_BUFFER_MS = 20 * 60 * 1_000
        private const val TARGET_BUFFER_BYTES = 32 * 1024 * 1024
        /**
         * How much track has to be left before a model pass is worth starting.
         *
         * Measured, not guessed: a pass runs 13s to 35s depending on contention, so a minute is the
         * first round number that clears the slow end with room for the render that follows.
         */
        private const val MODEL_PASS_MIN_LEAD_SECONDS = 60.0

        private const val ACTION_TOGGLE_SHUFFLE = "dev.sfg.orchard.ACTION_TOGGLE_SHUFFLE"
        private const val ACTION_TOGGLE_REPEAT = "dev.sfg.orchard.ACTION_TOGGLE_REPEAT"
        private val COMMAND_TOGGLE_SHUFFLE = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
        private val COMMAND_TOGGLE_REPEAT = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)
        private val AUDIO_ATTRIBUTES =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()
    }
}
