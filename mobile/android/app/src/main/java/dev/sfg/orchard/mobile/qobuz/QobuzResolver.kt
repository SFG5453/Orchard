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

package dev.sfg.orchard.mobile.qobuz

import android.util.Log
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.QobuzAlbumQuality
import dev.sfg.orchard.mobile.provider.ProviderBundle
import dev.sfg.orchard.mobile.provider.ProviderHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "QobuzResolver"

data class ResolvedQobuzTrack(
    val streamUrl: String,
    val bitrateKbps: Int,
    val bitDepth: Int,
    val sampleRate: Int,
    val hires: Boolean,
)

/** Matches tracks and resolves FLAC streams through the desktop provider (`providers/qobuz`). */
class QobuzResolver(
    private val repository: QobuzRepository,
    http: OkHttpClient,
    bundle: (String) -> ByteArray?,
) {
    private val provider = ProviderHost(http, ProviderBundle.Qobuz, bundle)
    private val streamServer = QobuzStreamServer(provider)
    private val sessionLock = Mutex()
    private var syncedSession: QobuzSession? = null

    /** Set by Orchard Connect; used when another device holds the only Qobuz session. */
    @Volatile var remote: RemoteQobuz? = null

    // A linked local account's off switch also disables borrowed sessions on this phone.
    fun isAvailable(): Boolean = if (hasSession()) localAvailable() else remote?.available() == true

    private fun localAvailable(): Boolean = repository.status.value.enabled && repository.getCredentials() != null

    /** A subscription is linked here or on a Connect peer. */
    fun isLinked(): Boolean = hasSession() || remote?.available() == true

    private val albumQualities = object : LinkedHashMap<String, QobuzAlbumQuality?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QobuzAlbumQuality?>?) = size > 200
    }

    /** Hi-Res or Lossless for the album Qobuz matches, or null when unmatched or unavailable. */
    suspend fun albumQuality(detail: BrowseDetail): QobuzAlbumQuality? = withContext(Dispatchers.IO) {
        if (!localAvailable() || detail.title.isBlank() || detail.artist.isBlank()) return@withContext null
        val key = "${detail.title.lowercase()}\n${detail.artist.lowercase()}"
        synchronized(albumQualities) { if (albumQualities.containsKey(key)) return@withContext albumQualities[key] }
        try {
            syncSession()
            val result = provider.invokeValue(
                "album.quality",
                JSONObject()
                    .put("title", detail.title)
                    .put("artist", detail.artist)
                    .put("year", detail.year.take(4).toIntOrNull() ?: 0)
                    .put("trackCount", detail.tracks.size)
                    .put("quality", repository.status.value.quality.id),
            ) as? JSONObject
            val quality = result?.optString("tier")?.takeIf { it.isNotBlank() }?.let {
                QobuzAlbumQuality(it == "hires", result.optInt("bitDepth"), result.optInt("sampleRate"))
            }
            synchronized(albumQualities) { albumQualities[key] = quality }
            quality
        } catch (e: Exception) {
            Log.w(TAG, "Qobuz album quality lookup failed: ${e.message}")
            null
        }
    }

    /** Signed in here, so this phone can serve a Connect peer whatever its own toggle says. */
    fun hasSession(): Boolean = repository.getCredentials() != null

    /** Null when Qobuz is off, has no confident match, or fails; the caller falls back. */
    suspend fun resolve(
        id: String = "",
        title: String,
        artists: List<String>,
        album: String = "",
        durationMs: Long = 0,
        isrc: String = "",
        explicit: Boolean = false,
    ): ResolvedQobuzTrack? = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext null
        try {
            val track = JSONObject()
                .put("id", id)
                .put("title", title)
                .put("artist", artists.firstOrNull().orEmpty())
                .put("artists", JSONArray(artists))
                .put("album", album)
                .put("durationSeconds", durationMs / 1000.0)
                .put("explicit", explicit)
                .put("isrc", isrc)
            val borrowed = if (localAvailable()) null else remote
            val result = if (borrowed != null) {
                borrowed.resolve(track) ?: return@withContext null
            } else {
                resolveSource(track)
            }
            val source = result.optJSONObject("source")
            if (source == null) {
                Log.i(TAG, "No Qobuz match for '$title': ${result.optJSONObject("miss")}")
                return@withContext null
            }
            toResolved(source, result.getJSONObject("match"), borrowed)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve Qobuz track: ${e.message}", e)
            repository.setLastError(e.message ?: "Failed to resolve Qobuz stream")
            null
        }
    }

    private fun toResolved(source: JSONObject, match: JSONObject, remote: RemoteQobuz?): ResolvedQobuzTrack {
        val totalBytes = source.getLong("totalBytes")
        val seconds = source.optDouble("durationSeconds", 0.0)
        val bitDepth = source.optInt("bitDepth")
        val sampleRate = source.optInt("sampleRate")
        val playbackId = source.getString("playbackId")
        return ResolvedQobuzTrack(
            streamUrl = streamServer.register(playbackId, totalBytes, remote),
            bitrateKbps = if (seconds > 0) (totalBytes * 8 / seconds / 1000).toInt() else 0,
            bitDepth = bitDepth,
            sampleRate = sampleRate,
            hires = match.optBoolean("hires") || bitDepth > 16 || sampleRate > 48000,
        )
    }

    /** The provider's raw match for a catalog track; also what a Connect peer receives. */
    suspend fun resolveSource(track: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        syncSession()
        provider.invoke(
            "playback.resolve",
            JSONObject().put("track", track).put("quality", repository.status.value.quality.id),
        )
    }

    /** Decrypted bytes of a stream this phone resolved, for a Connect peer. */
    suspend fun readRange(playbackId: String, start: Long, end: Long): ByteArray =
        provider.invokeBytes("playback.read", JSONObject().put("playbackId", playbackId).put("start", start).put("end", end))

    suspend fun report(playbackId: String, started: Boolean, position: Double) {
        provider.invokeRaw(
            if (started) "playback.started" else "playback.ended",
            JSONObject().put("playbackId", playbackId).put("position", position),
        )
    }

    // The provider holds the token; re-send it only when the stored session changes.
    private suspend fun syncSession() {
        sessionLock.withLock {
            val session = repository.getCredentials()
            if (session == syncedSession) return
            provider.invoke(
                "session.set",
                JSONObject().put("token", session?.token.orEmpty()).put("userId", session?.userId ?: 0L),
            )
            syncedSession = session
        }
    }

    /** The Qobuz sign-in page that redirects to [redirectUrl] with an authorization code. */
    suspend fun authorizationUrl(redirectUrl: String): String =
        provider.invoke("oauth.start", JSONObject().put("redirectUrl", redirectUrl)).getString("url")

    suspend fun exchangeCode(code: String): QobuzSession {
        val account = provider.invoke("oauth.finish", JSONObject().put("code", code))
        return QobuzSession(account.getString("token"), account.getLong("userId"))
    }

    fun release() {
        streamServer.stop()
        provider.cancelAll()
    }
}
