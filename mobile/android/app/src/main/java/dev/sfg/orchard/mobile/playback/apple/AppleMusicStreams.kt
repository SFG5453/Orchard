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

package dev.sfg.orchard.mobile.playback.apple

import android.util.Log
import dev.sfg.orchard.mobile.applemusic.AppleMusicCatalog
import dev.sfg.orchard.mobile.applemusic.AppleMusicException
import dev.sfg.orchard.mobile.catalog.isAppleMusicId
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Apple Music songs to [AppleMusicStream]s and licenses their keys. Every call
 * blocks, so only Media3 loader, DRM and analysis threads use it.
 */
internal class AppleMusicStreams(
    private val catalog: AppleMusicCatalog,
    private val http: OkHttpClient,
    /** Null while Apple songs may play, else what the listener should do first. */
    private val blocked: () -> String?,
    /** True while the quality setting asks for Apple's 64 kbps encode. */
    private val standard: () -> Boolean = { false },
) {
    private val streams = ConcurrentHashMap<String, AppleMusicStream>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun stream(sourceId: String): AppleMusicStream {
        cached(sourceId)?.let { return it }
        synchronized(locks.computeIfAbsent(sourceId) { Any() }) {
            cached(sourceId)?.let { return it }
            blocked()?.let { throw AppleMusicPlaybackException(it) }
            val wantStandard = standard()
            val reply = apple { catalog.stream(sourceId, wantStandard) }
            val stream = try {
                AppleMusicStream.parse(reply, STREAM_LIFETIME_MS)
            } catch (error: Exception) {
                Log.w(TAG, "Unusable stream for $sourceId", error)
                throw AppleMusicPlaybackException("Apple Music returned a stream Orchard cannot play.", error)
            }
            stream.standard = wantStandard
            stream.head = stream.buildHead(init(stream))
                ?: throw AppleMusicPlaybackException("Apple Music returned a stream Orchard cannot play.")
            Log.i(TAG, "Resolved $sourceId (${reply.optString("flavor")}, ${stream.bitrateKbps} kbps)")
            streams[sourceId] = stream
            return stream
        }
    }

    fun cached(sourceId: String): AppleMusicStream? {
        val stream = streams[sourceId] ?: return null
        if (stream.expiresAtMs > System.currentTimeMillis() && stream.standard == standard()) return stream
        streams.remove(sourceId, stream)
        return null
    }

    /** Apple's license for a Widevine key request made for [sourceId]'s key. */
    fun license(sourceId: String, challenge: ByteArray): ByteArray {
        val stream = stream(sourceId)
        return apple { catalog.license(stream.source, challenge) }.also {
            if (it.isEmpty()) throw AppleMusicPlaybackException("Apple Music did not license this song.")
        }
    }

    fun forget(sourceId: String) {
        streams.remove(sourceId)
    }

    fun clear() {
        streams.clear()
        locks.clear()
    }

    private fun init(stream: AppleMusicStream): ByteArray {
        val (start, size) = stream.ranges.first()
        val request = Request.Builder().url(stream.media).header("Range", "bytes=$start-${start + size - 1}").build()
        http.newCall(request).execute().use { response ->
            val bytes = response.body.bytes()
            if (!response.isSuccessful || bytes.size.toLong() != size) {
                throw IOException("Apple Music's init segment failed (HTTP ${response.code}, ${bytes.size} bytes)")
            }
            return bytes
        }
    }

    private fun <T> apple(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (error: AppleMusicException) {
        throw AppleMusicPlaybackException(error.message ?: "Apple Music could not play this song.", error)
    } catch (error: IllegalStateException) {
        // The catalog's sign-in checks.
        throw AppleMusicPlaybackException(error.message ?: "Connect Apple Music in Settings to play this song.", error)
    }

    companion object {
        private const val TAG = "OrchardAppleMusic"
        /** Apple's media URLs outlive this; a fresh resolve costs one request. */
        private const val STREAM_LIFETIME_MS = 60 * 60_000L

        fun isAppleSource(sourceId: String) = isAppleMusicId(sourceId)
    }
}
