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

import android.util.Log
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.qobuz.RemoteQobuz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Desktop's answer to ResolveArtwork: a larger cover and, when one exists, a looping video. */
data class ConnectArtwork(val staticUrl: String, val animatedUrl: String)

/**
 * Provider and artwork work across Connect. Credentials never cross: a peer gets match results,
 * opaque playback ids and decrypted bytes, and asks this phone only for what its session holds.
 */
internal class ConnectProviders(
    private val connect: ConnectRepository,
    private val graph: OrchardGraph,
) : RemoteQobuz {
    // Bytes that arrive ahead of their rpc_result, by request id.
    private val rangeBytes = ConcurrentHashMap<String, ByteArray>()

    init {
        graph.qobuzResolver.remote = this
    }

    // ── This phone as Provider Host ───────────────────────────────────────────────────────────

    fun serve(event: JSONObject) {
        val id = event.optString("id")
        val method = event.optString("method")
        val params = event.optJSONObject("params") ?: JSONObject()
        val sessionId = event.optString("session_id")
        if (params.optString("provider") != "qobuz") {
            connect.respond(id, false, error = "unsupported")
            return
        }
        val resolver = graph.qobuzResolver
        if (!resolver.hasSession()) {
            connect.respond(id, false, error = "provider_unavailable")
            return
        }
        graph.applicationScope.launch(Dispatchers.IO) {
            runCatching {
                when (method) {
                    "ResolveTrack" -> connect.respond(id, true, resolver.resolveSource(providerTrack(params.optJSONObject("track"))))
                    "ReadRange" -> {
                        val start = params.optLong("start", -1)
                        val end = params.optLong("end", -1)
                        if (start < 0 || end < start || end - start >= MAX_RANGE_BYTES) {
                            connect.respond(id, false, error = "invalid_request")
                            return@runCatching
                        }
                        val bytes = resolver.readRange(params.optString("playback_id"), start, end)
                        // The data frame goes first on the same ordered link as the result.
                        connect.sendData(sessionId, JSONObject().put("kind", "range").put("rpc", id), bytes)
                        connect.respond(id, true, JSONObject().put("length", bytes.size))
                    }
                    "PlaybackReport" -> {
                        resolver.report(params.optString("playback_id"), params.optBoolean("started"), params.optDouble("position", 0.0))
                        connect.respond(id, true)
                    }
                    else -> connect.respond(id, false, error = "unsupported")
                }
            }.onFailure {
                Log.w(TAG, "Serving $method failed", it)
                connect.respond(id, false, error = it.message.orEmpty().take(128).ifBlank { "failed" })
            }
        }
    }

    // The wire track (ConnectWire) in the shape the Qobuz provider matches on.
    private fun providerTrack(wire: JSONObject?): JSONObject {
        val track = ConnectWire.track(wire) ?: return JSONObject()
        return JSONObject()
            .put("id", track.id)
            .put("title", track.title)
            .put("artist", track.artist)
            .put("artists", JSONArray(track.artists.map { it.name }.ifEmpty { listOf(track.artist) }))
            .put("album", track.album)
            .put("durationSeconds", track.durationMs / 1000.0)
            .put("explicit", track.explicit)
    }

    fun onData(header: JSONObject, payload: ByteArray) {
        if (header.optString("kind") == "range") rangeBytes[header.optString("rpc")] = payload
    }

    // ── This phone borrowing a peer's Qobuz session ───────────────────────────────────────────

    override fun available(): Boolean = connect.roleHost("provider:qobuz") != null

    override suspend fun resolve(track: JSONObject): JSONObject? {
        val wire = ConnectWire.track(
            Track(
                id = track.optString("id"),
                title = track.optString("title"),
                artist = track.optString("artist"),
                album = track.optString("album"),
                durationMs = (track.optDouble("durationSeconds", 0.0) * 1000).toLong(),
                explicit = track.optBoolean("explicit"),
            ),
        )
        return connect.request("provider:qobuz", "ResolveTrack", JSONObject().put("provider", "qobuz").put("track", wire))
            ?.optJSONObject("result")
    }

    override suspend fun read(playbackId: String, start: Long, end: Long): ByteArray {
        val params = JSONObject().put("provider", "qobuz").put("playback_id", playbackId).put("start", start).put("end", end)
        val reply = connect.request("provider:qobuz", "ReadRange", params) ?: return ByteArray(0)
        return rangeBytes.remove(reply.optString("id")) ?: ByteArray(0)
    }

    override fun report(playbackId: String, started: Boolean, position: Double) {
        graph.applicationScope.launch {
            connect.request(
                "provider:qobuz", "PlaybackReport",
                JSONObject().put("provider", "qobuz").put("playback_id", playbackId).put("started", started).put("position", position),
            )
        }
    }

    // ── Artwork from the desktop ──────────────────────────────────────────────────────────────

    /** The Artwork Host's cover for [track], or null when this phone is its own artwork host. */
    suspend fun artwork(track: Track): ConnectArtwork? {
        if (connect.roleHost("artwork") == null) return null
        val params = JSONObject()
            .put("kind", "track")
            .put("title", track.title)
            .put("artist", track.artist)
            .put("album", track.album)
            .put("artwork", track.artworkUrl)
        val result = connect.request("artwork", "ResolveArtwork", params, timeoutMs = 15_000)?.optJSONObject("result")
            ?: return null
        return ConnectArtwork(result.optString("static_url"), result.optString("animated_url"))
    }

    private companion object {
        const val TAG = "OrchardConnect"
        const val MAX_RANGE_BYTES = 4L * 1024 * 1024
    }
}
