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
import dev.sfg.orchard.mobile.connect.ConnectWire
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.ListeningPartyManager
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Transport and queue commands, routed to this phone, a Connect device, or the party host. */
internal class Transport(
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val local: LocalPlaybackController,
    private val party: ListeningPartyManager,
    private val playback: StateFlow<PlaybackSnapshot>,
    private val targets: StateFlow<PlaybackTargetState>,
    private val musicVideo: StateFlow<MusicVideoState>,
    private val showWarning: (String) -> Unit,
) {
    private val connect = graph.connect

    private fun enqueue(track: Track, next: Boolean) =
        connect.command("enqueue", JSONObject().put("track", ConnectWire.track(track)).put("next", next))

    fun playNext(track: Track) {
        remoteOrLocal(
            { enqueue(track, next = true) },
            { scope.launch { local.playNext(track) } },
        )
    }

    fun addToQueue(track: Track) {
        remoteOrLocal(
            { enqueue(track, next = false) },
            { scope.launch { local.addToQueue(track) } },
        )
    }

    fun togglePlayback() = partyOrLocal(
        if (playback.value.isPlaying) "pause" else "play",
        { connect.command("toggle") },
        local::toggle,
    )

    fun toggleMusicVideo() {
        if (targets.value.selected !is PlaybackTarget.LocalPhone) {
            showWarning("Music videos play on this device only.")
            return
        }
        val state = musicVideo.value
        val currentId = local.snapshot.value.currentTrack?.id
        if (state.trackId != currentId) return
        when {
            state.playing -> local.setVideoMode(null)
            state.videoId.isNotBlank() -> local.setVideoMode(state.videoId, videoMaxHeight())
            state.checking -> Unit
            else -> showWarning("No music video is available for this track.")
        }
    }

    /** Turns the video on once the lookup for [trackId] settles, unless another track took over. */
    fun showMusicVideoWhenReady(trackId: String) {
        if (targets.value.selected !is PlaybackTarget.LocalPhone) return
        scope.launch {
            val state = withTimeoutOrNull(VIDEO_LOOKUP_TIMEOUT_MS) {
                musicVideo.first { it.trackId == trackId && !it.checking }
            } ?: return@launch
            if (state.available && !state.playing && local.snapshot.value.currentTrack?.id == trackId)
                local.setVideoMode(state.videoId, videoMaxHeight())
        }
    }

    /** Saves the picture height and reloads a playing video at it, keeping the playhead. */
    fun setVideoMaxHeight(height: Int) {
        val settings = graph.settings.settings.value
        if (settings.videoMaxHeight != height) graph.settings.updateSettings(settings.copy(videoMaxHeight = height))
        val state = musicVideo.value
        if (state.playing && state.videoId.isNotBlank()) local.setVideoMode(state.videoId, height)
    }

    private fun videoMaxHeight() = graph.settings.settings.value.videoMaxHeight

    fun next() = partyOrLocal("next", { connect.command("next") }, local::next)
    fun previous() = partyOrLocal("previous", { connect.command("previous") }, local::previous)
    fun seek(positionMs: Long) {
        if (party.requestSeek(positionMs)) return
        remoteOrLocal(
            { connect.command("seek", JSONObject().put("position", positionMs / 1_000.0)) },
            { local.seek(positionMs) },
        )
    }
    fun toggleShuffle() = partyOrLocal(
        "toggle-shuffle",
        { connect.command("set_shuffle", JSONObject().put("enabled", !playback.value.shuffle)) },
        {
            // The service reshuffles the upcoming items itself when the flag turns on, so doing it
            // here too would rewrite the queue twice for one toggle.
            local.setShuffle(!playback.value.shuffle)
        },
    )
    fun cycleRepeat() = remoteOrLocal(
        {
            val next = when (playback.value.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            connect.command("set_repeat", JSONObject().put("mode", ConnectWire.repeat(next)))
        },
        local::cycleRepeat,
    )
    // Indices below are absolute positions in the shown queue; Connect counts upcoming tracks.
    fun playQueueIndex(index: Int) = remoteOrLocal(
        { connect.queueCommand("play_queue_index", "index" to index) },
        { local.playQueueIndex(index) },
    )
    fun removeQueueIndex(index: Int) = remoteOrLocal(
        { connect.queueCommand("remove_queue_item", "index" to index) },
        { local.remove(index) },
    )
    fun moveQueueItem(from: Int, to: Int) = remoteOrLocal(
        { connect.queueCommand("move_queue_item", "from" to from, "to" to to) },
        { local.move(from, to) },
    )
    fun clearUpcoming() = remoteOrLocal(
        { connect.command("clear_queue") },
        local::clearUpcoming,
    )
    fun clearQueue() = partyOrLocal(
        "clear-queue",
        { connect.command("clear_queue") },
        local::clearQueue,
    )

    fun remoteOrLocal(remote: () -> Unit, localAction: () -> Unit) {
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
}

/** Covers queue start plus the video lookup on a slow network. */
private const val VIDEO_LOOKUP_TIMEOUT_MS = 20_000L
