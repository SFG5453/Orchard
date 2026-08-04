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

package dev.sfg.orchard.mobile.artwork

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Portrait and wide imagery for an artist, either field blank when TheAudioDB has none. */
data class ArtistImages(val portraitUrl: String = "", val wideUrl: String = "")

/**
 * Artist imagery from TheAudioDB, ported from orchardv2's audioDb.js.
 *
 * YouTube's channel avatars are small and often a logo rather than the artist, so artist pages
 * read better with a real photograph. v2 only took the wide fanart; the portrait is what stands in
 * for the avatar here, with fanart kept for anything that wants a banner.
 */
class ArtistImageRepository(http: OkHttpClient) {
    private val client = http.newBuilder()
        .callTimeout(4_000, TimeUnit.MILLISECONDS)
        .connectTimeout(2_500, TimeUnit.MILLISECONDS)
        .readTimeout(3_000, TimeUnit.MILLISECONDS)
        .build()

    private val cache = object : LinkedHashMap<String, ArtistImages?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtistImages?>?): Boolean =
            size > 64
    }

    suspend fun images(artistName: String): ArtistImages? = withContext(Dispatchers.IO) {
        val name = artistName.trim()
        if (name.isBlank()) return@withContext null
        val key = name.lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }

        val resolved = runCatching { fetch(name) }
            .onFailure { Log.w(TAG, "Artist imagery lookup failed for $name", it) }
            .getOrNull()
        synchronized(cache) { cache[key] = resolved }
        resolved
    }

    private fun fetch(artistName: String): ArtistImages? {
        val url = "https://www.theaudiodb.com/api/v1/json/2/search.php".toHttpUrl()
            .newBuilder()
            .addQueryParameter("s", artistName)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body.string().ifBlank { return null }
            val artist = JSONObject(body).optJSONArray("artists")?.optJSONObject(0) ?: return null

            val portrait = artist.field("strArtistThumb").ifBlank { artist.field("strArtistCutout") }
            // Same preference order v2 used for the wide art.
            val wide = artist.field("strArtistFanart")
                .ifBlank { artist.field("strArtistFanart2") }
                .ifBlank { artist.field("strArtistFanart3") }
                .ifBlank { artist.field("strArtistBanner") }

            if (portrait.isBlank() && wide.isBlank()) return null
            return ArtistImages(portraitUrl = portrait, wideUrl = wide)
        }
    }

    /** TheAudioDB returns the JSON literal "null" for absent fields as well as empty strings. */
    private fun JSONObject.field(name: String): String =
        optString(name).takeUnless { it.isBlank() || it.equals("null", true) }.orEmpty()

    private companion object {
        const val TAG = "ArtistImages"
        const val USER_AGENT = "Orchard Android/2.0"
    }
}
