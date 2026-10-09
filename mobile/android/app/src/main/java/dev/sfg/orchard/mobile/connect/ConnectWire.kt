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

/**
 * Phone models <-> the Connect wire format (`core/native/connect/playback.h`).
 *
 * Wire queues hold only upcoming tracks and index them from zero; the phone's queue also holds
 * history and the current track. [upcomingIndex] converts between the two.
 */
internal object ConnectWire {
    // Desktop caps its queue at this; a longer one is cut to fit.
    const val QUEUE_LIMIT = 2500

    fun track(track: Track): JSONObject {
        val extra = JSONObject()
            .put("type", if (track.isVideoUpload) "video" else "track")
            .put("artists", JSONArray(track.artists.map(Artist::name).ifEmpty { listOf(track.artist) }))
            .put("artistBrowseIds", JSONArray(track.artists.map(Artist::id).filter(String::isNotBlank)))
            .put("musicVideoType", track.musicVideoType)
            .put("isAudioOnly", track.isAudioOnly)
            .put("musicVideoId", track.musicVideoId)
            .put("isUpload", track.isUpload)
        return JSONObject()
            .put("id", track.id)
            .put("title", track.title)
            .put("artist", track.artist)
            .put("album", track.album)
            .put("artwork", track.artworkUrl)
            .put("duration", track.durationMs / 1000.0)
            .put("provider", "youtube")
            .put("album_id", track.albumId)
            .put("artist_id", track.artistId)
            .put("explicit", track.explicit)
            .put("extra", extra)
    }

    fun track(json: JSONObject?): Track? {
        val id = json?.optString("id").orEmpty()
        if (id.isBlank()) return null
        val extra = json!!.optJSONObject("extra") ?: JSONObject()
        val names = extra.optJSONArray("artists")
        val ids = extra.optJSONArray("artistBrowseIds")
        val artists = (0 until (names?.length() ?: 0)).map { index ->
            Artist(id = ids?.optString(index).orEmpty(), name = names!!.optString(index))
        }.filter { it.name.isNotBlank() }
        return Track(
            id = id,
            title = json.optString("title"),
            artist = json.optString("artist").ifBlank { artists.firstOrNull()?.name.orEmpty() },
            album = json.optString("album"),
            albumId = json.optString("album_id"),
            artistId = json.optString("artist_id").ifBlank { artists.firstOrNull()?.id.orEmpty() },
            artworkUrl = json.optString("artwork"),
            durationMs = (json.optDouble("duration", 0.0) * 1000).toLong(),
            explicit = json.optBoolean("explicit"),
            musicVideoType = extra.optString("musicVideoType"),
            musicVideoId = extra.optString("musicVideoId"),
            isUpload = extra.optBoolean("isUpload"),
            artists = artists,
        )
    }

    fun tracks(list: List<Track>, limit: Int = QUEUE_LIMIT): JSONArray =
        JSONArray().also { array -> list.take(limit).forEach { array.put(track(it)) } }

    fun tracks(array: JSONArray?): List<Track> =
        (0 until (array?.length() ?: 0)).mapNotNull { track(array!!.optJSONObject(it)) }

    /** This phone's player as a PlaybackSnapshot. */
    fun snapshot(local: PlaybackSnapshot, autoplay: Boolean): JSONObject {
        val current = local.currentTrack
        return JSONObject()
            .put("track", current?.let(::track) ?: JSONObject.NULL)
            .put("position", local.positionMs / 1000.0)
            .put("duration", local.durationMs / 1000.0)
            .put("playing", local.isPlaying)
            .put("buffering", local.status == PlaybackStatus.BUFFERING || local.status == PlaybackStatus.LOADING)
            .put("queue", tracks(if (current == null) local.queue else local.upcoming))
            .put("volume", local.volume.toDouble())
            .put("repeat", repeat(local.repeatMode))
            .put("shuffle", local.shuffle)
            .put("context_title", local.contextTitle)
            .put("autoplay", autoplay)
    }

    /** A target's state as the phone shows it: current track first, then what plays next. */
    fun snapshot(json: JSONObject): PlaybackSnapshot {
        val current = track(json.optJSONObject("track"))
        val upcoming = tracks(json.optJSONArray("queue"))
        val playing = json.optBoolean("playing")
        val buffering = json.optBoolean("buffering")
        return PlaybackSnapshot(
            status = when {
                buffering -> PlaybackStatus.BUFFERING
                playing -> PlaybackStatus.PLAYING
                current != null -> PlaybackStatus.PAUSED
                else -> PlaybackStatus.IDLE
            },
            currentTrack = current,
            queue = listOfNotNull(current) + upcoming,
            currentIndex = if (current != null) 0 else -1,
            positionMs = (json.optDouble("position", 0.0) * 1000).toLong(),
            durationMs = (json.optDouble("duration", 0.0) * 1000).toLong(),
            isPlaying = playing,
            volume = json.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f),
            shuffle = json.optBoolean("shuffle"),
            repeatMode = repeat(json.optString("repeat")),
            contextTitle = json.optString("context_title"),
        )
    }

    /** Wire index of an absolute index into [snapshot]'s queue; -1 for history or the current track. */
    fun upcomingIndex(snapshot: PlaybackSnapshot, index: Int): Int =
        (index - snapshot.currentIndex.coerceAtLeast(-1) - 1).takeIf { it >= 0 } ?: -1

    fun repeat(mode: RepeatMode): String = when (mode) {
        RepeatMode.ONE -> "one"
        RepeatMode.ALL -> "all"
        RepeatMode.OFF -> "off"
    }

    fun repeat(mode: String): RepeatMode = when (mode) {
        "one" -> RepeatMode.ONE
        "all" -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }
}
