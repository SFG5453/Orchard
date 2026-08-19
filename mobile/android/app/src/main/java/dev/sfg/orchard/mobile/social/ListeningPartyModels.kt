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

package dev.sfg.orchard.mobile.social

import dev.sfg.orchard.mobile.model.Track
import org.json.JSONArray
import org.json.JSONObject

/** Where the client sits relative to the signalling socket. Mirrors the web client's `status`. */
enum class PartyStatus { IDLE, CONNECTING, CONNECTED, OFFLINE }

/** A party role. The worker only ever writes these two strings. */
enum class PartyRole {
    HOST,
    GUEST,
    ;

    val wire: String get() = if (this == HOST) "host" else "guest"

    companion object {
        fun of(value: String?): PartyRole = if (value == "host") HOST else GUEST
    }
}

/** The room as the worker describes it. Ids are the six-character `A-Z2-9` codes. */
data class PartyRoom(
    val id: String = "",
    val hostId: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val closed: Boolean = false,
    val maxParticipants: Int = 0,
    val participantCount: Int = 0,
    val socketUrl: String = "",
    val joinUrl: String = "",
    val shareUrl: String = "",
)

/** This device's seat in the room. [token] authenticates the socket upgrade and is never shown. */
data class PartyParticipant(
    val id: String = "",
    val name: String = "",
    val role: PartyRole = PartyRole.GUEST,
    val token: String = "",
    val joinedAt: Long = 0,
) {
    val isHost: Boolean get() = role == PartyRole.HOST
}

/** Another listener, and the state of the data channel this device holds to them. */
data class PartyPeer(
    val id: String,
    val name: String = "",
    val role: PartyRole = PartyRole.GUEST,
    /** True once the `orchard-party` data channel is open in both directions. */
    val open: Boolean = false,
    /** The `PeerConnection.PeerConnectionState` name, lowercased to match the web's spelling. */
    val state: String = "new",
)

/**
 * A playback snapshot as it travels between clients.
 *
 * Times are seconds and [sentAt] is epoch milliseconds, because the web client computes drift as
 * `(Date.now() - sentAt) / 1000` and adds it to [currentTime]. Sending milliseconds here would
 * put a guest thousands of seconds past the end of the track.
 */
data class PartyPlaybackState(
    val reason: String = "manual",
    val track: Track? = null,
    val queue: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val mediaKind: String = "audio",
    val isPlaying: Boolean = false,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val sentAt: Long = 0,
)

/** Everything the UI renders about the current party. */
data class PartyState(
    val status: PartyStatus = PartyStatus.IDLE,
    val room: PartyRoom? = null,
    val participant: PartyParticipant? = null,
    val peers: List<PartyPeer> = emptyList(),
    val error: String = "",
) {
    val isHost: Boolean get() = participant?.isHost == true
    val isActive: Boolean get() = status != PartyStatus.IDLE
    /** The code a listener types into another device. */
    val code: String get() = room?.id.orEmpty()
}

/** Events the client surfaces to the manager. Signalling and ICE are handled internally. */
sealed interface PartyEvent {
    /** The host pushed a new snapshot, or the worker replayed the last one on join. */
    data class State(val payload: PartyPlaybackState) : PartyEvent

    /** A guest asked this device, as host, to change playback. */
    data class Request(val from: String, val action: String, val payload: JSONObject) : PartyEvent

    /** The host seat moved. [hostId] is the participant that now holds it. */
    data class HostChanged(val hostId: String) : PartyEvent

    /** The room ended for everyone. */
    data class Closed(val reason: String) : PartyEvent

    data class Failed(val message: String) : PartyEvent
}

/**
 * Translates between the mobile [Track] and the loosely typed track objects the web client puts on
 * the wire.
 *
 * The two shapes disagree on three fields — the web calls the cover `thumbnail` and the length
 * `durationSeconds`, and it accepts an artist under any of three keys — so a snapshot that only
 * carried mobile's own names would reach the desktop as a titleless, artless, coverless row that
 * still plays. Every payload therefore carries both spellings: the web reads the ones it knows and
 * spreads the rest through untouched, and mobile prefers its own keys and falls back to the web's.
 */
object PartyTrackJson {
    fun encode(track: Track): JSONObject = JSONObject()
        .put("id", track.id)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("album", track.album)
        // `type` keeps the web's classifier from having to guess. Without it a track carrying an
        // `albumId` risks being read as a collection rather than something playable.
        .put("type", "song")
        .put("thumbnail", track.artworkUrl)
        .put("durationSeconds", track.durationMs / 1000.0)
        .put("explicit", track.explicit)
        // Mobile's own spelling, so a phone-to-phone party round-trips without losing the fields
        // the web has no concept of. The web ignores what it does not recognise.
        .put("albumId", track.albumId)
        .put("artistId", track.artistId)
        .put("artworkUrl", track.artworkUrl)
        .put("animatedArtworkUrl", track.animatedArtworkUrl)
        .put("animatedArtworkVerticalUrl", track.animatedArtworkVerticalUrl)
        .put("durationMs", track.durationMs)
        .put("musicVideoType", track.musicVideoType)
        .put("autoplayGenerated", track.autoplayGenerated)
        .put("isUpload", track.isUpload)

    fun decode(value: JSONObject): Track {
        val durationMs = when {
            value.has("durationMs") -> value.optLong("durationMs")
            else -> (value.optDouble("durationSeconds", 0.0) * 1000).toLong()
        }
        return Track(
            id = value.string("id"),
            title = value.string("title", "Unknown track"),
            // The web fills whichever of these its source populated; `artists` is an array there.
            artist = value.string("artist")
                .ifBlank { value.string("subtitle") }
                .ifBlank { value.optJSONArray("artists")?.optString(0).orEmpty().trim() }
                .ifBlank { "Unknown artist" },
            album = value.string("album"),
            albumId = value.string("albumId"),
            artistId = value.string("artistId"),
            artworkUrl = value.string("artworkUrl").ifBlank { value.string("thumbnail") },
            animatedArtworkUrl = value.string("animatedArtworkUrl"),
            animatedArtworkVerticalUrl = value.string("animatedArtworkVerticalUrl"),
            durationMs = durationMs.coerceAtLeast(0),
            explicit = value.optBoolean("explicit"),
            musicVideoType = value.string("musicVideoType"),
            autoplayGenerated = value.optBoolean("autoplayGenerated"),
            isUpload = value.optBoolean("isUpload"),
        )
    }

    fun encodeAll(tracks: List<Track>): JSONArray = JSONArray().also { array ->
        tracks.forEach { array.put(encode(it)) }
    }

    fun decodeAll(values: JSONArray?): List<Track> = buildList {
        if (values == null) return@buildList
        for (index in 0 until values.length()) {
            val decoded = values.optJSONObject(index)?.let(::decode) ?: continue
            if (decoded.id.isNotBlank()) add(decoded)
        }
    }

    /** JSON `null` arrives as the string "null" through org.json, which must not become a title. */
    private fun JSONObject.string(key: String, fallback: String = ""): String {
        if (isNull(key)) return fallback
        val value = optString(key, fallback).trim()
        return if (value.equals("null", ignoreCase = true)) fallback else value
    }
}

/** Encodes and decodes the `party:state` payload itself. */
object PartyStateJson {
    /** The web caps what it accepts at these lengths; sending more would only be dropped. */
    const val MAX_QUEUE = 100
    const val MAX_HISTORY = 30

    fun encode(state: PartyPlaybackState): JSONObject = JSONObject()
        .put("reason", state.reason)
        .put("track", state.track?.let(PartyTrackJson::encode) ?: JSONObject.NULL)
        .put("queue", PartyTrackJson.encodeAll(state.queue.take(MAX_QUEUE)))
        .put("history", PartyTrackJson.encodeAll(state.history.take(MAX_HISTORY)))
        .put("mediaKind", state.mediaKind)
        .put("isPlaying", state.isPlaying)
        .put("currentTime", state.currentTime)
        .put("duration", state.duration)
        .put("sentAt", state.sentAt)

    fun decode(value: JSONObject): PartyPlaybackState = PartyPlaybackState(
        reason = value.optString("reason", "manual"),
        track = value.optJSONObject("track")?.let(PartyTrackJson::decode)?.takeIf { it.id.isNotBlank() },
        queue = PartyTrackJson.decodeAll(value.optJSONArray("queue")).take(MAX_QUEUE),
        history = PartyTrackJson.decodeAll(value.optJSONArray("history")).take(MAX_HISTORY),
        mediaKind = value.optString("mediaKind", "audio"),
        isPlaying = value.optBoolean("isPlaying"),
        currentTime = value.optDouble("currentTime", 0.0).takeIf(Double::isFinite) ?: 0.0,
        duration = value.optDouble("duration", 0.0).takeIf(Double::isFinite) ?: 0.0,
        // A missing timestamp must read as "just now" rather than 1970, or the drift correction
        // below it would seek the guest to the end of the track.
        sentAt = value.optLong("sentAt").takeIf { it > 0 } ?: System.currentTimeMillis(),
    )
}

/**
 * Normalises a typed room code the same way the worker does: uppercase, then drop anything outside
 * `A-Z2-9`.
 *
 * Codes are *generated* from an alphabet that omits I, O, 0 and 1, but the filter deliberately
 * keeps I and O rather than stripping them. Removing a character shifts every one after it and
 * turns a one-letter typo into a different six-character code; leaving it in lets the lookup miss
 * and report "room not found", which is what actually happened.
 */
fun cleanRoomCode(value: String): String =
    value.trim().uppercase().filter { it in 'A'..'Z' || it in '2'..'9' }
