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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import kotlin.math.max

private const val TAG = "QobuzResolver"

data class ResolvedQobuzTrack(
    val playbackId: String,
    val qobuzTrackId: Long,
    val streamUrl: String,
    val bitrateKbps: Int,
    val bitDepth: Int,
    val sampleRate: Int,
    val hires: Boolean,
    val durationSeconds: Int,
)

class QobuzResolver(
    private val repository: QobuzRepository,
    private val httpClient: OkHttpClient = OkHttpClient(),
    val bootstrapLoader: QobuzBootstrapLoader = QobuzBootstrapLoader(httpClient),
    val client: QobuzClient = QobuzClient(bootstrapLoader, { repository.getCredentials() }, httpClient),
    val matcher: QobuzMatcher = QobuzMatcher(client),
    val streamServer: QobuzStreamServer = QobuzStreamServer(httpClient),
) {
    suspend fun isAvailable(): Boolean {
        val status = repository.status.value
        return status.enabled && repository.getCredentials() != null
    }

    suspend fun resolve(
        title: String,
        artists: List<String>,
        album: String = "",
        durationMs: Long = 0,
        isrc: String = "",
        explicit: Boolean = false,
    ): ResolvedQobuzTrack? = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext null
        try {
            val match = matcher.match(
                title = title,
                artists = artists,
                album = album,
                durationMs = durationMs,
                isrc = isrc,
                explicit = explicit,
            ) ?: return@withContext null

            Log.i(TAG, "Matched track '$title' -> Qobuz track ID ${match.qobuzTrackId}")
            val quality = repository.status.value.quality
            val streamingInfo = client.streamingInfo(match.qobuzTrackId, quality)

            val initUrl = streamingInfo.urlTemplate.replace("\$SEGMENT\$", "0")
            val initRequest = Request.Builder()
                .url(initUrl)
                .header("User-Agent", QOBUZ_USER_AGENT)
                .header("Accept", "*/*")
                .build()
            val initBytes = httpClient.newCall(initRequest).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Failed to load init segment (${response.code})")
                response.body.bytes()
            }

            val parsedInit = parseQobuzInitSegment(initBytes)

            val contentKey = if (streamingInfo.key.isNotBlank()) {
                val sessionKey = deriveQobuzSessionKey(streamingInfo.session.infos, streamingInfo.rngInit)
                unwrapQobuzContentKey(sessionKey, streamingInfo.key)
            } else null

            val playbackId = UUID.randomUUID().toString()
            val durationSec = if (streamingInfo.durationSeconds > 0) streamingInfo.durationSeconds else match.durationSeconds
            val totalBytes = parsedInit.totalLength

            val bitrateKbps = if (durationSec > 0 && totalBytes > 0) {
                ((totalBytes * 8L) / (durationSec.toLong() * 1000L)).toInt()
            } else {
                (parsedInit.sampleRate * parsedInit.bitDepth * parsedInit.channels) / 1000
            }

            val source = QobuzStreamSource(
                playbackId = playbackId,
                trackId = match.qobuzTrackId,
                quality = quality,
                formatId = streamingInfo.formatId,
                durationSeconds = durationSec,
                blob = streamingInfo.blob,
                trackContextUuid = UUID.randomUUID().toString(),
                urlTemplate = streamingInfo.urlTemplate,
                contentKey = contentKey,
                expiresAtMs = System.currentTimeMillis() + 4 * 3600_000L,
                bitDepth = parsedInit.bitDepth,
                sampleRate = parsedInit.sampleRate,
                channels = parsedInit.channels,
                hires = match.hires || parsedInit.bitDepth > 16 || parsedInit.sampleRate > 48000,
                bitrateKbps = bitrateKbps,
                totalBytes = totalBytes,
            )

            val session = QobuzPlaybackSession(
                playbackId = playbackId,
                source = source,
                init = parsedInit,
                httpClient = httpClient,
                contentKey = contentKey,
            )

            streamServer.registerSession(session)
            val streamUrl = streamServer.urlFor(playbackId)

            // Report start
            val creds = repository.getCredentials()
            if (creds != null) {
                client.reportStreamingStart(
                    trackId = match.qobuzTrackId,
                    startedAtUnix = System.currentTimeMillis() / 1000L,
                    formatId = streamingInfo.formatId,
                    userId = creds.userId,
                )
            }

            ResolvedQobuzTrack(
                playbackId = playbackId,
                qobuzTrackId = match.qobuzTrackId,
                streamUrl = streamUrl,
                bitrateKbps = bitrateKbps,
                bitDepth = parsedInit.bitDepth,
                sampleRate = parsedInit.sampleRate,
                hires = source.hires,
                durationSeconds = durationSec,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve Qobuz track: ${e.message}", e)
            repository.setLastError(e.message ?: "Failed to resolve Qobuz stream")
            null
        }
    }

    fun release() {
        streamServer.stop()
        matcher.clear()
        client.reset()
    }
}
