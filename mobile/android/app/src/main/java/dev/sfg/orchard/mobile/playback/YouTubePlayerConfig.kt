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

package dev.sfg.orchard.mobile.playback

import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The signature timestamp the web-shaped player clients have to quote.
 *
 * YouTube rotates its player script every week or so, and a request quoting the
 * timestamp of an older one is refused outright with "The page needs to be reloaded"
 * — no formats, no explanation of what is actually stale. Pinning the value therefore
 * works only until the next rotation and then fails totally and silently, so it is
 * read from the live player instead and only falls back to a constant when the
 * network will not give one up.
 */
internal class YouTubePlayerConfig(private val client: OkHttpClient) {
    private data class Cached(val timestamp: Int, val atMs: Long)

    @Volatile private var cached: Cached? = null
    private val lock = Any()

    /**
     * The current timestamp, fetched at most once per [CACHE_TTL_MS] and shared by
     * every player client. Falls back to the last known-good constant, which is worth
     * trying: it is right until YouTube next rotates, and a refused request is no
     * worse than the no request we could otherwise make.
     */
    fun signatureTimestamp(): Int {
        cached?.let { if (System.currentTimeMillis() - it.atMs < CACHE_TTL_MS) return it.timestamp }
        synchronized(lock) {
            cached?.let { if (System.currentTimeMillis() - it.atMs < CACHE_TTL_MS) return it.timestamp }
            val fetched = runCatching { fetchTimestamp() }
                .onFailure { Log.w(TAG, "Could not read the player signature timestamp", it) }
                .getOrNull()
            if (fetched != null) {
                Log.i(TAG, "Player signature timestamp is $fetched")
                cached = Cached(fetched, System.currentTimeMillis())
                return fetched
            }
            return cached?.timestamp ?: FALLBACK_SIGNATURE_TIMESTAMP
        }
    }

    /**
     * Drops the cached value after YouTube has rejected it, so the next attempt pays
     * for a fresh read rather than repeating a timestamp already known to be refused.
     */
    fun invalidate() {
        synchronized(lock) { cached = null }
    }

    private fun fetchTimestamp(): Int? {
        val playerId = fetchPlayerId() ?: return null
        val url = "https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_US/base.js"
        val request = Request.Builder().url(url).header("User-Agent", DESKTOP_USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // The script runs to several megabytes, so it is scanned as it arrives and
            // abandoned at the match rather than held in memory in one piece.
            return response.body.charStream().buffered().use { reader ->
                val buffer = CharArray(READ_CHUNK)
                // Carrying the tail of each chunk forward keeps a match that straddles
                // a boundary from being missed.
                var carry = ""
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) return@use null
                    val text = carry + String(buffer, 0, read)
                    TIMESTAMP_PATTERN.find(text)?.let { match ->
                        return@use match.groupValues[1].toIntOrNull()
                    }
                    carry = text.takeLast(CARRY_OVER)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        }
    }

    private fun fetchPlayerId(): String? {
        val request = Request.Builder()
            .url("https://www.youtube.com/iframe_api")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return PLAYER_ID_PATTERN.find(response.body.string())?.groupValues?.get(1)
        }
    }

    companion object {
        private const val TAG = "YouTubePlayerConfig"

        /**
         * The timestamp that was live when this was written. Only reached when the
         * player cannot be read at all, and stops being right at the next rotation.
         */
        const val FALLBACK_SIGNATURE_TIMESTAMP = 20668

        private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(6)
        private const val READ_CHUNK = 64 * 1024
        private const val CARRY_OVER = 64
        private val TIMESTAMP_PATTERN = Regex("""signatureTimestamp:(\d+)""")
        private val PLAYER_ID_PATTERN = Regex("""/player/([0-9a-zA-Z_-]+)/""")
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
