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
import dev.sfg.orchard.mobile.auth.YouTubeSessionProvider
import dev.sfg.orchard.mobile.download.DownloadManager
import dev.sfg.orchard.mobile.download.DownloadPlaybackHelper
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.youtube.YouTubeProvider
import dev.sfg.orchard.mobile.youtube.providerJson
import dev.sfg.orchard.mobile.youtube.text
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class ResolvedStream(
    val url: String,
    val mimeType: String,
    val expiresAtMs: Long,
    val bitrateKbps: Int = 0,
    /** The CDN checks the URL against the client that minted it, so fetches must claim it too. */
    val userAgent: String = YouTubeStreamResolver.CLIENT_USER_AGENT,
    /** Exact progressive-stream size, used for progress and completeness validation. */
    val contentLength: Long = 0,
    val origin: String? = null,
    val referer: String? = null,
    /** Whether independent bounded ranges may be fetched concurrently. */
    val supportsParallelRanges: Boolean = false,
    val isQobuz: Boolean = false,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val hires: Boolean = false,
) {
    val requestHeaders: Map<String, String>
        get() = buildMap {
            put("User-Agent", userAgent)
            origin?.let { put("Origin", it) }
            referer?.let { put("Referer", it) }
        }
}

/** A music video as two files: the picture alone and its soundtrack. */
data class VideoStreams(val picture: ResolvedStream, val sound: ResolvedStream, val height: Int, val heights: List<Int>)

data class VideoQuality(val videoId: String, val height: Int, val heights: List<Int>)

/** The video a track's audio actually came from, which is what SponsorBlock timestamps refer to. */
data class PlaybackSource(val videoId: String, val durationSeconds: Double)

data class PlaybackTracking(val playbackUrl: String, val watchtimeUrl: String, val itag: Int)

/**
 * Stream URLs from the desktop provider's signed-in WEB_REMIX player (`playback.*`), with the PO
 * token minted in QuickJS. Calls block: Media3 and the downloader resolve on loader threads.
 */
class YouTubeStreamResolver(
    private val provider: YouTubeProvider,
    private val sessions: YouTubeSessionProvider,
    private val qualityProvider: () -> AudioQuality = { AudioQuality.HIGH },
    private val downloads: () -> DownloadManager? = { null },
) {
    /**
     * Queue metadata for a bare video id. The provider matches album audio by title, artist and
     * duration, and checks a music video's length against the song it stands in for.
     */
    @Volatile var trackLookup: (String) -> Track? = { null }

    private val streams = ConcurrentHashMap<String, ResolvedStream>()
    private val videos = ConcurrentHashMap<String, VideoStreams>()

    private val mutableVideoQuality = MutableStateFlow<VideoQuality?>(null)
    /** The picture height of the last resolved music video and the heights it offers. */
    val videoQuality: StateFlow<VideoQuality?> = mutableVideoQuality.asStateFlow()

    /**
     * Track id to the video and length its stream came from. Album audio can sit on a different
     * video than the queue entry, and SponsorBlock only knows videos, so asking by track id could
     * fetch skips for the wrong cut. Kept small: only the current song and its preload matter.
     */
    private val mutableSources = MutableStateFlow<Map<String, PlaybackSource>>(emptyMap())
    val sources: StateFlow<Map<String, PlaybackSource>> = mutableSources.asStateFlow()
    private val historyTracking = ConcurrentHashMap<String, PlaybackTracking>()
    private val bitrates = ConcurrentHashMap<String, Int>()
    // Refused URLs ask the provider for a fresh player and token on the next resolve.
    private val refresh = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "orchard-stream-prefetch").apply { isDaemon = true }
    }

    fun resolve(videoId: String): ResolvedStream = resolve(trackFor(videoId))

    fun resolve(track: Track): ResolvedStream {
        downloads()?.let { manager ->
            DownloadPlaybackHelper.resolveOfflineStream(track.id, manager)?.let { return it }
        }
        return audio(track, streamQuality()).also { bitrates[track.id] = it.bitrateKbps }
    }

    /**
     * The lowest-bitrate stream, ignoring downloads: the exact encode desktop's Best Mix analyzes,
     * so both platforms sort from the same features.
     */
    fun resolveSaver(track: Track): ResolvedStream = audio(track, "saver")

    private fun audio(track: Track, quality: String): ResolvedStream =
        cached(track.id, "playback.resolve", track, quality) { json ->
            val contentLength = json.optLong("contentLength")
            rememberSource(track.id, json)
            rememberHistoryTracking(track.id, json)
            ResolvedStream(
                url = json.text("url"),
                mimeType = json.text("mimeType").ifEmpty { "audio/mp4" },
                expiresAtMs = expiry(json),
                bitrateKbps = json.optInt("bitrate") / 1000,
                userAgent = json.text("userAgent").ifEmpty { CLIENT_USER_AGENT },
                contentLength = contentLength,
                origin = json.text("origin").ifEmpty { MUSIC_ORIGIN },
                referer = "${json.text("origin").ifEmpty { MUSIC_ORIGIN }}/",
            )
        }

    /**
     * Separate picture and soundtrack streams, the picture at most [maxHeight] lines (0 for the
     * best). YouTube muxes only 360p. A failure is handled by the service's audio fallback.
     */
    fun resolveVideo(videoId: String, maxHeight: Int): VideoStreams {
        val key = "$videoId:video:$maxHeight:${streamQuality()}"
        freshVideo(key)?.let { return it }
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            freshVideo(key)?.let { return it }
            val track = trackFor(videoId).copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
            val payload = payload(videoId, track, streamQuality()).put("maxHeight", maxHeight)
            val json = runBlocking { withTimeout(RESOLVE_TIMEOUT_MS) { provider.invoke("playback.resolveVideo", payload) } }
            val audio = json.optJSONObject("audio") ?: error("YouTube returned no video soundtrack")
            val heights = json.optJSONArray("heights")
                ?.let { list -> (0 until list.length()).map(list::optInt).filter { it > 0 } }.orEmpty()
            val streams = VideoStreams(json.stream("video/mp4"), audio.stream("audio/mp4"), json.optInt("height"), heights)
            check(streams.picture.url.isNotBlank() && streams.sound.url.isNotBlank()) { "YouTube returned no stream URL" }
            videos[key] = streams
            mutableVideoQuality.value = VideoQuality(videoId, streams.height, heights)
            return streams
        }
    }

    private fun JSONObject.stream(defaultMime: String) = ResolvedStream(
        url = text("url"),
        mimeType = text("mimeType").ifEmpty { defaultMime },
        expiresAtMs = expiry(this),
        bitrateKbps = optInt("bitrate") / 1000,
        userAgent = text("userAgent").ifEmpty { CLIENT_USER_AGENT },
        contentLength = optLong("contentLength"),
        origin = text("origin").ifEmpty { MUSIC_ORIGIN },
        referer = "${text("origin").ifEmpty { MUSIC_ORIGIN }}/",
    )

    private fun freshVideo(key: String): VideoStreams? {
        val streams = videos[key] ?: return null
        if (streams.picture.expiresAtMs > System.currentTimeMillis() + EXPIRY_MARGIN_MS) return streams
        videos.remove(key, streams)
        return null
    }

    /** Every provider stream is signed-in; this forces a fresh player and token. */
    fun resolveAuthenticatedDirect(videoId: String): ResolvedStream {
        invalidate(videoId)
        refresh += videoId
        return resolve(videoId)
    }

    /** Safari's HLS manifest, the last resort once direct URLs keep being refused. */
    fun resolveAuthenticatedHls(videoId: String): ResolvedStream =
        cached(videoId, "playback.resolveHls") { json ->
            ResolvedStream(
                url = json.text("url"),
                mimeType = json.text("mimeType").ifEmpty { HLS_MIME_TYPE },
                expiresAtMs = expiry(json),
                userAgent = json.text("userAgent").ifEmpty { WEB_SAFARI_USER_AGENT },
            )
        }

    /** Loads the player script off the critical path of the first play. */
    fun warmUp() = background.execute {
        runCatching { runBlocking { provider.invoke("playback.prepare") } }
            .onFailure { Log.w(TAG, "Player warm-up failed", it) }
    }

    fun prefetch(videoId: String) = background.execute {
        // A file on this phone has no stream to resolve.
        if (dev.sfg.orchard.mobile.local.isLocalTrackId(videoId)) return@execute
        runCatching { resolve(videoId) }.onFailure { Log.d(TAG, "Prefetch failed for $videoId", it) }
    }

    fun knownBitrateKbps(videoId: String): Int = bitrates[videoId] ?: 0

    fun trackingFor(videoId: String): PlaybackTracking? = historyTracking[videoId]

    /** Cached audio can play without resolving a stream in this process. */
    suspend fun loadHistoryTracking(track: Track): PlaybackTracking? {
        trackingFor(track.id)?.let { return it }
        val json = provider.invoke("playback.resolve", payload(track.id, track, streamQuality()))
        rememberHistoryTracking(track.id, json)
        return trackingFor(track.id)
    }

    fun invalidate(videoId: String) {
        streams.keys.removeIf { it.startsWith("$videoId:") }
        videos.keys.removeIf { it.startsWith("$videoId:") }
    }

    fun resetForRetry(videoId: String) = invalidate(videoId)

    /** Returns true when the CDN refused the URL itself, so the next resolve starts fresh. */
    fun reject(videoId: String, stream: ResolvedStream, responseCode: Int): Boolean {
        invalidate(videoId)
        if (responseCode !in REFUSED_RESPONSE_CODES) return false
        Log.w(TAG, "CDN refused $videoId with HTTP $responseCode; refreshing player and token")
        refresh += videoId
        return true
    }

    private fun trackFor(videoId: String): Track {
        require(videoId.isNotBlank()) { "A YouTube video id is required" }
        return runCatching { trackLookup(videoId) }.getOrNull()?.takeIf { it.id == videoId }
            ?: Track(id = videoId, title = "", artist = "")
    }

    private fun cached(
        videoId: String,
        method: String,
        track: Track = trackFor(videoId),
        quality: String = streamQuality(),
        map: (JSONObject) -> ResolvedStream,
    ): ResolvedStream {
        val key = "$videoId:$method:$quality"
        fresh(key)?.let { return it }
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            fresh(key)?.let { return it }
            val payload = payload(videoId, track, quality)
            val stream = map(runBlocking { withTimeout(RESOLVE_TIMEOUT_MS) { provider.invoke(method, payload) } })
            check(stream.url.isNotBlank()) { "YouTube returned no stream URL" }
            streams[key] = stream
            return stream
        }
    }

    private fun payload(videoId: String, track: Track, quality: String): JSONObject = JSONObject()
        .put("session", sessions.session().providerJson())
        .put("track", track.providerJson())
        .put("streamQuality", quality)
        .put("refreshStream", refresh.remove(videoId))

    private fun rememberSource(trackId: String, json: JSONObject) {
        val videoId = json.text("youtubeVideoId")
        if (videoId.isBlank()) return
        mutableSources.update { known ->
            val kept = if (known.size >= MAX_REMEMBERED_SOURCES) known.entries.drop(known.size / 2) else known.entries.toList()
            kept.associate { it.key to it.value } + (trackId to PlaybackSource(videoId, json.optDouble("durationSeconds", 0.0)))
        }
    }

    private fun rememberHistoryTracking(trackId: String, json: JSONObject) {
        val tracking = json.optJSONObject("playbackTracking") ?: return
        val playbackUrl = tracking.text("playbackUrl")
        val watchtimeUrl = tracking.text("watchtimeUrl")
        if (playbackUrl.isBlank() || watchtimeUrl.isBlank()) return
        if (historyTracking.size >= MAX_REMEMBERED_SOURCES) historyTracking.clear()
        historyTracking[trackId] = PlaybackTracking(playbackUrl, watchtimeUrl, tracking.optInt("itag", 251))
    }

    private fun fresh(key: String): ResolvedStream? {
        val stream = streams[key] ?: return null
        if (stream.expiresAtMs > System.currentTimeMillis() + EXPIRY_MARGIN_MS) return stream
        streams.remove(key, stream)
        return null
    }

    private fun streamQuality(): String = when (qualityProvider()) {
        AudioQuality.DATA_SAVER -> "saver"
        AudioQuality.NORMAL -> "normal"
        AudioQuality.HIGH, AudioQuality.MAX -> "high"
    }

    private fun expiry(json: JSONObject): Long =
        json.optLong("expiresAt").takeIf { it > 0 } ?: (System.currentTimeMillis() + DEFAULT_LIFETIME_MS)

    companion object {
        /** The browser identity the provider's WEB_REMIX player and media probe use. */
        const val CLIENT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Safari/537.36"
        const val WEB_SAFARI_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Version/15.5 Safari/605.1.15,gzip(gfe)"
        private const val MUSIC_ORIGIN = "https://music.youtube.com"
        private const val HLS_MIME_TYPE = "application/x-mpegURL"
        private val REFUSED_RESPONSE_CODES = setOf(401, 403, 410)
        private val RESOLVE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(45)
        private val EXPIRY_MARGIN_MS = TimeUnit.MINUTES.toMillis(2)
        private val DEFAULT_LIFETIME_MS = TimeUnit.MINUTES.toMillis(45)
        private const val MAX_REMEMBERED_SOURCES = 8
        private const val TAG = "YouTubeStreamResolver"
    }
}

/** What `playback.*` needs to know about a queue entry. */
private fun Track.providerJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("type", if (isVideoUpload) "video" else "track")
    .put("title", title)
    .put("artist", artist)
    .put("album", album)
    .put("musicVideoType", musicVideoType)
    .put("explicit", explicit)
    .put("isUpload", isUpload)
    .apply { if (durationMs > 0) put("durationSeconds", durationMs / 1000.0) }
