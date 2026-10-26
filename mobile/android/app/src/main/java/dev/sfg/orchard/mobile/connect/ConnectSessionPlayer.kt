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

package dev.sfg.orchard.mobile.connect

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.sfg.orchard.mobile.artwork.highResolutionArtworkUrl
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackStatus
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.playback.MediaItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The controlled Connect target as a Media3 player, so system media shows and drives it. */
@UnstableApi
internal class ConnectSessionPlayer(
    private val connect: ConnectRepository,
    scope: CoroutineScope,
    private val onMediaChanged: (hasMedia: Boolean) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val handler = Handler(Looper.getMainLooper())
    private var snapshot = PlaybackSnapshot()
    private var receivedAt = SystemClock.elapsedRealtime()
    // Commands resolve on the target's next state, so Media3's placeholder covers the round trip.
    private val pending = mutableListOf<SettableFuture<Unit>>()

    val hasMedia: Boolean get() = snapshot.currentTrack != null

    init {
        scope.launch {
            connect.remote.collect { next ->
                val hadMedia = hasMedia
                snapshot = next
                receivedAt = SystemClock.elapsedRealtime()
                pending.toList().also { pending.clear() }.forEach { it.set(Unit) }
                invalidateState()
                if (hasMedia != hadMedia) onMediaChanged(hasMedia)
            }
        }
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setShuffleModeEnabled(snapshot.shuffle)
            .setRepeatMode(repeatMode(snapshot.repeatMode))
        val current = snapshot.currentTrack ?: return builder.setPlaybackState(STATE_IDLE).build()
        val durationMs = snapshot.durationMs.takeIf { it > 0 } ?: current.durationMs
        val items = snapshot.queue.take(QUEUE_LIMIT).mapIndexed { index, track ->
            val itemDurationMs = if (index == 0) durationMs else track.durationMs
            MediaItemData.Builder("$index:${track.id}")
                .setMediaItem(MediaItemMapper.toMediaItem(track.copy(artworkUrl = highResolutionArtworkUrl(track.artworkUrl, 1024))))
                .setDurationUs(if (itemDurationMs > 0) itemDurationMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val buffering = snapshot.status == PlaybackStatus.BUFFERING || snapshot.status == PlaybackStatus.LOADING
        val base = snapshot.positionMs
        val since = receivedAt
        val playing = snapshot.isPlaying
        return builder
            .setPlaylist(items)
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (buffering) STATE_BUFFERING else STATE_READY)
            .setPlayWhenReady(playing || buffering, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(PositionSupplier {
                val position = base + if (playing) SystemClock.elapsedRealtime() - since else 0
                if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
            })
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = send(if (playWhenReady) "play" else "pause")

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when {
        seekCommand == COMMAND_SEEK_TO_NEXT || seekCommand == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> send("next")
        // The target decides between restarting and going back, like its own previous button.
        seekCommand == COMMAND_SEEK_TO_PREVIOUS || seekCommand == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> send("previous")
        mediaItemIndex > 0 -> {
            connect.queueCommand("play_queue_index", "index" to mediaItemIndex)
            awaitRemote()
        }
        else -> send("seek", JSONObject().put("position", (if (positionMs == C.TIME_UNSET) 0 else positionMs) / 1000.0))
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean) =
        send("set_shuffle", JSONObject().put("enabled", shuffleModeEnabled))

    override fun handleSetRepeatMode(repeatMode: Int) = send(
        "set_repeat",
        JSONObject().put("mode", ConnectWire.repeat(
            when (repeatMode) {
                REPEAT_MODE_ONE -> RepeatMode.ONE
                REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
        )),
    )

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        return Futures.immediateVoidFuture()
    }

    private fun send(action: String, args: JSONObject = JSONObject()): ListenableFuture<*> {
        connect.command(action, args)
        return awaitRemote()
    }

    private fun awaitRemote(): ListenableFuture<Unit> {
        val future = SettableFuture.create<Unit>()
        pending += future
        // A command that changes nothing never produces new state; drop the placeholder anyway.
        handler.postDelayed({ if (pending.remove(future)) future.set(Unit) }, PENDING_TIMEOUT_MS)
        return future
    }

    private fun repeatMode(mode: RepeatMode) = when (mode) {
        RepeatMode.ONE -> REPEAT_MODE_ONE
        RepeatMode.ALL -> REPEAT_MODE_ALL
        RepeatMode.OFF -> REPEAT_MODE_OFF
    }

    private companion object {
        const val QUEUE_LIMIT = 50
        const val PENDING_TIMEOUT_MS = 3_000L
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_PREPARE,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_TO_DEFAULT_POSITION,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SET_SHUFFLE_MODE,
            COMMAND_SET_REPEAT_MODE,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
        ).build()
    }
}
