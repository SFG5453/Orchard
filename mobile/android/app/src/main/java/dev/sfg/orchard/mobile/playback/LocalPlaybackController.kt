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

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackStatus
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-side Media3 controller; the service remains the authoritative local player. */
class LocalPlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot(status = PlaybackStatus.LOADING))
    val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private val pendingActions = ArrayDeque<(MediaController) -> Unit>()

    /**
     * The failure the player last reported, and the item it happened on.
     *
     * A failure arrives as an error callback immediately followed by an ordinary event
     * for the same stoppage. That event carries no error, so a snapshot rebuilt from it
     * alone erases the message roughly a millisecond after it appears and leaves the UI
     * looking merely idle. Holding the failure here keeps it on screen until playback is
     * actually retried or moves to another track.
     */
    private var lastError: String = ""
    private var lastErrorItemId: String? = null

    init {
        connect()
    }

    fun replaceQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        positionMs: Long = 0,
        play: Boolean = true,
        contextTitle: String = "",
    ) {
        Log.d(TAG, "replaceQueue: count=${tracks.size}, startIndex=$startIndex, play=$play, contextTitle='$contextTitle'")
        val edited = QueueEditor.replaceAndPlay(tracks, startIndex)
        if (edited.tracks.isEmpty()) {
            Log.w(TAG, "replaceQueue: QueueEditor returned empty tracks")
            return
        }
        withController {
            Log.d(TAG, "replaceQueue: dispatching setMediaItems with ${edited.tracks.size} items to MediaController")
            it.setPlaylistMetadata(MediaMetadata.Builder().setTitle(contextTitle).build())
            it.setMediaItems(edited.tracks.map(MediaItemMapper::toMediaItem), edited.currentIndex, positionMs)
            clearError()
            it.prepare()
            if (play) it.play()
        }
    }

    /**
     * Swaps one queued item in place, used when a track's album-audio version resolves after the
     * queue is already playing. Skips the current item so playback is never restarted.
     */
    fun replaceQueued(index: Int, expectedId: String, track: Track) = withController { player ->
        if (index == player.currentMediaItemIndex || index !in 0 until player.mediaItemCount) return@withController
        if (player.getMediaItemAt(index).mediaId != expectedId) return@withController
        player.replaceMediaItem(index, MediaItemMapper.toMediaItem(track))
    }

    fun playNext(track: Track) = withController { player ->
        val index = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItem(index, MediaItemMapper.toMediaItem(track))
    }

    fun addToQueue(track: Track) = withController { it.addMediaItem(MediaItemMapper.toMediaItem(track)) }
    fun remove(index: Int) = withController { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController {
        if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to)
    }
    fun clearUpcoming() = withController {
        val from = it.currentMediaItemIndex + 1
        if (from in 0 until it.mediaItemCount) it.removeMediaItems(from, it.mediaItemCount)
    }
    fun playQueueIndex(index: Int) = withController {
        if (index in 0 until it.mediaItemCount) { it.seekToDefaultPosition(index); it.play() }
    }
    fun toggle() = withController {
        if (it.isPlaying) it.pause() else {
            if (it.playbackState == Player.STATE_IDLE) { clearError(); it.prepare() }
            it.play()
        }
    }
    fun play() = withController {
        if (it.playbackState == Player.STATE_IDLE) { clearError(); it.prepare() }
        it.play()
    }
    fun pause() = withController(Player::pause)
    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = withController {
        if (it.currentPosition > 5_000) it.seekTo(0) else if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem()
    }
    fun seek(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }
    fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }
    fun replaceUpcoming(tracks: List<Track>) = withController { player ->
        val current = player.currentMediaItemIndex
        val total = player.mediaItemCount
        val from = (current + 1).coerceAtLeast(0)
        // from == total means nothing follows the current track. That is not a no-op case: Autoplay
        // refills exactly then, and the removal below is empty while the insert still appends.
        if (from > total) return@withController
        // Callers compute this tail from a snapshot, and the player may have advanced since: a
        // track that was upcoming when the list was built can be the current one by the time it
        // arrives. Writing it back unfiltered queues that track directly behind itself.
        val played = (0..current.coerceAtMost(total - 1))
            .mapTo(mutableSetOf()) { player.getMediaItemAt(it).mediaId }
        val newMediaItems = tracks
            .filter { it.id.isNotBlank() && it.id !in played }
            .distinctBy(Track::id)
            .map(MediaItemMapper::toMediaItem)
        player.removeMediaItems(from, total)
        player.addMediaItems(from, newMediaItems)
    }

    /**
     * Appends tracks to the end of the queue, keeping it within [totalLimit] upcoming items.
     *
     * Autoplay refills from a request that was in the air while playback carried on, so it must not
     * rewrite the tail it last saw — by the time the recommendations land, the queue underneath has
     * moved. Appending against the player's live state instead of a snapshot is what keeps a refill
     * from reinserting whatever became current while it was waiting.
     */
    fun appendUpcoming(tracks: List<Track>, totalLimit: Int) = withController { player ->
        val known = (0 until player.mediaItemCount)
            .mapTo(mutableSetOf()) { player.getMediaItemAt(it).mediaId }
        val upcoming = player.mediaItemCount - player.currentMediaItemIndex - 1
        val room = (totalLimit - upcoming).coerceAtLeast(0)
        if (room == 0) return@withController
        val additions = tracks
            .filter { it.id.isNotBlank() && known.add(it.id) }
            .take(room)
            .map(MediaItemMapper::toMediaItem)
        if (additions.isEmpty()) return@withController
        player.addMediaItems(additions)
    }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun close() {
        progressJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        controllerFuture?.cancel(true)
        pendingActions.clear()
    }

    private fun connect() {
        Log.d(TAG, "connect: initializing MediaController...")
        val token = SessionToken(context, ComponentName(context, OrchardPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { connected ->
                    Log.d(TAG, "connect: MediaController connected successfully")
                    controller = connected
                    connected.addListener(listener)
                    while (pendingActions.isNotEmpty()) pendingActions.removeFirst()(connected)
                    publish(connected)
                }.onFailure {
                    Log.e(TAG, "connect: MediaController failed to connect", it)
                    mutableSnapshot.value = PlaybackSnapshot(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "Local playback service is unavailable.",
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun withController(action: (MediaController) -> Unit) {
        val connected = controller
        if (connected != null) {
            action(connected)
        } else {
            Log.d(TAG, "withController: controller is null, pending action queued (current queue=${pendingActions.size})")
            if (pendingActions.size < 20) pendingActions.addLast(action)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            Log.d(TAG, "listener.onEvents: isPlaying=${player.isPlaying}, state=${player.playbackState}, currentItem=${player.currentMediaItem?.mediaId}")
            publish(player)
        }
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "listener.onPlayerError: ${error.errorCodeName} - ${error.message}", error)
            lastError = playbackErrorMessage(error)
            lastErrorItemId = controller?.currentMediaItem?.mediaId
            publish(controller, lastError)
        }
    }

    private fun clearError() {
        lastError = ""
        lastErrorItemId = null
    }

    private fun publish(player: Player?, explicitError: String = "") {
        if (player == null) return
        val queue = buildList {
            for (index in 0 until player.mediaItemCount) add(MediaItemMapper.toTrack(player.getMediaItemAt(index)))
        }
        val current = player.currentMediaItem?.let(MediaItemMapper::toTrack)
        // A held failure belongs to the item it happened on. Moving to another track, or
        // this one becoming playable, means it no longer describes anything.
        if (lastError.isNotBlank() &&
            (player.currentMediaItem?.mediaId != lastErrorItemId || player.playbackState == Player.STATE_READY)
        ) {
            clearError()
        }
        val error = explicitError.ifBlank { lastError }
        // A restored queue is not prepared until playback starts, so the player reports no
        // duration. The catalog already knows it, which keeps the scrubber honest until then.
        val duration = player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0)
            ?: current?.durationMs?.coerceAtLeast(0)
            ?: 0
        mutableSnapshot.value = PlaybackSnapshot(
            status = when {
                error.isNotBlank() -> PlaybackStatus.ERROR
                player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                player.isPlaying -> PlaybackStatus.PLAYING
                player.playbackState == Player.STATE_READY -> PlaybackStatus.PAUSED
                player.playbackState == Player.STATE_ENDED -> PlaybackStatus.ENDED
                else -> PlaybackStatus.IDLE
            },
            currentTrack = current,
            queue = queue,
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
            isPlaying = player.isPlaying,
            shuffle = player.shuffleModeEnabled,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
            contextTitle = player.playlistMetadata.title?.toString().orEmpty(),
            errorMessage = error,
        )
        syncProgress(player.isPlaying)
    }

    private fun syncProgress(playing: Boolean) {
        if (!playing) {
            progressJob?.cancel()
            progressJob = null
            return
        }
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (true) {
                delay(500)
                controller?.let(::publish)
            }
        }
    }

    private companion object {
        const val TAG = "LocalPlaybackController"
    }
}
