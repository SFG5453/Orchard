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

package dev.sfg.orchard.mobile.discord

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Handles pre-warming animated artwork through Orchard's GIF proxy
 * and registering external image assets with Discord's REST API.
 */
class DiscordAssetRegistrar(http: OkHttpClient) {
    private val client: OkHttpClient = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val assetCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 100
        }
    )

    private val warmCache: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 100
        }
    )

    /**
     * Resolves the optimal Discord image key:
     * 1. If animated artwork is available, pre-warms the GIF and registers it with Discord.
     * 2. Otherwise registers or normalizes the static artwork URL.
     */
    suspend fun resolveArtworkAsset(
        accessToken: String,
        staticArtworkUrl: String?,
        animatedArtworkUrl: String?,
    ): String? = withContext(Dispatchers.IO) {
        val animatedProxyUrl = animatedArtworkUrl?.let(::discordAnimatedArtworkUrl).orEmpty()
        if (animatedProxyUrl.isNotBlank()) {
            val warmed = warmAnimatedArtwork(animatedProxyUrl)
            if (warmed) {
                val registeredAnimated = registerExternalAsset(accessToken, animatedProxyUrl)
                if (!registeredAnimated.isNullOrBlank()) {
                    return@withContext registeredAnimated
                }
            }
        }

        val staticUrl = normalizeDiscordImageUrl(staticArtworkUrl)
        if (staticUrl.isNotBlank()) {
            // A bare https URL is not a valid image key, so returning one here would
            // render as a broken-image placeholder. No key at all looks better.
            return@withContext registerExternalAsset(accessToken, staticUrl)
        }

        null
    }

    /**
     * Pre-warms the GIF conversion proxy at artwork-proxy.sfg545.dev.
     */
    suspend fun warmAnimatedArtwork(proxyGifUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (proxyGifUrl.isBlank()) return@withContext false
        val now = System.currentTimeMillis()
        val cachedAt = warmCache[proxyGifUrl]
        if (cachedAt != null && now - cachedAt < 300_000L) {
            return@withContext true
        }

        val request = Request.Builder()
            .url(proxyGifUrl)
            .header("Accept", "image/gif")
            .header("User-Agent", "Orchard Mobile Discord RPC")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    warmCache[proxyGifUrl] = now
                    true
                } else {
                    Log.w(TAG, "Artwork proxy returned HTTP ${response.code} for $proxyGifUrl")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not prepare animated Discord artwork: ${e.message}")
            false
        }
    }

    /**
     * Registers an external URL with Discord's external-assets API and returns an `mp:...` asset key.
     */
    suspend fun registerExternalAsset(accessToken: String, imageUrl: String): String? = withContext(Dispatchers.IO) {
        val normalized = normalizeDiscordUrl(imageUrl)
        if (normalized.isBlank() || accessToken.isBlank()) return@withContext null

        assetCache[normalized]?.let { return@withContext it }

        val payload = JSONObject().apply {
            val urls = JSONArray()
            urls.put(normalized)
            put("urls", urls)
        }

        val request = Request.Builder()
            .url("https://discord.com/api/v10/applications/$DISCORD_APPLICATION_ID/external-assets")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Discord external-assets API returned HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body.string()
                val array = JSONArray(body)
                if (array.length() > 0) {
                    val first = array.getJSONObject(0)
                    val assetPath = first.optString("external_asset_path")
                    if (assetPath.isNotBlank()) {
                        val assetKey = "mp:$assetPath"
                        assetCache[normalized] = assetKey
                        return@withContext assetKey
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register external asset with Discord: ${e.message}")
            null
        }
    }

    fun clearCache() {
        assetCache.clear()
        warmCache.clear()
    }

    companion object {
        private const val TAG = "DiscordAssetRegistrar"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
