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
import kotlin.math.max

/**
 * The only JSON serialization boundary used by the Connect client.
 *
 * Decoders tolerate missing additive desktop fields, while outbound messages
 * remain byte-shape compatible with the existing Socket.IO implementation.
 */
object ConnectJsonCodec {
    fun hello(
        token: String,
        deviceToken: String,
        deviceName: String,
        protocolVersion: Int = ConnectProtocol.PROTOCOL_VERSION
    ): JSONObject = JSONObject()
        .put(ConnectProtocol.Field.TOKEN, token)
        .put(ConnectProtocol.Field.DEVICE_TOKEN, deviceToken)
        .put(ConnectProtocol.Field.NAME, deviceName.take(60))
        .put(ConnectProtocol.Field.PROTOCOL_VERSION, protocolVersion)

    fun command(command: ConnectCommand): JSONObject {
        val (type, value) = when (command) {
            ConnectCommand.TogglePlayback -> ConnectProtocol.CommandType.PLAY_PAUSE to JSONObject.NULL
            ConnectCommand.Play -> ConnectProtocol.CommandType.PLAY to JSONObject.NULL
            ConnectCommand.Pause -> ConnectProtocol.CommandType.PAUSE to JSONObject.NULL
            ConnectCommand.Next -> ConnectProtocol.CommandType.NEXT to JSONObject.NULL
            ConnectCommand.Previous -> ConnectProtocol.CommandType.PREVIOUS to JSONObject.NULL
            is ConnectCommand.Volume -> ConnectProtocol.CommandType.VOLUME to command.value.coerceIn(0.0, 1.0)
            is ConnectCommand.Seek -> ConnectProtocol.CommandType.SEEK to max(0.0, command.seconds)
            is ConnectCommand.AudioEnginePreset -> ConnectProtocol.CommandType.AUDIO_ENGINE_PRESET to command.value
            is ConnectCommand.AutoEq -> ConnectProtocol.CommandType.AUDIO_ENGINE_AUTO_EQ to command.enabled
            is ConnectCommand.ManualEq -> ConnectProtocol.CommandType.AUDIO_ENGINE_MANUAL_EQ to command.enabled
            is ConnectCommand.PlayQueueIndex -> ConnectProtocol.CommandType.PLAY_QUEUE_INDEX to command.index
            is ConnectCommand.RemoveQueueIndex -> ConnectProtocol.CommandType.REMOVE_QUEUE_INDEX to command.index
            is ConnectCommand.MoveQueueIndex -> ConnectProtocol.CommandType.MOVE_QUEUE_INDEX to JSONObject()
                .put(ConnectProtocol.Field.FROM, command.from)
                .put(ConnectProtocol.Field.TO, command.to)
            ConnectCommand.ClearUpcoming -> ConnectProtocol.CommandType.CLEAR_UPCOMING to JSONObject.NULL
            is ConnectCommand.PlayNext -> ConnectProtocol.CommandType.PLAY_NEXT to copy(command.track)
            is ConnectCommand.AddToQueue -> ConnectProtocol.CommandType.ADD_TO_QUEUE to copy(command.track)
            ConnectCommand.ToggleShuffle -> ConnectProtocol.CommandType.TOGGLE_SHUFFLE to JSONObject.NULL
            ConnectCommand.CycleRepeat -> ConnectProtocol.CommandType.CYCLE_REPEAT to JSONObject.NULL
            is ConnectCommand.PlayTrack -> ConnectProtocol.CommandType.PLAY_TRACK to copy(command.item.playbackPayload)
            is ConnectCommand.PlayTrackPayload -> ConnectProtocol.CommandType.PLAY_TRACK to copy(command.payload)
            is ConnectCommand.Transfer -> ConnectProtocol.CommandType.TRANSFER to JSONObject()
                .put(ConnectProtocol.Field.TRACK, command.track?.let(::copy) ?: JSONObject.NULL)
                .put(ConnectProtocol.Field.POSITION_SECONDS, command.positionSeconds)
                .put(ConnectProtocol.Field.QUEUE, JSONArray(command.queue.map(::copy)))
                .put(ConnectProtocol.Field.SHUFFLE, command.shuffle)
                .put(ConnectProtocol.Field.REPEAT_MODE, command.repeatMode)
                .put(ConnectProtocol.Field.AUTOPLAY, command.autoplay)
                .put(ConnectProtocol.Field.PLAY, command.play)
            is ConnectCommand.ReplaceQueue -> ConnectProtocol.CommandType.REPLACE_QUEUE to JSONObject()
                .put(ConnectProtocol.Field.TRACKS, JSONArray(command.tracks.map(::copy)))
                .put(ConnectProtocol.Field.START_INDEX, command.startIndex)
                .put(ConnectProtocol.Field.POSITION_SECONDS, command.positionSeconds)
                .put(ConnectProtocol.Field.PLAY, command.play)
                .put(ConnectProtocol.Field.CONTEXT_TITLE, command.contextTitle)
            is ConnectCommand.Unknown -> command.type to JSONObject.NULL
        }
        return JSONObject().put(ConnectProtocol.Field.TYPE, type).put(ConnectProtocol.Field.VALUE, value)
    }

    fun commandFromPayload(payload: JSONObject): ConnectCommand {
        val type = payload.optString(ConnectProtocol.Field.TYPE)
        val value = payload.opt(ConnectProtocol.Field.VALUE)
        return when (type) {
            ConnectProtocol.CommandType.PLAY_PAUSE -> ConnectCommand.TogglePlayback
            ConnectProtocol.CommandType.PLAY -> ConnectCommand.Play
            ConnectProtocol.CommandType.PAUSE -> ConnectCommand.Pause
            ConnectProtocol.CommandType.NEXT -> ConnectCommand.Next
            ConnectProtocol.CommandType.PREVIOUS -> ConnectCommand.Previous
            ConnectProtocol.CommandType.VOLUME -> ConnectCommand.Volume(seconds(value).coerceIn(0.0, 1.0))
            ConnectProtocol.CommandType.SEEK -> ConnectCommand.Seek(seconds(value))
            ConnectProtocol.CommandType.AUDIO_ENGINE_PRESET -> ConnectCommand.AudioEnginePreset(value?.toString().orEmpty())
            ConnectProtocol.CommandType.AUDIO_ENGINE_AUTO_EQ -> ConnectCommand.AutoEq(value == true || value == "true")
            ConnectProtocol.CommandType.AUDIO_ENGINE_MANUAL_EQ -> ConnectCommand.ManualEq(value == true || value == "true")
            ConnectProtocol.CommandType.PLAY_QUEUE_INDEX -> ConnectCommand.PlayQueueIndex((value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0)
            ConnectProtocol.CommandType.REMOVE_QUEUE_INDEX -> ConnectCommand.RemoveQueueIndex((value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0)
            ConnectProtocol.CommandType.MOVE_QUEUE_INDEX -> {
                val obj = value as? JSONObject ?: JSONObject()
                ConnectCommand.MoveQueueIndex(obj.optInt(ConnectProtocol.Field.FROM), obj.optInt(ConnectProtocol.Field.TO))
            }
            ConnectProtocol.CommandType.CLEAR_UPCOMING -> ConnectCommand.ClearUpcoming
            ConnectProtocol.CommandType.PLAY_NEXT -> ConnectCommand.PlayNext(value as? JSONObject ?: JSONObject())
            ConnectProtocol.CommandType.ADD_TO_QUEUE -> ConnectCommand.AddToQueue(value as? JSONObject ?: JSONObject())
            ConnectProtocol.CommandType.TOGGLE_SHUFFLE -> ConnectCommand.ToggleShuffle
            ConnectProtocol.CommandType.CYCLE_REPEAT -> ConnectCommand.CycleRepeat
            ConnectProtocol.CommandType.PLAY_TRACK -> ConnectCommand.PlayTrackPayload(value as? JSONObject ?: JSONObject())
            ConnectProtocol.CommandType.TRANSFER -> {
                val obj = value as? JSONObject ?: JSONObject()
                val trackObj = obj.optJSONObject(ConnectProtocol.Field.TRACK)
                val queueArray = obj.optJSONArray(ConnectProtocol.Field.QUEUE)
                val queue = objects(queueArray)
                ConnectCommand.Transfer(
                    track = trackObj,
                    positionSeconds = seconds(obj.opt(ConnectProtocol.Field.POSITION_SECONDS)),
                    queue = queue,
                    shuffle = obj.optBoolean(ConnectProtocol.Field.SHUFFLE, false),
                    repeatMode = obj.optString(ConnectProtocol.Field.REPEAT_MODE, "off"),
                    autoplay = obj.optBoolean(ConnectProtocol.Field.AUTOPLAY, false),
                    play = obj.optBoolean(ConnectProtocol.Field.PLAY, true)
                )
            }
            ConnectProtocol.CommandType.REPLACE_QUEUE -> {
                val obj = value as? JSONObject ?: JSONObject()
                val tracksArray = obj.optJSONArray(ConnectProtocol.Field.TRACKS)
                ConnectCommand.ReplaceQueue(
                    tracks = objects(tracksArray),
                    startIndex = obj.optInt(ConnectProtocol.Field.START_INDEX, 0),
                    positionSeconds = seconds(obj.opt(ConnectProtocol.Field.POSITION_SECONDS)),
                    play = obj.optBoolean(ConnectProtocol.Field.PLAY, true),
                    contextTitle = obj.optString(ConnectProtocol.Field.CONTEXT_TITLE, "")
                )
            }
            else -> ConnectCommand.Unknown(type)
        }
    }

    fun deviceState(
        snapshot: dev.sfg.orchard.mobile.model.PlaybackSnapshot,
        protocolVersion: Int = ConnectProtocol.PROTOCOL_VERSION,
        autoplay: Boolean = false,
    ): JSONObject {
        val trackJson = snapshot.currentTrack?.let { track ->
            JSONObject()
                .put("id", track.id)
                .put("title", track.title)
                .put("artist", track.artist)
                .put("album", track.album)
                .put("thumbnail", track.artworkUrl)
                .put("artwork", track.artworkUrl)
                .put("animatedArtwork", track.animatedArtworkUrl)
                .put("animatedArtworkVertical", track.animatedArtworkVerticalUrl)
                .put("durationSeconds", track.durationMs / 1000.0)
        }
        val isPlaying = snapshot.isPlaying || snapshot.status == dev.sfg.orchard.mobile.model.PlaybackStatus.PLAYING
        val playbackJson = JSONObject()
            .put("isPlaying", isPlaying)
            .put("buffering", snapshot.status == dev.sfg.orchard.mobile.model.PlaybackStatus.BUFFERING)
            .put("currentTime", snapshot.positionMs / 1000.0)
            .put("duration", snapshot.durationMs / 1000.0)
            .put("volume", snapshot.volume.toDouble())
            .put("shuffle", snapshot.shuffle)
            .put("autoplay", autoplay)
            .put("repeatMode", when (snapshot.repeatMode) {
                dev.sfg.orchard.mobile.model.RepeatMode.ONE -> "one"
                dev.sfg.orchard.mobile.model.RepeatMode.ALL -> "queue"
                else -> "off"
            })
        val queueArray = JSONArray()
        for (track in snapshot.upcoming.take(50)) {
            queueArray.put(
                JSONObject()
                    .put("id", track.id)
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("album", track.album)
                    .put("thumbnail", track.artworkUrl)
                    .put("artwork", track.artworkUrl)
                    .put("animatedArtwork", track.animatedArtworkUrl)
                    .put("animatedArtworkVertical", track.animatedArtworkVerticalUrl)
                    .put("durationSeconds", track.durationMs / 1000.0)
            )
        }
        return JSONObject()
            .put("status", if (isPlaying) "playing" else if (snapshot.currentTrack != null) "paused" else "idle")
            .put("protocolVersion", protocolVersion)
            .put("track", trackJson ?: JSONObject.NULL)
            .put("playback", playbackJson)
            .put("queue", queueArray)
            .put("lyrics", JSONObject().put("status", "idle").put("mode", "").put("lines", JSONArray()))
    }

    fun search(query: String, requestId: String): JSONObject = JSONObject()
        .put(ConnectProtocol.Field.QUERY, query)
        .put(ConnectProtocol.Field.REQUEST_ID, requestId)

    fun library(requestId: String): JSONObject = JSONObject()
        .put(ConnectProtocol.Field.REQUEST_ID, requestId)

    fun analysis(trackIds: List<String>, requestId: String): JSONObject {
        val array = JSONArray()
        for (id in trackIds) {
            if (id.isNotBlank()) array.put(id)
        }
        return JSONObject()
            .put(ConnectProtocol.Field.TRACK_IDS, array)
            .put(ConnectProtocol.Field.REQUEST_ID, requestId)
    }

    fun unwrapReply(reply: JSONObject): JSONObject {
        if (!reply.has(ConnectProtocol.Field.OK)) return reply
        if (!reply.optBoolean(ConnectProtocol.Field.OK)) {
            throw IllegalStateException(reply.optString(ConnectProtocol.Field.ERROR, "Request failed"))
        }
        return reply.optJSONObject(ConnectProtocol.Field.DATA) ?: JSONObject()
    }

    fun helloResult(reply: JSONObject): HelloResult {
        val payload = unwrapReply(reply)
        val status = when (payload.optString(ConnectProtocol.Field.STATUS)) {
            "approved" -> HelloStatus.APPROVED
            "pending" -> HelloStatus.PENDING
            "expired" -> HelloStatus.EXPIRED
            else -> HelloStatus.UNKNOWN
        }
        val rawVersion = payload.optInt(ConnectProtocol.Field.PROTOCOL_VERSION, 1)
        val protocolVersion = if (rawVersion > ConnectProtocol.PROTOCOL_VERSION) ConnectProtocol.PROTOCOL_VERSION else rawVersion
        val state = payload.optJSONObject(ConnectProtocol.Field.STATE)?.let { snapshot(it, protocolVersion) }
        return HelloResult(status, state, protocolVersion)
    }

    fun snapshot(payload: JSONObject, defaultProtocolVersion: Int = 1): ConnectSnapshot {
        val rawVersion = payload.optInt(ConnectProtocol.Field.PROTOCOL_VERSION, defaultProtocolVersion)
        val protocolVersion = if (rawVersion > ConnectProtocol.PROTOCOL_VERSION) ConnectProtocol.PROTOCOL_VERSION else rawVersion
        val trackJson = payload.optJSONObject("track")
        val playbackJson = payload.optJSONObject("playback") ?: JSONObject()
        val lyricsJson = payload.optJSONObject("lyrics") ?: JSONObject()
        val queueJson = payload.optJSONArray("queue") ?: JSONArray()
        val engineJson = payload.optJSONObject("audioEngine") ?: JSONObject()
        val configJson = engineJson.optJSONObject("config") ?: JSONObject()
        val presetJson = engineJson.optJSONArray("presets") ?: JSONArray()

        return ConnectSnapshot(
            status = payload.optString("status", "idle"),
            protocolVersion = protocolVersion,
            track = trackJson?.let(::track),
            playback = ConnectPlayback(
                isPlaying = playbackJson.optBoolean("isPlaying"),
                buffering = playbackJson.optBoolean("buffering"),
                currentTime = seconds(playbackJson.opt("currentTime")),
                duration = seconds(playbackJson.opt("duration")),
                volume = playbackJson.optDouble("volume", 0.0).coerceIn(0.0, 1.0),
                shuffle = playbackJson.optBoolean("shuffle", false),
                repeatMode = playbackJson.optString("repeatMode", "off"),
                autoplay = playbackJson.optBoolean("autoplay", false)
            ),
            lyrics = ConnectLyrics(
                status = lyricsJson.optString("status", "idle"),
                mode = lyricsJson.optString("mode"),
                lines = objects(lyricsJson.optJSONArray("lines")).map(::lyricLine)
            ),
            queue = objects(queueJson).map(::track),
            audioEngine = ConnectAudioEngine(
                manualEqEnabled = engineJson.optBoolean("manualEqEnabled", configJson.optBoolean("eqEnabled")),
                autoEqEnabled = engineJson.optBoolean("autoEqEnabled", configJson.optBoolean("autoEqEnabled")),
                activePreset = engineJson.optString("activePreset", "flat"),
                presets = objects(presetJson).map {
                    ConnectAudioPreset(it.optString("value"), it.optString("label"))
                }
            ),
            rawPayload = copy(payload)
        )
    }

    fun results(payload: JSONObject): ConnectResults = ConnectResults(
        requestId = payload.optString(ConnectProtocol.Field.REQUEST_ID),
        items = objects(payload.optJSONArray(ConnectProtocol.Field.RESULTS)).map(::remoteItem)
    )

    fun analysisResults(payload: JSONObject): ConnectAnalysisResults {
        val requestId = payload.optString(ConnectProtocol.Field.REQUEST_ID)
        val list = objects(payload.optJSONArray(ConnectProtocol.Field.RESULTS)).map { item ->
            ConnectAnalysisResult(
                id = item.optString("id"),
                bpm = item.optDouble("bpm", 0.0),
                musicalKey = item.optString("musicalKey", item.optString("key")),
                keyConfidence = item.optDouble("keyConfidence", 0.0),
                beatConfidence = item.optDouble("beatConfidence", 0.0),
                duration = item.optDouble("duration", 0.0),
                cueIn = item.optDouble("cueIn", 0.0),
                cueOut = item.optDouble("cueOut", 0.0)
            )
        }
        return ConnectAnalysisResults(requestId = requestId, results = list)
    }

    fun remoteItem(payload: JSONObject): ConnectRemoteItem {
        val playback = payload.optJSONObject("playbackItem") ?: payload
        return ConnectRemoteItem(track(payload), copy(playback), copy(payload))
    }

    private fun track(payload: JSONObject): ConnectTrack = ConnectTrack(
        id = payload.optString("id"),
        title = payload.optString("title"),
        artist = payload.optString("artist", payload.optString("subtitle")),
        album = payload.optString("album"),
        thumbnail = payload.optString("thumbnail"),
        artwork = payload.optString("artwork"),
        animatedArtwork = payload.optString("animatedArtwork"),
        animatedArtworkVertical = payload.optString("animatedArtworkVertical"),
        bpm = payload.optDouble("bpm", 0.0),
        musicalKey = payload.optString("musicalKey", payload.optString("key"))
    )

    private fun lyricLine(payload: JSONObject): ConnectLyricLine = ConnectLyricLine(
        text = payload.optString("text"),
        startTime = payload.opt("startTime").takeUnless { it == null || it == JSONObject.NULL }?.let(::seconds),
        endTime = payload.opt("endTime").takeUnless { it == null || it == JSONObject.NULL }?.let(::seconds),
        words = objects(payload.optJSONArray("words")).map {
            ConnectLyricWord(it.optString("text"), seconds(it.opt("startTime")), seconds(it.opt("endTime")))
        }
    )

    private fun seconds(value: Any?): Double {
        if (value is Number) return max(0.0, value.toDouble())
        val text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return 0.0
        if (":" in text) {
            val parts = text.split(":").mapNotNull(String::toDoubleOrNull)
            if (parts.isNotEmpty()) return parts.fold(0.0) { total, part -> total * 60 + max(0.0, part) }
        }
        return max(0.0, text.toDoubleOrNull() ?: 0.0)
    }

    private fun objects(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }

    private fun copy(value: JSONObject): JSONObject = JSONObject(value.toString())
}
