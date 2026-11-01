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

package dev.sfg.orchard.mobile.lyrics

import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.LyricsProvider
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Fetches lyrics from individually selectable providers. */
class LyricsRepository(
    http: OkHttpClient,
    private val appleLyrics: suspend (Track) -> String = { "" },
    private val appleTranslation: suspend (Track, String) -> Map<String, String> = { _, _ -> emptyMap() },
    /** Plain lyrics from YouTube Music's Lyrics tab, via the provider's `lyrics.youtube`. */
    private val youtubeLyrics: suspend (videoId: String) -> String,
) {
    private data class Metadata(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val videoId: String,
    )

    private val providerHttp = http.newBuilder().callTimeout(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val resolver = LyricsProviderResolver(::fetch)
    private val mutableOverrides = MutableStateFlow<Map<String, LyricsProvider>>(emptyMap())
    val providerOverrides = mutableOverrides.asStateFlow()
    private val mutableSources = MutableStateFlow<Map<String, LyricsProvider>>(emptyMap())
    val sources = mutableSources.asStateFlow()
    private val appleTracks = LinkedHashMap<String, Track>()

    /** A source choice for this song, kept for the current app session. Null restores the saved order. */
    fun selectProvider(trackId: String, provider: LyricsProvider?) {
        if (trackId.isBlank()) return
        mutableOverrides.update { current ->
            if (provider == null) current - trackId
            else (current - trackId + (trackId to provider)).entries.toList().takeLast(32).associate { it.toPair() }
        }
    }

    suspend fun lyrics(track: Track, providers: List<LyricsProvider> = LyricsProvider.entries): List<LyricLine> =
        withContext(Dispatchers.IO) {
            mutableSources.update { it - track.id }
            val result = resolver.resolve(track, providers)
            result.provider?.let { provider ->
                if (provider == LyricsProvider.APPLE) synchronized(appleTracks) {
                    appleTracks.remove(track.id)
                    appleTracks[track.id] = track
                    while (appleTracks.size > 32) appleTracks.remove(appleTracks.keys.first())
                }
                mutableSources.update { (it - track.id + (track.id to provider)).entries.toList().takeLast(32).associate { it.toPair() } }
            }
            result.lines
        }

    /** Apple's translation of the lyrics shown for [trackId] as `{line key: text}`; empty when they are not Apple's. */
    suspend fun appleTranslation(trackId: String, language: String): Map<String, String> {
        if (mutableSources.value[trackId] != LyricsProvider.APPLE) return emptyMap()
        val track = synchronized(appleTracks) { appleTracks[trackId] } ?: return emptyMap()
        return appleTranslation(track, language)
    }

    private suspend fun fetch(track: Track, provider: LyricsProvider): List<LyricLine> {
        if (provider == LyricsProvider.APPLE) return LyricsParser.ttml(appleLyrics(track))
        if (provider == LyricsProvider.YOUTUBE) return LyricsParser.plain(youtubeLyrics(track.id))
        val metadata = track.lyricsMetadata() ?: return emptyList()
        return when (provider) {
            LyricsProvider.BINI -> fromBiniLyrics(metadata)
            LyricsProvider.BETTER_LYRICS -> fromBetterLyrics(metadata)
            LyricsProvider.LYRICS_PLUS -> fromLyricsPlus(metadata)
            LyricsProvider.LRCLIB -> fromLrcLib(metadata)
            LyricsProvider.YOUTUBE -> emptyList()
            LyricsProvider.APPLE -> emptyList()
        }
    }

    private fun fromBiniLyrics(metadata: Metadata): List<LyricLine> {
        val url = "https://lyrics-api.binimum.org/".toHttpUrl().newBuilder()
            .addQueryParameter("track", metadata.title)
            .addQueryParameter("artist", metadata.artist)
            .withAlbumAndDuration(metadata, "album", "duration")
            .build()
        val root = requestObject(url) ?: return emptyList()
        val results = root.optJSONArray("results") ?: return emptyList()
        val lyricsUrl = (0 until results.length()).firstNotNullOfOrNull { index ->
            results.optJSONObject(index)?.optString("lyricsUrl")?.takeIf(String::isNotBlank)
        } ?: return emptyList()
        return requestText(lyricsUrl.toHttpUrl())?.let(LyricsParser::ttml).orEmpty()
    }

    private fun fromBetterLyrics(metadata: Metadata): List<LyricLine> {
        val url = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("s", metadata.title)
            .addQueryParameter("a", metadata.artist)
            .withAlbumAndDuration(metadata, "al", "d")
            .addQueryParameter("videoId", metadata.videoId)
            .build()
        val root = requestObject(url) ?: return emptyList()
        val ttml = root.optString("ttml").ifBlank { root.optString("lyrics") }
        return LyricsParser.ttml(ttml)
    }

    private fun fromLyricsPlus(metadata: Metadata): List<LyricLine> {
        for (server in LYRICS_PLUS_SERVERS) {
            val url = "$server/v2/lyrics/get".toHttpUrl().newBuilder()
                .addQueryParameter("title", metadata.title)
                .addQueryParameter("artist", metadata.artist)
                .withAlbumAndDuration(metadata, "album", "duration")
                .build()
            val lines = runCatching { requestObject(url)?.let(LyricsParser::amPayload).orEmpty() }
                .getOrDefault(emptyList())
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    private fun fromLrcLib(metadata: Metadata): List<LyricLine> {
        val exactUrl = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", metadata.title)
            .addQueryParameter("artist_name", metadata.artist)
            .withAlbumAndDuration(metadata, "album_name", "duration")
            .build()
        val exact = runCatching { requestObject(exactUrl)?.let(LyricsParser::lrcLib).orEmpty() }
            .getOrDefault(emptyList())
        if (exact.isNotEmpty()) return exact

        val searchUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", metadata.title)
            .addQueryParameter("artist_name", metadata.artist)
            .build()
        val search = runCatching { requestArray(searchUrl) }.getOrNull() ?: return emptyList()
        for (index in 0 until search.length()) {
            val lines = search.optJSONObject(index)?.let(LyricsParser::lrcLib).orEmpty()
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    private fun HttpUrl.Builder.withAlbumAndDuration(
        metadata: Metadata,
        albumKey: String,
        durationKey: String,
    ): HttpUrl.Builder = apply {
        if (metadata.album.isNotBlank()) addQueryParameter(albumKey, metadata.album)
        if (metadata.durationMs > 0) addQueryParameter(durationKey, (metadata.durationMs / 1_000).toString())
    }

    private fun requestObject(url: HttpUrl): JSONObject? = requestText(url)?.let(::JSONObject)
    private fun requestArray(url: HttpUrl): JSONArray? = requestText(url)?.let(::JSONArray)

    private fun requestText(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        providerHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string()
        }
    }

    private fun Track.lyricsMetadata(): Metadata? {
        val cleanTitle = title.cleanLookupText()
        val cleanArtist = artist.cleanLookupText()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null
        return Metadata(
            cleanTitle,
            cleanArtist,
            album.cleanLookupText().takeUnless { it.isEngagementMetric() }.orEmpty(),
            durationMs,
            id,
        )
    }

    private fun String.cleanLookupText(): String = replace(
        Regex("\\([^)]*(?:official|video|visualizer|lyrics?|audio|remaster|hd|4k)[^)]*\\)", RegexOption.IGNORE_CASE),
        "",
    ).replace(
        Regex("\\[[^]]*(?:official|video|visualizer|lyrics?|audio|remaster|hd|4k)[^]]*]", RegexOption.IGNORE_CASE),
        "",
    ).replace(Regex("\\s+-\\s+Topic$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun String.isEngagementMetric(): Boolean = contains(
        Regex("\\b(?:plays?|views?|listeners?|subscribers?)\\b", RegexOption.IGNORE_CASE),
    )

    private companion object {
        const val PROVIDER_TIMEOUT_SECONDS = 8L
        const val USER_AGENT = "Orchard Android/2.0"
        val LYRICS_PLUS_SERVERS = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus-seven.vercel.app",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyrics-plus-backend.vercel.app",
        )
    }
}
