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
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dev.sfg.orchard.connect.R
import dev.sfg.orchard.connect.app.MainActivity
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.smart.CrossfadeMode
import dev.sfg.orchard.mobile.playback.smart.MixSplicer
import dev.sfg.orchard.mobile.playback.smart.TransitionFilter
import dev.sfg.orchard.mobile.widget.OrchardWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-level owner of local phone playback.
 *
 * UI components connect through Media3 controllers and can disappear without interrupting playback.
 * ExoPlayer owns audio focus, noisy-output handling, Bluetooth/headset media buttons, automatic
 * queue progression, and buffering.
 */
@UnstableApi
class OrchardPlaybackService : MediaLibraryService() {
    // Crossfade needs two decoders overlapping, so playback runs on a pair of players and
    // `player` is whichever one currently owns the session and the queue.
    private lateinit var player: ExoPlayer
    private lateinit var spare: ExoPlayer
    private lateinit var crossfade: CrossfadeEngine
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var streamResolver: YouTubeStreamResolver
    private lateinit var streamCache: StreamCache
    private lateinit var streams: PlayerStreams
    private lateinit var recovery: PlaybackRecovery
    private lateinit var nowPlaying: NowPlayingPublisher
    private lateinit var chromecastStreamServer: ChromecastStreamServer
    private lateinit var chromecastPlayback: ChromecastPlayback
    // One filter per player, inserted in each one's audio pipeline. They follow the players
    // through a handoff, so a filter never ends up automating the wrong track.
    private lateinit var playerFilter: TransitionFilter
    private lateinit var spareFilter: TransitionFilter
    private lateinit var playbackVolume: PlaybackVolumeMonitor
    private lateinit var slop: dev.sfg.orchard.mobile.playback.slop.SlopPlayback
    // Adaptive mixes and standard fades run at the head of each pipeline; a splicer stays with its player.
    private val splicers = java.util.IdentityHashMap<ExoPlayer, MixSplicer>()
    /** Queue order as it was when shuffle went on, so turning it off can put the queue back. */
    private var unshuffledOrder: List<String> = emptyList()

    /** Tracks near the current item by media id; written on the main thread, read by loader threads. */
    @Volatile private var queueSnapshot: Map<String, Track> = emptyMap()
    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadedAudioVariant = ""
    private val positionSaver = object : Runnable {
        override fun run() {
            persistPlayback()
            val source = authoritativePlayer()
            if (source != null) {
                OrchardWidgetUpdater.onPlayerChanged(this@OrchardPlaybackService, source)
                nowPlaying.updateScrobbling(source)
            }
            if (source?.isPlaying == true) handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(this)
        val graph = OrchardGraph.from(this)
        val browseTree = OrchardMediaLibrary(graph)
        streamResolver = graph.streams
        streamResolver.trackLookup = ::queueTrack
        streamResolver.warmUp()
        streamCache = StreamCache(context = this, maxBytes = graph.settings.settings.value.cacheSizeBytes, variant = graph::streamVariant)
        graph.onClearStreamCache = streamCache::clear
        streams = PlayerStreams(this, graph, streamCache, ::queueTrack) { uri, stream -> nowPlaying.onResolved(uri, stream) }
        nowPlaying = NowPlayingPublisher(graph, handler, ioScope, streamCache, streams::cached) {
            if (::player.isInitialized) player else null
        }
        recovery = PlaybackRecovery(streamResolver, streams, graph::postWarning)
        // Caching finishes seconds after the player events that asked for it, so completion has
        // to re-drive prefetch itself, on the main thread because that reads player state.
        streamCache.onCached = {
            handler.post {
                if (::player.isInitialized) {
                    prefetchAround(player)
                    // The current track's true bitrate becomes measurable here, and no player event marks it.
                    nowPlaying.publishBitrate()
                }
            }
        }
        playerFilter = TransitionFilter()
        spareFilter = TransitionFilter()
        val playerEq = EqualizerAudioProcessor()
        val spareEq = EqualizerAudioProcessor()
        playbackVolume = PlaybackVolumeMonitor(this)
        observeAudioSettings(graph, ioScope, playbackVolume.state, listOf(playerFilter, spareFilter), listOf(playerEq, spareEq))
        player = buildPlayer(playerFilter, playerEq, handlesAudioFocus = true)
        spare = buildPlayer(spareFilter, spareEq, handlesAudioFocus = false)
        unshuffledOrder = stateStore.load().also { it.restoreInto(player) }.unshuffledOrder
        // The restored song is known before anything can ask for it. No more amnesiac cold starts.
        snapshotQueue(player)
        val callback = OrchardSessionCallback(
            packageName = packageName,
            browseTree = browseTree,
            stateStore = stateStore,
            browseScope = ioScope,
            target = { authoritativePlayer() ?: player },
            onSetVideoMode = ::setVideoMode,
            onLayoutChanged = ::updateCustomLayout,
        )
        mediaSession = MediaLibrarySession.Builder(this, OrchardSessionPlayer(player), callback)
            .setSessionActivity(mainActivityIntent())
            // The app's OkHttp stack: the default URLConnection loader fails on some provider
            // artwork URLs, and WearOS then falls back to a gray notification background.
            .setBitmapLoader(
                DataSourceBitmapLoader.Builder(this)
                    .setDataSourceFactory(OkHttpDataSource.Factory(graph.http))
                    .setMaximumOutputDimension(512)
                    .setMakeShared(true)
                    .build()
            )
            .build()
        updateCustomLayout()
        handler.post { nowPlaying.hydrateArtwork() }
        // Media3's default small icon is a play glyph, which the media player then badges.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) }
        )
        crossfade = CrossfadeEngine(
            handler = handler,
            config = {
                val settings = graph.settings.settings.value
                CrossfadeEngine.Config(
                    enabled = settings.crossfadeMs > 0,
                    fadeSeconds = settings.crossfadeMs / 1000.0,
                    mode = if (settings.smartCrossfade && !graph.maxActive.value) CrossfadeMode.SMART else CrossfadeMode.STANDARD,
                )
            },
            // A connected desktop does the heavy lifting; the phone keeps the clock and the speaker.
            mixer = AdaptiveMixer(this, handler, remote = graph.connect::mixHost),
            // Adaptive mixes decode whole songs, so both must be fully cached first.
            sourceFor = { uri -> if (streamCache.isFullyCached(uri)) ({ streamCache.mediaDataSource(uri) }) else null },
            splicerFor = splicers::get,
            onMarker = { graph.transitionMarker.value = it },
            onHandoff = ::adoptPlayer,
            canCrossfadeTo = { dev.sfg.orchard.mobile.playback.slop.SlopPlayback.allowsTransition(graph, it) },
        )
        crossfade.start(player, spare)
        loadedAudioVariant = graph.streamVariant()
        player.addListener(playbackListener)
        chromecastStreamServer = ChromecastStreamServer(this, graph.http, streamResolver)
        chromecastPlayback = ChromecastPlayback(
            context = this,
            converter = ChromecastMediaItemConverter(chromecastStreamServer),
            localPlayer = { player },
            onCastStarted = { castPlayer ->
                crossfade.abort()
                castPlayer.addListener(castPlaybackListener)
                mediaSession.player = OrchardSessionPlayer(castPlayer)
                if (::slop.isInitialized) slop.refresh()
                persistPlayback()
                updateCustomLayout()
                OrchardWidgetUpdater.onPlayerChanged(this, castPlayer)
            },
            onCastEnded = { localPlayer ->
                chromecastPlayback.player.removeListener(castPlaybackListener)
                mediaSession.player = OrchardSessionPlayer(localPlayer)
                if (::slop.isInitialized) slop.refresh()
                crossfade.start(player, spare)
                refreshAudioVariant()
                persistPlayback()
                updateCustomLayout()
                OrchardWidgetUpdater.onPlayerChanged(this, localPlayer)
            },
            onError = graph::postWarning,
        )
        chromecastPlayback.start()
        slop = dev.sfg.orchard.mobile.playback.slop.SlopPlayback(this, graph, ioScope, ::authoritativePlayer, crossfade::abort)
        observeAudioVariants(graph, ioScope, ::refreshAudioVariant)
        OrchardWidgetUpdater.onPlayerChanged(this, player)
    }
    private fun refreshAudioVariant() {
        val next = OrchardGraph.from(this).streamVariant()
        if (next == loadedAudioVariant || authoritativePlayer() !== player) return
        loadedAudioVariant = next
        reloadAudioForVariant(player, crossfade, streamCache, streams, ::prefetchAround)
    }
    private fun buildPlayer(filter: TransitionFilter, eq: EqualizerAudioProcessor, handlesAudioFocus: Boolean): ExoPlayer {
        val splicer = MixSplicer()
        return buildOrchardPlayer(this, streams.mediaSourceFactory(), splicer, filter, eq, playbackVolume.state, handlesAudioFocus)
            .also { splicers[it] = splicer }
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
        OrchardWidgetUpdater.onPlayerChanged(this, incoming)
        updateCustomLayout()
        prefetchAround(incoming)
        if (::slop.isInitialized) slop.refresh()
    }

    /** Change the decoder source as one service-side operation, including the crossfade deck. */
    private fun setVideoMode(expectedTrackId: String, videoId: String, maxHeight: Int?) {
        if (authoritativePlayer() !== player) return
        player.switchVideoSource(expectedTrackId, videoId, maxHeight, beforeSwap = crossfade::abort)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val target = authoritativePlayer() ?: return result
        target.runWidgetAction(intent)
        return result
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN is every screen-off listen; only real pressure should cost the next mix a reload.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && ::crossfade.isInitialized) {
            crossfade.trimMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Modern Android reports critical pressure here; the old RUNNING_* levels retired.
        if (::crossfade.isInitialized) crossfade.trimMemory()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::nowPlaying.isInitialized) nowPlaying.finishHistory()
        val finalSource = authoritativePlayer()
        if (finalSource != null) {
            persistPlayback(sync = true)
            OrchardWidgetUpdater.onPlayerChanged(this, finalSource, forcePaused = true)
        }
        if (::slop.isInitialized) slop.close()
        if (::crossfade.isInitialized) crossfade.release()
        if (::chromecastPlayback.isInitialized) chromecastPlayback.close()
        if (::chromecastStreamServer.isInitialized) chromecastStreamServer.close()
        if (::player.isInitialized) player.release()
        ioScope.cancel()
        if (::spare.isInitialized) spare.release()
        if (::playbackVolume.isInitialized) playbackVolume.close()
        if (::mediaSession.isInitialized) mediaSession.release()
        streamResolver.trackLookup = { null }
        queueSnapshot = emptyMap()
        OrchardGraph.from(this).onClearStreamCache = null
        if (::streamCache.isInitialized) streamCache.release()
        OrchardGraph.from(this).qobuzResolver.release()
        if (::streams.isInitialized) streams.clear()
        super.onDestroy()
    }

    /**
     * Only one player may handle audio focus at a time. Two focus-handling players in the same
     * process fight: the standby's `play()` takes focus during a crossfade and ExoPlayer pauses the
     * player that lost it, cutting the outgoing track instead of fading it. Focus follows the
     * session, so it is handed over with it.
     */
    private fun setFocusOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(ORCHARD_AUDIO_ATTRIBUTES, owns)
    }

    private fun persistPlayback(sync: Boolean = false) {
        val restored = savedPlayback(authoritativePlayer() ?: return, unshuffledOrder)
        if (sync) stateStore.save(restored) else ioScope.launch { stateStore.save(restored) }
    }

    private fun authoritativePlayer(): Player? = when {
        ::chromecastPlayback.isInitialized && chromecastPlayback.isActive -> chromecastPlayback.player
        ::player.isInitialized -> player
        else -> null
    }

    /**
     * Queue metadata for the resolvers, which run on loader threads. Player state lives on the
     * main thread; the timeout keeps a busy main thread from stalling a load forever.
     */
    private fun queueTrack(videoId: String): Track? {
        // The snapshot answers without touching the main thread, which is the point: on a cold
        // start the main thread is still busy in onCreate, the hop below times out, and the
        // resolver would match album audio from a blank title and cache the wrong recording.
        queueSnapshot[videoId]?.let { return it }
        val find = {
            if (!::player.isInitialized) null
            else (0 until player.mediaItemCount).asSequence().map(player::getMediaItemAt)
                .firstOrNull { it.mediaId == videoId }?.let(MediaItemMapper::toTrack)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return find()
        return runBlocking { withTimeoutOrNull(QUEUE_LOOKUP_TIMEOUT_MS) { withContext(Dispatchers.Main) { find() } } }
    }

    /**
     * Copies the tracks around the current item into [queueSnapshot], on the main thread. Only a
     * window is kept so a 2,500-song queue is not re-parsed on every timeline change; anything
     * outside it still falls back to the main-thread scan in [queueTrack].
     */
    private fun snapshotQueue(source: Player) {
        val from = (source.currentMediaItemIndex - 1).coerceAtLeast(0)
        val to = (source.currentMediaItemIndex + SNAPSHOT_AHEAD).coerceAtMost(source.mediaItemCount - 1)
        queueSnapshot = (from..to).associate { index ->
            val item = source.getMediaItemAt(index)
            item.mediaId to MediaItemMapper.toTrack(item)
        }
        if (::slop.isInitialized) slop.refresh()
    }

    /** Cast does not use Orchard's local resolver/crossfade recovery, only shared state updates. */
    private val castPlaybackListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (::slop.isInitialized) slop.refresh()
            if (events.containsAny(*STATE_EVENTS)) {
                persistPlayback()
                updateCustomLayout()
            }
            OrchardWidgetUpdater.onPlayerChanged(this@OrchardPlaybackService, player)
            nowPlaying.updateScrobbling(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = savePositionWhilePlaying(isPlaying)
    }

    private val playbackListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            Log.d(TAG, "onEvents: isPlaying=${player.isPlaying}, state=${player.playbackState}, " +
                "playWhenReady=${player.playWhenReady}, item=${player.currentMediaItem?.mediaId}, count=${player.mediaItemCount}")
            if (events.containsAny(*STATE_EVENTS)) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) this@OrchardPlaybackService.player.keepQueueOrderUnshuffled()
                persistPlayback()
                if (events.containsAny(
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                    )
                ) updateCustomLayout()
            }
            // The codec is only known once the decoder reports its format.
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) nowPlaying.publishBitrate()
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                // Before the prefetch below, which resolves on another thread and reads this.
                snapshotQueue(player)
                prefetchAround(player)
                // The duration a bitrate measurement divides by lands with the timeline, not the transition.
                nowPlaying.publishBitrate()
                nowPlaying.hydrateArtwork()
            }
            OrchardWidgetUpdater.onPlayerChanged(this@OrchardPlaybackService, this@OrchardPlaybackService.player)
            nowPlaying.updateScrobbling(player)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled) {
                // Taken before the shuffle, because after it there is nothing left to remember.
                unshuffledOrder = player.queueMediaIds()
                player.shuffleUpcoming()
            } else {
                player.restoreUpcoming(unshuffledOrder)
                unshuffledOrder = emptyList()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = savePositionWhilePlaying(isPlaying)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(TAG, "onMediaItemTransition: item=${mediaItem?.mediaId}, reason=$reason")
            recovery.onMediaItemTransition()
            // This marker belonged to the item just left, even when Media3 advanced on its own.
            OrchardGraph.from(this@OrchardPlaybackService).transitionMarker.value = null
            nowPlaying.resetArtwork()
            nowPlaying.hydrateArtwork()
            nowPlaying.publishBitrate()
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) crossfade.abort()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            Log.d(TAG, "onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reason")
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) crossfade.abort()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) crossfade.abort()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.errorCodeName} - ${error.message}", error)
            recovery.recover(player, error)
        }
    }

    private fun savePositionWhilePlaying(isPlaying: Boolean) {
        handler.removeCallbacks(positionSaver)
        if (isPlaying) handler.postDelayed(positionSaver, POSITION_SAVE_INTERVAL_MS) else persistPlayback()
    }

    private fun updateCustomLayout() {
        if (!::mediaSession.isInitialized) return
        OrchardSessionCallback.applyLayout(mediaSession, authoritativePlayer() ?: return)
    }

    private fun prefetchAround(player: Player) {
        val wanted = (player.currentMediaItemIndex..player.currentMediaItemIndex + 1)
            .filter { it in 0 until player.mediaItemCount }
            .map(player::getMediaItemAt)
        for (item in wanted) {
            val uri = item.localConfiguration?.uri
            // Authenticated progressive items are prefetched through StreamCache below, whose
            // DataSource invokes their signed-in resolver; a guest resolve beside it would compete.
            val authenticated = uri != null &&
                (MediaItemMapper.requiresAuthenticatedHls(uri) || MediaItemMapper.requiresAuthenticatedDirect(uri))
            if (!authenticated && !MediaItemMapper.isVideoUri(uri)) streamResolver.prefetch(item.mediaId)
        }
        val uris = wanted.mapNotNull { it.localConfiguration?.uri }.filterNot(MediaItemMapper::isVideoUri)
        streamCache.retainOnly(uris)
        uris.forEach(streamCache::prefetch)
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "OrchardPlayback"
        private const val QUEUE_LOOKUP_TIMEOUT_MS = 2_000L
        private const val SNAPSHOT_AHEAD = 3
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private val STATE_EVENTS = intArrayOf(
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            Player.EVENT_REPEAT_MODE_CHANGED,
            Player.EVENT_PLAYLIST_METADATA_CHANGED,
        )
    }
}
