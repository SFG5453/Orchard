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

package dev.sfg.orchard.mobile.spotify

import android.content.Context
import android.util.Log
import dev.sfg.orchard.mobile.artwork.TrackArtwork
import dev.sfg.orchard.mobile.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Handles Spotify access token harvesting, client token generation, track search,
 * and fetching Spotify Canvas animated artwork videos (.mp4).
 */
class SpotifyCanvasRepository(
    context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    private val harvester = SpotifyTokenHarvester(context)
    private var cachedAccessToken: String? = null
    private var accessTokenExpiresAt: Long = 0L
    private val harvestMutex = Mutex()

    private var cachedClientToken: String? = null
    private var clientTokenExpiresAt: Long = 0L

    private val canvasCache = object : LinkedHashMap<String, TrackArtwork?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackArtwork?>?): Boolean = size > 128
    }

    suspend fun getCanvas(title: String, artist: String): TrackArtwork? = withContext(Dispatchers.IO) {
        val currentSettings = settings.settings.value
        if (!currentSettings.spotifyCanvasEnabled) return@withContext null

        val spdc = extractSpdc(currentSettings.spotifySpdc)
        if (spdc.isBlank()) return@withContext null

        val cleanTitle = cleanText(title)
        val cleanArtist = cleanText(artist)
        if (cleanTitle.isBlank()) return@withContext null

        val cacheKey = "${cleanTitle.lowercase()}::${cleanArtist.lowercase()}"
        synchronized(canvasCache) {
            if (canvasCache.containsKey(cacheKey)) return@withContext canvasCache[cacheKey]
        }

        val result = resolveCanvas(spdc, cleanTitle, cleanArtist)
        synchronized(canvasCache) {
            canvasCache[cacheKey] = result
        }
        result
    }

    private suspend fun resolveCanvas(spdc: String, title: String, artist: String): TrackArtwork? {
        val accessToken = getAccessToken(spdc) ?: return null
        val clientToken = getClientToken() ?: ""

        val trackId = searchSpotifyTrackId(accessToken, title, artist, clientToken) ?: run {
            Log.w(TAG, "Spotify canvas: no track match for $title - $artist")
            return null
        }

        val canvasUrl = fetchSpotifyCanvasUrl(trackId, accessToken, clientToken) ?: return null

        return TrackArtwork(
            trackId = trackId,
            staticUrl = "",
            videoUrl = canvasUrl,
            videoUrlVertical = canvasUrl,
        )
    }

    private suspend fun getAccessToken(spdc: String): String? = harvestMutex.withLock {
        if (System.currentTimeMillis() + TOKEN_REFRESH_MARGIN_MS < accessTokenExpiresAt &&
            !cachedAccessToken.isNullOrBlank()
        ) {
            return@withLock cachedAccessToken
        }

        val harvested = harvester.harvestAccessToken(spdc)
        if (harvested != null) {
            cachedAccessToken = harvested.token
            accessTokenExpiresAt = harvested.expiresAt
        }
        harvested?.token
    }

    private fun getClientToken(): String? {
        if (System.currentTimeMillis() < clientTokenExpiresAt && !cachedClientToken.isNullOrBlank()) {
            return cachedClientToken
        }

        return runCatching {
            val jsonBody = JSONObject().apply {
                put("client_data", JSONObject().apply {
                    put("client_version", "1.2.46.25.g7f0cbf22")
                    put("client_id", "d8a5ed958d274c2e8ee717e6a4b0971d")
                    put("js_sdk_data", JSONObject().apply {
                        put("device_brand", "Apple")
                        put("device_model", "Macintosh")
                        put("os", "macOS")
                        put("os_version", "10.15.7")
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url("https://clienttoken.spotify.com/v1/clienttoken")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", SpotifyTokenHarvester.BROWSER_USER_AGENT)
                // Deliberately the ByteArray overload: the String overload rewrites the
                // Content-Type to "application/json; charset=utf-8", which clienttoken
                // rejects with a bare 400.
                .post(jsonBody.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Spotify client token rejected: ${response.code} ${response.body.string().take(300)}")
                    return null
                }
                val data = JSONObject(response.body.string())
                val token = data.optJSONObject("granted_token")?.optString("token")
                if (!token.isNullOrBlank()) {
                    cachedClientToken = token
                    clientTokenExpiresAt = System.currentTimeMillis() + 7100_000L
                    token
                } else null
            }
        }.onFailure { Log.w(TAG, "Spotify client token failed: ${it.message}") }.getOrNull()
    }

    private fun searchSpotifyTrackId(accessToken: String, title: String, artist: String, clientToken: String): String? {
        val searchTerm = "$title $artist".trim()
        val pathfinderId = searchViaPathfinder(searchTerm, accessToken, clientToken)
        if (pathfinderId != null) return pathfinderId

        return runCatching {
            val query = URLEncoder.encode(searchTerm, "UTF-8")
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/search?q=$query&type=track&limit=5")
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", SpotifyTokenHarvester.BROWSER_USER_AGENT)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Spotify search rejected: ${response.code} ${response.body.string().take(200)}")
                    return null
                }
                val data = JSONObject(response.body.string())
                val items = data.optJSONObject("tracks")?.optJSONArray("items") ?: return null
                if (items.length() == 0) return null

                val normTitle = title.lowercase().trim()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val name = item.optString("name").lowercase().trim()
                    if (name == normTitle || name.contains(normTitle) || normTitle.contains(name)) {
                        return item.optString("id")
                    }
                }
                items.optJSONObject(0)?.optString("id")
            }
        }.onFailure { Log.w(TAG, "Spotify search failed: ${it.message}") }.getOrNull()
    }

    private fun searchViaPathfinder(searchTerm: String, accessToken: String, clientToken: String): String? {
        return runCatching {
            val variables = JSONObject().apply {
                put("searchTerm", searchTerm)
                put("offset", 0)
                put("limit", 10)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includePreReleases", false)
            }.toString()

            val extensions = JSONObject().apply {
                put("persistedQuery", JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428")
                })
            }.toString()

            val url = "https://api-partner.spotify.com/pathfinder/v1/query"
                .toHttpUrl().newBuilder()
                .addQueryParameter("operationName", "searchTracks")
                .addQueryParameter("variables", variables)
                .addQueryParameter("extensions", extensions)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $accessToken")
                .header("Client-Token", clientToken)
                .header("App-platform", "WebPlayer")
                .header("User-Agent", SpotifyTokenHarvester.BROWSER_USER_AGENT)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Spotify pathfinder rejected: ${response.code} ${response.body.string().take(200)}")
                    return null
                }
                val root = JSONObject(response.body.string())
                val items = root.optJSONObject("data")
                    ?.optJSONObject("searchV2")
                    ?.optJSONObject("tracksV2")
                    ?.optJSONArray("items") ?: return null

                val firstItem = items.optJSONObject(0)?.optJSONObject("item")?.optJSONObject("data") ?: return null
                val id = firstItem.optString("id")
                if (id.isNotBlank()) return id

                val uri = firstItem.optString("uri")
                if (uri.isNotBlank()) uri.split(":").lastOrNull() else null
            }
        }.onFailure { Log.w(TAG, "Spotify pathfinder failed: ${it.message}") }.getOrNull()
    }

    private fun fetchSpotifyCanvasUrl(trackId: String, accessToken: String, clientToken: String): String? {
        return runCatching {
            val uri = "spotify:track:$trackId"
            val uriBytes = uri.toByteArray(Charsets.UTF_8)
            val inner = byteArrayOf(0x0a.toByte(), uriBytes.size.toByte()) + uriBytes
            val protobufBody = byteArrayOf(0x0a.toByte(), inner.size.toByte()) + inner

            val request = Request.Builder()
                .url("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")
                .header("Accept", "application/protobuf")
                .header("Content-Type", "application/protobuf")
                .header("Authorization", "Bearer $accessToken")
                .header("Client-Token", clientToken)
                .header("User-Agent", CANVAS_USER_AGENT)
                .post(protobufBody.toRequestBody("application/protobuf".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Spotify canvaz rejected: ${response.code} ${response.body.string().take(200)}")
                    return null
                }
                val bytes = response.body.bytes()
                val text = String(bytes, Charsets.ISO_8859_1)
                CANVAS_URL_REGEX.find(text)?.value.also {
                    // An empty body is a legitimate answer: most tracks have no canvas.
                    if (it == null) Log.i(TAG, "Spotify canvas: no video for $trackId (${bytes.size} byte response)")
                }
            }
        }.onFailure { Log.w(TAG, "Spotify canvas fetch failed: ${it.message}") }.getOrNull()
    }

    companion object {
        const val CANVAS_USER_AGENT = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
        private val CANVAS_URL_REGEX = Regex("https://[^\"'\\s\\x00-\\x1F]+\\.cnvs\\.mp4")
        private const val TAG = "SpotifyCanvasRepository"
        private const val TOKEN_REFRESH_MARGIN_MS = 60_000L

        fun extractSpdc(cookieInput: String): String {
            val str = cookieInput.trim()
            if (str.isBlank()) return ""
            if (!str.contains("=")) return str
            val match = Regex("(?:^|;\\s*)sp_dc=([^;]+)").find(str)
            return match?.groupValues?.get(1)?.trim() ?: ""
        }

        private fun cleanText(value: String): String =
            value.trim().replace(Regex("\\s+"), " ").take(500)
    }
}
