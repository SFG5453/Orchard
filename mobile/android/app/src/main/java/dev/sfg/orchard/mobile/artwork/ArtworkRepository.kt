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
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class TrackArtwork(
    val trackId: String,
    val staticUrl: String = "",
    val videoUrl: String = "",
    val videoUrlVertical: String = "",
) {
    val preferredVideoUrl: String get() = videoUrlVertical.ifBlank { videoUrl }
}

/**
 * Native port of Orchard desktop's ordered animated-artwork provider chain.
 *
 * Responses are accepted only when their title/artist/album identify the active
 * track. Provider errors are isolated so ordinary catalog artwork always wins
 * as a safe fallback. HLS URLs stay remote and are consumed by Media3 directly.
 */
class ArtworkRepository(
    http: OkHttpClient,
    private val spotifyCanvas: dev.sfg.orchard.mobile.spotify.SpotifyCanvasRepository? = null,
) {
    private val client: OkHttpClient = http.newBuilder()
        // The lookup only upgrades artwork after the fact, so it can afford to wait
        // for a cold provider instead of falling back to the song's video thumbnail.
        // fetch() blocks on an OkHttp call, so these, not any coroutine timeout, are what
        // actually bound a slow provider. The still cover is already on screen while we wait.
        .callTimeout(2_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .connectTimeout(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private data class Provider(val id: String, val baseUrl: String)
    private val cache = object : LinkedHashMap<String, TrackArtwork?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackArtwork?>?): Boolean = size > 128
    }

    suspend fun artwork(track: Track): TrackArtwork? = withContext(Dispatchers.IO) {
        val key = "${normalize(track.title)}::${normalize(track.artist)}::${normalize(track.album)}"
        if (key.startsWith("::") || track.title.isBlank() || track.artist.isBlank()) return@withContext null
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }
        val resolved = coroutineScope {
            // Canvas is asked in parallel so demoting it costs no latency, but it is only
            // consulted once the artwork providers come back without motion: a Canvas is a fixed
            // ~720p mp4 with no higher rendition, so letting it win on speed alone downgraded
            // covers that a provider would have served at a higher resolution.
            val canvas = spotifyCanvas?.let { canvasRepo ->
                async { runCatching { canvasRepo.getCanvas(track.title, track.artist) }.getOrNull() }
            }
            val fromProviders = raceProviders(track)
            if (fromProviders.hasMotion()) {
                canvas?.cancel()
                fromProviders
            } else {
                canvas?.await() ?: fromProviders
            }
        }
        synchronized(cache) { cache[key] = resolved }
        resolved
    }

    /**
     * First provider to answer wins, rather than strict priority order: they return equivalent
     * data, and waiting for a preferred provider that has stalled costs the whole read timeout
     * even when another already replied.
     */
    private suspend fun raceProviders(track: Track): TrackArtwork? = coroutineScope {
        val first = CompletableDeferred<TrackArtwork?>()
        val attempts = providers.map { provider ->
            launch { fetch(provider, track)?.let(first::complete) }
        }
        val watchdog = launch {
            attempts.joinAll()
            first.complete(null)
        }
        first.await().also {
            attempts.forEach(Job::cancel)
            watchdog.cancel()
        }
    }

    suspend fun artwork(detail: BrowseDetail): TrackArtwork? = withContext(Dispatchers.IO) {
        if (detail.title.isBlank()) return@withContext null
        // detail.subtitle is the descriptive line ("Album", "Playlist"), not the performer, so
        // querying with it missed every time and cost the full timeout on the slow providers.
        val artist = detail.artist.takeIf { it.isNotBlank() }
            ?: detail.tracks.firstOrNull { it.artist.isNotBlank() }?.artist.orEmpty()

        val albumQuery = Track(
            id = detail.id,
            title = detail.title,
            artist = artist,
            album = detail.title,
        )
        val titleTrack = detail.tracks.firstOrNull { it.title.equals(detail.title, ignoreCase = true) }
            ?: detail.tracks.firstOrNull()

        // Both lookups start together, but the album answer is taken as soon as it has motion;
        // waiting on the fallback as well meant a hung second request cost the whole read timeout
        // even when the album query had already succeeded.
        coroutineScope {
            val albumArt = async { artwork(albumQuery) }
            val fallback = async {
                titleTrack?.let { artwork(it.copy(album = it.album.ifBlank { detail.title })) }
            }

            val direct = albumArt.await()
            if (direct.hasMotion()) {
                fallback.cancel()
                direct
            } else {
                val trackArt = fallback.await()
                if (trackArt.hasMotion()) trackArt!!.copy(trackId = detail.id) else direct
            }
        }
    }

    private fun TrackArtwork?.hasMotion(): Boolean =
        this != null && (videoUrl.isNotBlank() || videoUrlVertical.isNotBlank())

    private fun fetch(provider: Provider, track: Track): TrackArtwork? = runCatching {
        val url = providerUrl(provider, track) ?: return null
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body.string()
            val root = runCatching { JSONObject(text) }.getOrElse {
                val values = JSONArray(text)
                values.optJSONObject(0) ?: return null
            }
            normalizeResponse(provider.id, root, track)
        }
    }.onFailure { Log.w(TAG, "Artwork provider ${provider.id} failed", it) }.getOrNull()

    private fun providerUrl(provider: Provider, track: Track): okhttp3.HttpUrl? {
        val base = provider.baseUrl.toHttpUrl().newBuilder()
        return when (provider.id) {
            "m8tec" -> {
                if (track.album.isBlank()) return null
                base.addPathSegments("api/v1/artwork/search")
                    .addQueryParameter("artist", track.artist)
                    .addQueryParameter("album", track.album)
                    .apply {
                        if (track.title.isNotBlank()) addQueryParameter("title", track.title)
                    }
                    .build()
            }
            else -> base
                .addQueryParameter("s", track.title)
                .addQueryParameter("a", track.artist)
                .apply {
                    if (provider.id == "orchard" && track.album.isNotBlank()) {
                        addQueryParameter("albumName", track.album)
                    }
                    if (provider.id == "orchard" && track.durationMs > 0) {
                        addQueryParameter("duration", (track.durationMs / 1_000).toString())
                    }
                }
                .build()
        }
    }

    internal fun normalizeResponse(providerId: String, root: JSONObject, track: Track): TrackArtwork? {
        if (root.has("error")) return null
        val accepted = if (providerId == "m8tec") {
            looseMatch(root.cleanString("artist"), track.artist) && looseMatch(root.cleanString("album"), track.album)
        } else {
            (exactMatch(root.cleanString("name"), track.title) || (track.album.isNotBlank() && exactMatch(root.cleanString("name"), track.album))) && looseMatch(root.cleanString("artist"), track.artist)
        }
        if (!accepted) return null
        val staticUrl = root.cleanString("static")
        val videoUrl = if (providerId == "m8tec") {
            root.cleanString("url").ifBlank { root.cleanString("animated") }
        } else {
            root.cleanString("animated").ifBlank { root.cleanString("videoUrl") }.ifBlank { root.cleanString("url") }
        }
        val videoUrlVertical = if (providerId == "m8tec") {
            root.cleanString("url_tall").ifBlank { root.cleanString("animatedVertical") }
        } else {
            root.cleanString("animatedVertical").ifBlank { root.cleanString("videoUrlVertical") }.ifBlank { root.cleanString("url_tall") }
        }
        if (staticUrl.isBlank() && videoUrl.isBlank() && videoUrlVertical.isBlank()) return null
        return TrackArtwork(track.id, staticUrl, videoUrl, videoUrlVertical)
    }

    private fun JSONObject.cleanString(key: String): String {
        if (isNull(key)) return ""
        val value = optString(key).trim()
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    private fun exactMatch(left: String, right: String): Boolean =
        normalize(left).isNotBlank() && normalize(left) == normalize(right)

    private fun looseMatch(left: String, right: String): Boolean {
        val first = normalize(left)
        val second = normalize(right)
        return first.isBlank() || second.isBlank() || first == second || first.contains(second) || second.contains(first)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace("&", " and ")
        .replace(NON_ALPHANUMERIC, " ")
        .trim()
        .replace(MULTIPLE_SPACES, " ")

    private companion object {
        val providers = listOf(
            Provider("m8tec", "https://artwork.m8tec.top/"),
            Provider("boidu", "https://artwork.boidu.dev/"),
            Provider("orchard", "https://artwork.sfg545.dev/"),
        )
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        val MULTIPLE_SPACES = Regex("\\s+")
        const val USER_AGENT = "Orchard Android/2.0"
        const val TAG = "ArtworkRepository"
    }
}
