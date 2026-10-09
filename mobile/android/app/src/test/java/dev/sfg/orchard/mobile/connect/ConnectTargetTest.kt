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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectTargetTest {
    private class FakePlayer(override val current: PlaybackSnapshot) : ConnectPlayer {
        val calls = mutableListOf<String>()
        var queued: List<Track> = emptyList()
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun toggle() { calls += "toggle" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override fun setVolume(volume: Float) { calls += "volume $volume" }
        override fun setShuffle(enabled: Boolean) { calls += "shuffle $enabled" }
        override fun setRepeatMode(mode: RepeatMode) { calls += "repeat $mode" }
        override fun replaceQueue(tracks: List<Track>, startIndex: Int, positionMs: Long, play: Boolean, contextTitle: String) {
            queued = tracks
            calls += "replace $startIndex $positionMs $play $contextTitle"
        }
        override fun playNext(track: Track) { calls += "next-up ${track.id}" }
        override fun addToQueue(track: Track) { calls += "append ${track.id}" }
        override fun remove(index: Int) { calls += "remove $index" }
        override fun move(from: Int, to: Int) { calls += "move $from $to" }
        override fun clearUpcoming() { calls += "clear" }
        override fun playQueueIndex(index: Int) { calls += "jump $index" }
    }

    private fun track(id: String) = Track(id = id, title = id, artist = "A")

    // Two played songs, the current one at index 2, two upcoming.
    private val player = FakePlayer(
        PlaybackSnapshot(queue = listOf("a", "b", "c", "d", "e").map(::track), currentIndex = 2, currentTrack = track("c")),
    )

    private fun apply(action: String, args: JSONObject = JSONObject()) =
        ConnectTarget.apply(player, action, args, contextTitle = "Desktop")

    @Test
    fun transportCommands() {
        apply("pause")
        apply("seek", JSONObject().put("position", 122.5))
        apply("set_volume", JSONObject().put("volume", 0.25))
        apply("set_repeat", JSONObject().put("mode", "all"))
        apply("set_shuffle", JSONObject().put("enabled", true))
        assertEquals(listOf("pause", "seek 122500", "volume 0.25", "repeat ALL", "shuffle true"), player.calls)
    }

    @Test
    fun upcomingIndicesBecomePlayerIndices() {
        apply("remove_queue_item", JSONObject().put("index", 0))
        apply("move_queue_item", JSONObject().put("from", 1).put("to", 0))
        apply("play_queue_index", JSONObject().put("index", 1))
        assertEquals(listOf("remove 3", "move 4 3", "jump 4"), player.calls)
    }

    @Test
    fun playTrackReplacesTheQueue() {
        apply(
            "play_track",
            JSONObject()
                .put("track", ConnectWire.track(track("x")))
                .put("tracks", JSONArray().put(ConnectWire.track(track("y"))))
                .put("position", 30.0)
                .put("play", true),
        )
        assertEquals(listOf("x", "y"), player.queued.map { it.id })
        assertEquals("replace 0 30000 true Desktop", player.calls.single())
    }

    @Test
    fun transferStartsNearTheControllersPosition() {
        val snapshot = JSONObject()
            .put("track", ConnectWire.track(track("t")))
            .put("queue", JSONArray().put(ConnectWire.track(track("u"))))
            .put("position", 94.2)
            .put("playing", true)
            .put("shuffle", false)
            .put("repeat", "one")
        ConnectTarget.transfer(player, snapshot, contextTitle = "Pixel")
        assertEquals(listOf("t", "u"), player.queued.map { it.id })
        assertEquals(listOf("shuffle false", "repeat ONE", "replace 0 94200 true Pixel"), player.calls)
    }
}
