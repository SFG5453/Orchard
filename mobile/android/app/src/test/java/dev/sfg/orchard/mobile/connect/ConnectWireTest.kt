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

import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackStatus
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.model.Track
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectWireTest {
    private val song = Track(
        id = "abc123",
        title = "Song",
        artist = "Band",
        album = "Record",
        albumId = "MPREb_1",
        artworkUrl = "https://example.test/cover.jpg",
        durationMs = 200_000,
        explicit = true,
        musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
        artists = listOf(Artist(id = "UC1", name = "Band"), Artist(id = "UC2", name = "Guest")),
    )

    @Test
    fun trackRoundTrip() {
        val wire = ConnectWire.track(song)
        assertEquals(200.0, wire.getDouble("duration"), 0.0)
        assertEquals("youtube", wire.getString("provider"))
        val back = ConnectWire.track(wire)!!
        assertEquals(song.id, back.id)
        assertEquals(song.title, back.title)
        assertEquals(song.durationMs, back.durationMs)
        assertEquals(song.explicit, back.explicit)
        assertEquals(song.musicVideoType, back.musicVideoType)
        assertEquals(listOf("Band", "Guest"), back.artists.map { it.name })
        assertEquals("UC2", back.artists[1].id)
    }

    @Test
    fun desktopTrackWithoutHintsStillPlays() {
        // What desktop sends for a row without artist ids: canonical fields and a sparse extra.
        val wire = JSONObject("""{"id":"x1","title":"T","artist":"A","duration":61.5,"extra":{"type":"song"}}""")
        val track = ConnectWire.track(wire)!!
        assertEquals("A", track.artist)
        assertEquals(61_500L, track.durationMs)
        assertNull(ConnectWire.track(JSONObject().put("title", "no id")))
    }

    @Test
    fun localSnapshotSendsOnlyUpcoming() {
        val history = song.copy(id = "h")
        val next = song.copy(id = "n")
        val local = PlaybackSnapshot(
            status = PlaybackStatus.PLAYING,
            currentTrack = song,
            queue = listOf(history, song, next),
            currentIndex = 1,
            positionMs = 94_000,
            isPlaying = true,
            volume = 0.5f,
            repeatMode = RepeatMode.ALL,
        )
        val wire = ConnectWire.snapshot(local, autoplay = true)
        assertEquals(94.0, wire.getDouble("position"), 0.0)
        assertEquals(listOf("n"), (0 until wire.getJSONArray("queue").length()).map {
            wire.getJSONArray("queue").getJSONObject(it).getString("id")
        })
        assertEquals("all", wire.getString("repeat"))
        assertTrue(wire.getBoolean("autoplay"))
    }

    @Test
    fun remoteSnapshotPutsCurrentFirst() {
        val wire = JSONObject()
            .put("track", ConnectWire.track(song))
            .put("queue", JSONArray().put(ConnectWire.track(song.copy(id = "n1"))).put(ConnectWire.track(song.copy(id = "n2"))))
            .put("playing", true)
            .put("position", 12.5)
            .put("volume", 3.0)
            .put("repeat", "one")
        val shown = ConnectWire.snapshot(wire)
        assertEquals(listOf("abc123", "n1", "n2"), shown.queue.map { it.id })
        assertEquals(0, shown.currentIndex)
        assertEquals(12_500L, shown.positionMs)
        assertEquals(1f, shown.volume)
        assertEquals(RepeatMode.ONE, shown.repeatMode)
        // The queue UI addresses n2 as index 2; the target knows it as upcoming item 1.
        assertEquals(1, ConnectWire.upcomingIndex(shown, 2))
        assertEquals(-1, ConnectWire.upcomingIndex(shown, 0))
    }
}
