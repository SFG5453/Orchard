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

import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** What the target side needs from the phone's player. */
internal interface ConnectPlayer {
    val current: PlaybackSnapshot
    fun play()
    fun pause()
    fun toggle()
    fun next()
    fun previous()
    fun seek(positionMs: Long)
    fun setVolume(volume: Float)
    fun setShuffle(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)
    fun replaceQueue(tracks: List<Track>, startIndex: Int, positionMs: Long, play: Boolean, contextTitle: String)
    fun playNext(track: Track)
    fun addToQueue(track: Track)
    fun remove(index: Int)
    fun move(from: Int, to: Int)
    fun clearUpcoming()
    fun playQueueIndex(index: Int)
}

internal class LocalConnectPlayer(private val local: LocalPlaybackController) : ConnectPlayer {
    val snapshots: StateFlow<PlaybackSnapshot> get() = local.snapshot
    override val current: PlaybackSnapshot get() = local.snapshot.value
    override fun play() = local.play()
    override fun pause() = local.pause()
    override fun toggle() = local.toggle()
    override fun next() = local.next()
    override fun previous() = local.previous()
    override fun seek(positionMs: Long) = local.seek(positionMs)
    override fun setVolume(volume: Float) = local.setVolume(volume)
    override fun setShuffle(enabled: Boolean) = local.setShuffle(enabled)
    override fun setRepeatMode(mode: RepeatMode) = local.setRepeatMode(mode)
    override fun replaceQueue(tracks: List<Track>, startIndex: Int, positionMs: Long, play: Boolean, contextTitle: String) =
        local.replaceQueue(tracks, startIndex, positionMs, play, contextTitle)
    override fun playNext(track: Track) = local.playNext(track)
    override fun addToQueue(track: Track) = local.addToQueue(track)
    override fun remove(index: Int) = local.remove(index)
    override fun move(from: Int, to: Int) = local.move(from, to)
    override fun clearUpcoming() = local.clearUpcoming()
    override fun playQueueIndex(index: Int) = local.playQueueIndex(index)
}

/**
 * This phone as a Connect target. The core has already validated every argument
 * (`normalizeCommand`), so this only maps wire actions onto the player. Queue indices on the
 * wire count upcoming tracks; the player counts its whole queue.
 */
internal object ConnectTarget {
    fun apply(player: ConnectPlayer, action: String, args: JSONObject, contextTitle: String) {
        fun absolute(key: String) = player.current.currentIndex.coerceAtLeast(-1) + 1 + args.optInt(key)
        when (action) {
            "play" -> player.play()
            "pause" -> player.pause()
            "toggle" -> player.toggle()
            "next" -> player.next()
            "previous" -> player.previous()
            "seek" -> player.seek((args.optDouble("position") * 1000).toLong())
            "set_volume" -> player.setVolume(args.optDouble("volume", 1.0).toFloat())
            "set_shuffle" -> player.setShuffle(args.optBoolean("enabled"))
            "set_repeat" -> player.setRepeatMode(ConnectWire.repeat(args.optString("mode")))
            "play_track", "replace_queue" -> {
                val rest = ConnectWire.tracks(args.optJSONArray("tracks"))
                val first = if (action == "play_track") ConnectWire.track(args.optJSONObject("track")) else null
                val tracks = listOfNotNull(first) + rest
                if (tracks.isEmpty()) return
                player.replaceQueue(
                    tracks,
                    if (first != null) 0 else args.optInt("index").coerceIn(0, tracks.lastIndex),
                    (args.optDouble("position") * 1000).toLong(),
                    args.optBoolean("play", true),
                    args.optString("context_title").ifBlank { contextTitle },
                )
            }
            "enqueue" -> ConnectWire.track(args.optJSONObject("track"))?.let {
                if (args.optBoolean("next")) player.playNext(it) else player.addToQueue(it)
            }
            "remove_queue_item" -> player.remove(absolute("index"))
            "move_queue_item" -> player.move(absolute("from"), absolute("to"))
            "clear_queue" -> player.clearUpcoming()
            "play_queue_index" -> player.playQueueIndex(absolute("index"))
        }
    }

    /** The controller was playing and this phone was idle: carry on from where it was. */
    fun transfer(player: ConnectPlayer, snapshot: JSONObject, contextTitle: String) {
        val current = ConnectWire.track(snapshot.optJSONObject("track")) ?: return
        val tracks = listOf(current) + ConnectWire.tracks(snapshot.optJSONArray("queue"))
        player.setShuffle(snapshot.optBoolean("shuffle"))
        player.setRepeatMode(ConnectWire.repeat(snapshot.optString("repeat")))
        player.replaceQueue(
            tracks,
            0,
            (snapshot.optDouble("position") * 1000).toLong(),
            snapshot.optBoolean("playing", true),
            snapshot.optString("context_title").ifBlank { contextTitle },
        )
    }
}
