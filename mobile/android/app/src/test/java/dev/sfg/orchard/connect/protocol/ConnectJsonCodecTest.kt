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

package dev.sfg.orchard.connect.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectJsonCodecTest {
    @Test
    fun decodesCurrentDesktopSnapshotShape() {
        val payload = JSONObject()
            .put("status", "connected")
            .put("track", JSONObject().put("id", "video").put("title", "Song").put("artist", "Artist"))
            .put("playback", JSONObject().put("isPlaying", true).put("currentTime", 12.5).put("duration", 180))
            .put("lyrics", JSONObject().put("mode", "synced").put("lines", JSONArray().put(JSONObject().put("text", "Line").put("startTime", 10))))
            .put("queue", JSONArray().put(JSONObject().put("id", "next").put("title", "Next")))
            .put("audioEngine", JSONObject().put("activePreset", "warm").put("config", JSONObject().put("eqEnabled", true)))

        val snapshot = ConnectJsonCodec.snapshot(payload)

        assertEquals("video", snapshot.track?.id)
        assertTrue(snapshot.playback.isPlaying)
        assertEquals(12.5, snapshot.playback.currentTime, 0.001)
        assertEquals("Line", snapshot.lyrics.lines.single().text)
        assertEquals("next", snapshot.queue.single().id)
        assertTrue(snapshot.audioEngine.manualEqEnabled)
    }

    @Test
    fun clampsOutboundCommandValues() {
        val volume = ConnectJsonCodec.command(ConnectCommand.Volume(4.0))
        val seek = ConnectJsonCodec.command(ConnectCommand.Seek(-2.0))

        assertEquals(1.0, volume.getDouble(ConnectProtocol.Field.VALUE), 0.0)
        assertEquals(0.0, seek.getDouble(ConnectProtocol.Field.VALUE), 0.0)
    }

    @Test
    fun decodesProtocolV2FieldsAndAnimatedArtwork() {
        val payload = JSONObject()
            .put("status", "playing")
            .put("protocolVersion", 2)
            .put(
                "track",
                JSONObject()
                    .put("id", "track-123")
                    .put("title", "Song V2")
                    .put("artist", "Artist V2")
                    .put("animatedArtwork", "https://example.com/art.mp4")
                    .put("animatedArtworkVertical", "https://example.com/art_vert.mp4")
            )
            .put(
                "playback",
                JSONObject()
                    .put("isPlaying", true)
                    .put("volume", 0.75)
                    .put("shuffle", true)
                    .put("repeatMode", "one")
            )
            .put(
                "audioEngine",
                JSONObject()
                    .put("activePreset", "bass_boost")
                    .put("autoEqEnabled", true)
                    .put("manualEqEnabled", false)
                    .put(
                        "presets",
                        JSONArray().put(JSONObject().put("value", "bass_boost").put("label", "Bass Boost"))
                    )
            )

        val snapshot = ConnectJsonCodec.snapshot(payload)

        assertEquals(2, snapshot.protocolVersion)
        assertEquals("track-123", snapshot.track?.id)
        assertEquals("https://example.com/art.mp4", snapshot.track?.animatedArtwork)
        assertEquals("https://example.com/art_vert.mp4", snapshot.track?.animatedArtworkVertical)
        assertEquals(0.75, snapshot.playback.volume, 0.001)
        assertTrue(snapshot.playback.shuffle)
        assertEquals("one", snapshot.playback.repeatMode)
        assertEquals("bass_boost", snapshot.audioEngine.activePreset)
        assertTrue(snapshot.audioEngine.autoEqEnabled)
        assertFalse(snapshot.audioEngine.manualEqEnabled)
        assertEquals(1, snapshot.audioEngine.presets.size)
        assertEquals("Bass Boost", snapshot.audioEngine.presets[0].label)
    }

    @Test
    fun serializesProtocolV2CommandsCorrectly() {
        val playNext = ConnectJsonCodec.command(ConnectCommand.PlayNext(JSONObject().put("id", "t1")))
        assertEquals(ConnectProtocol.CommandType.PLAY_NEXT, playNext.getString("type"))
        assertEquals("t1", playNext.getJSONObject("value").getString("id"))

        val moveQueue = ConnectJsonCodec.command(ConnectCommand.MoveQueueIndex(1, 4))
        assertEquals(ConnectProtocol.CommandType.MOVE_QUEUE_INDEX, moveQueue.getString("type"))
        assertEquals(1, moveQueue.getJSONObject("value").getInt("from"))
        assertEquals(4, moveQueue.getJSONObject("value").getInt("to"))

        val clearUpcoming = ConnectJsonCodec.command(ConnectCommand.ClearUpcoming)
        assertEquals(ConnectProtocol.CommandType.CLEAR_UPCOMING, clearUpcoming.getString("type"))

        val toggleShuffle = ConnectJsonCodec.command(ConnectCommand.ToggleShuffle)
        assertEquals(ConnectProtocol.CommandType.TOGGLE_SHUFFLE, toggleShuffle.getString("type"))

        val cycleRepeat = ConnectJsonCodec.command(ConnectCommand.CycleRepeat)
        assertEquals(ConnectProtocol.CommandType.CYCLE_REPEAT, cycleRepeat.getString("type"))

        val setPreset = ConnectJsonCodec.command(ConnectCommand.AudioEnginePreset("electronic"))
        assertEquals(ConnectProtocol.CommandType.AUDIO_ENGINE_PRESET, setPreset.getString("type"))
        assertEquals("electronic", setPreset.getString("value"))

        val toggleAutoEq = ConnectJsonCodec.command(ConnectCommand.AutoEq(true))
        assertEquals(ConnectProtocol.CommandType.AUDIO_ENGINE_AUTO_EQ, toggleAutoEq.getString("type"))
        assertTrue(toggleAutoEq.getBoolean("value"))
    }

    @Test
    fun preservesOpaquePlaybackItemWhenPlayingSearchResult() {
        val item = ConnectJsonCodec.remoteItem(
            JSONObject().put("id", "safe").put("playbackItem", JSONObject().put("id", "raw").put("futureField", true))
        )
        val command = ConnectJsonCodec.command(ConnectCommand.PlayTrack(item))
        val value = command.getJSONObject(ConnectProtocol.Field.VALUE)

        assertEquals("raw", value.getString("id"))
        assertTrue(value.getBoolean("futureField"))
        assertFalse(value.has("playbackItem"))
    }

    @Test
    fun serializesAndDecodesAnalysisRequestsAndResults() {
        val request = ConnectJsonCodec.analysis(listOf("track-1", "track-2"), "req-99")
        assertEquals("req-99", request.getString(ConnectProtocol.Field.REQUEST_ID))
        val trackIds = request.getJSONArray(ConnectProtocol.Field.TRACK_IDS)
        assertEquals(2, trackIds.length())
        assertEquals("track-1", trackIds.getString(0))
        assertEquals("track-2", trackIds.getString(1))

        val resultsJson = JSONObject()
            .put(ConnectProtocol.Field.REQUEST_ID, "req-99")
            .put(
                ConnectProtocol.Field.RESULTS,
                JSONArray().put(
                    JSONObject()
                        .put("id", "track-1")
                        .put("bpm", 124.0)
                        .put("musicalKey", "8B")
                        .put("cueIn", 1.5)
                        .put("cueOut", 195.0)
                        .put("duration", 200.0)
                )
            )

        val results = ConnectJsonCodec.analysisResults(resultsJson)
        assertEquals("req-99", results.requestId)
        assertEquals(1, results.results.size)
        assertEquals("track-1", results.results[0].id)
        assertEquals(124.0, results.results[0].bpm, 0.001)
        assertEquals("8B", results.results[0].musicalKey)
    }

    @Test
    fun decodesIncomingCommandsFromDesktop() {
        val playPause = ConnectJsonCodec.commandFromPayload(JSONObject().put("type", "play-pause"))
        assertEquals(ConnectCommand.TogglePlayback, playPause)

        val next = ConnectJsonCodec.commandFromPayload(JSONObject().put("type", "next"))
        assertEquals(ConnectCommand.Next, next)

        val seek = ConnectJsonCodec.commandFromPayload(JSONObject().put("type", "seek").put("value", 42.5))
        assertTrue(seek is ConnectCommand.Seek)
        assertEquals(42.5, (seek as ConnectCommand.Seek).seconds, 0.001)

        val transferPayload = JSONObject()
            .put("type", "transfer")
            .put(
                "value",
                JSONObject()
                    .put("track", JSONObject().put("id", "track-abc").put("title", "Remote Song"))
                    .put("positionSeconds", 15.0)
                    .put("queue", JSONArray().put(JSONObject().put("id", "track-abc")).put(JSONObject().put("id", "track-def")))
                    .put("shuffle", true)
                    .put("repeatMode", "queue")
                    .put("autoplay", true)
                    .put("play", false)
            )

        val transfer = ConnectJsonCodec.commandFromPayload(transferPayload)
        assertTrue(transfer is ConnectCommand.Transfer)
        val transferCmd = transfer as ConnectCommand.Transfer
        assertEquals("track-abc", transferCmd.track?.getString("id"))
        assertEquals(15.0, transferCmd.positionSeconds, 0.001)
        assertEquals(2, transferCmd.queue.size)
        assertTrue(transferCmd.shuffle)
        assertEquals("queue", transferCmd.repeatMode)
        assertTrue(transferCmd.autoplay)
        assertFalse(transferCmd.play)

        val unknown = ConnectJsonCodec.commandFromPayload(JSONObject().put("type", "future-command"))
        assertTrue(unknown is ConnectCommand.Unknown)
    }

    @Test
    fun serializesDeviceStateForDesktopConsumption() {
        val snapshot = dev.sfg.orchard.mobile.model.PlaybackSnapshot(
            status = dev.sfg.orchard.mobile.model.PlaybackStatus.PLAYING,
            currentTrack = dev.sfg.orchard.mobile.model.Track(
                id = "track-phone",
                title = "Phone Title",
                artist = "Phone Artist",
                album = "Phone Album",
                artworkUrl = "https://example.com/art.jpg",
                durationMs = 180000
            ),
            positionMs = 30000,
            durationMs = 180000,
            volume = 0.65f,
            shuffle = true,
            repeatMode = dev.sfg.orchard.mobile.model.RepeatMode.ALL,
            currentIndex = 0,
            queue = listOf(
                dev.sfg.orchard.mobile.model.Track(
                    id = "track-phone",
                    title = "Phone Title",
                    artist = "Phone Artist"
                ),
                dev.sfg.orchard.mobile.model.Track(
                    id = "track-phone-next",
                    title = "Next Title",
                    artist = "Next Artist"
                )
            )
        )

        val stateJson = ConnectJsonCodec.deviceState(snapshot, 3, autoplay = true)
        assertEquals("playing", stateJson.getString("status"))
        assertEquals(3, stateJson.getInt("protocolVersion"))
        assertEquals("track-phone", stateJson.getJSONObject("track").getString("id"))
        assertEquals("Phone Title", stateJson.getJSONObject("track").getString("title"))
        assertEquals(30.0, stateJson.getJSONObject("playback").getDouble("currentTime"), 0.001)
        assertTrue(stateJson.getJSONObject("playback").getBoolean("isPlaying"))
        assertTrue(stateJson.getJSONObject("playback").getBoolean("shuffle"))
        assertEquals(0.65, stateJson.getJSONObject("playback").getDouble("volume"), 0.001)
        assertEquals("queue", stateJson.getJSONObject("playback").getString("repeatMode"))
        assertTrue(stateJson.getJSONObject("playback").getBoolean("autoplay"))
        assertEquals(1, stateJson.getJSONArray("queue").length())
        assertEquals("track-phone-next", stateJson.getJSONArray("queue").getJSONObject(0).getString("id"))
    }
}
