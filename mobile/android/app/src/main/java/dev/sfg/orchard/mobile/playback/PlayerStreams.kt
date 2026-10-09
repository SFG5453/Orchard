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

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.StreamDetail
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns stable `orchard://` URIs into fetchable streams for both players. Resolution runs on
 * Media3 loader and cache threads; [trackLookup] and [onResolved] are the only ways back to
 * player state.
 */
@UnstableApi
internal class PlayerStreams(
    private val context: Context,
    private val graph: OrchardGraph,
    private val cache: StreamCache,
    /** Queue metadata for a source id; must not block forever on a busy main thread. */
    private val trackLookup: (String) -> Track?,
    /** A stable URI resolved; called on the resolving thread. */
    private val onResolved: (stableUri: String, stream: ResolvedStream) -> Unit,
) {
    private val resolver = graph.streams

    /** The exact client identity behind each stable Orchard URI's most recent media fetch. */
    private val resolvedStreams = ConcurrentHashMap<String, ResolvedStream>()

    /**
     * Media3 can open the same stable URI from the active player, the spare crossfade player, and
     * the whole-track prefetcher at the same time. Keep resolution itself single-flight per URI;
     * otherwise each range request mints another Qobuz session and the last callback can replace
     * the source metadata for the song that is actually playing.
     */
    private val resolutionLocks = ConcurrentHashMap<String, Any>()

    @Volatile
    private var variant = graph.streamVariant()

    // Signing in to Qobuz or changing quality changes where audio comes from; streams resolved before that are stale.
    private fun dropStaleStreams() {
        val now = graph.streamVariant()
        if (now == variant) return
        variant = now
        resolvedStreams.clear()
    }

    /** Returns a still-usable stream for a stable URI and drops expired CDN/session URLs. */
    fun cached(stableUri: String): ResolvedStream? {
        dropStaleStreams()
        val stream = resolvedStreams[stableUri] ?: return null
        if (stream.expiresAtMs > System.currentTimeMillis() + EXPIRY_BUFFER_MS) return stream
        resolvedStreams.remove(stableUri, stream)
        return null
    }

    /** Forgets the stream behind a failed URI and returns it, so the next open resolves afresh. */
    fun forget(stableUri: String): ResolvedStream? = resolvedStreams.remove(stableUri)

    fun clear() {
        resolvedStreams.clear()
        resolutionLocks.clear()
    }

    /** One player's source factory: cached progressive audio, uncached video, and authenticated HLS. */
    fun mediaSourceFactory(): MediaSource.Factory {
        // The identity is a default request property rather than the factory's userAgent
        // because that one is appended after per-request headers instead of replacing
        // them, which would send two User-Agent headers on any stream that supplies its
        // own. As a default property it is simply overridden by the resolved stream's.
        val httpFactory = OkHttpDataSource.Factory(graph.http)
            .setDefaultRequestProperties(mapOf("User-Agent" to YouTubeStreamResolver.CLIENT_USER_AGENT))
        val resolvingFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, httpFactory), ::resolve)
        // HLS segment requests are created after the orchard manifest URI has been
        // resolved, so they do not inherit that DataSpec's headers. Give the entire
        // HLS data-source family Safari's identity to match the player response.
        val hlsHttpFactory = OkHttpDataSource.Factory(graph.http)
            .setDefaultRequestProperties(mapOf("User-Agent" to YouTubeStreamResolver.WEB_SAFARI_USER_AGENT))
        val hlsResolvingFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, hlsHttpFactory)) { original ->
            if (!MediaItemMapper.requiresAuthenticatedHls(original.uri)) return@Factory original
            val stream = resolver.resolveAuthenticatedHls(original.uri.lastPathSegment.orEmpty())
            resolvedStreams[original.uri.toString()] = stream
            original.withUri(stream.url.toUri()).withAdditionalHeaders(stream.requestHeaders)
        }
        // Cache above resolution: it keys on the stable orchard:// URI, and a hit skips the
        // resolver entirely rather than re-resolving a CDN URL it does not need.
        val progressive = DefaultMediaSourceFactory(context).setDataSourceFactory(cache.dataSourceFactory(resolvingFactory))
        // Video is viewed on demand and can be hundreds of megabytes. Do not evict the listener's
        // audio cache by writing the movie into the whole-track cache behind their back.
        val video = DefaultMediaSourceFactory(context).setDataSourceFactory(resolvingFactory)
        val hls = HlsMediaSource.Factory(hlsResolvingFactory)
        // Songs from this phone: plain reads from storage, no resolver and no copy into the stream cache.
        val localFiles = DefaultMediaSourceFactory(context)
        return object : MediaSource.Factory {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val uri = mediaItem.localConfiguration?.uri
                return when {
                    uri != null && (uri.scheme == "content" || uri.scheme == "file") -> localFiles.createMediaSource(mediaItem)
                    uri != null && MediaItemMapper.requiresAuthenticatedHls(uri) -> hls.createMediaSource(mediaItem)
                    // The picture keeps the queue item as its own, so the player still reports it.
                    MediaItemMapper.isVideoUri(uri) -> MergingMediaSource(
                        true,
                        true,
                        video.createMediaSource(mediaItem),
                        video.createMediaSource(MediaItemMapper.videoSound(mediaItem)),
                    )
                    else -> progressive.createMediaSource(mediaItem)
                }
            }

            override fun getSupportedTypes(): IntArray =
                (progressive.supportedTypes.asIterable() + C.CONTENT_TYPE_HLS).distinct().toIntArray()

            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
                progressive.setDrmSessionManagerProvider(provider)
                localFiles.setDrmSessionManagerProvider(provider)
                video.setDrmSessionManagerProvider(provider)
                hls.setDrmSessionManagerProvider(provider)
            }

            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
                progressive.setLoadErrorHandlingPolicy(policy)
                localFiles.setLoadErrorHandlingPolicy(policy)
                video.setLoadErrorHandlingPolicy(policy)
                hls.setLoadErrorHandlingPolicy(policy)
            }
        }
    }

    private fun resolve(original: DataSpec): DataSpec {
        Log.d(TAG, "resolve: request uri=${original.uri}")
        dropStaleStreams()
        if (!MediaItemMapper.isOrchardUri(original.uri)) return original
        val videoId = original.uri.lastPathSegment.orEmpty()
        val stableUri = original.uri.toString()
        val lock = resolutionLocks.computeIfAbsent(stableUri) { Any() }
        return synchronized(lock) {
            var stream: ResolvedStream
            var selectedVariant: String
            var freshlyResolved: Boolean
            while (true) {
                selectedVariant = graph.streamVariant()
                // A source toggle can finish while a Qobuz match is still resolving.
                val existing = cached(stableUri)
                freshlyResolved = existing == null
                stream = existing?.also { Log.d(TAG, "resolve: reusing stream for $videoId") } ?: fresh(original, videoId)
                if (selectedVariant == graph.streamVariant()) break
            }
            resolvedStreams[stableUri] = stream
            if (freshlyResolved && !MediaItemMapper.isVideoUri(original.uri)) {
                if (stream.isQobuz) {
                    graph.qobuzTiers.record(videoId, selectedVariant, StreamDetail(
                        hiRes = stream.hires,
                        bitDepth = stream.bitDepth ?: 0,
                        sampleRate = stream.sampleRate ?: 0,
                        codec = "FLAC",
                    ))
                } else {
                    graph.qobuzTiers.clear(videoId, selectedVariant)
                }
            }
            onResolved(stableUri, stream)
            // The CDN checks the URL against the client it was issued to, so the fetch
            // has to claim the identity that resolved it rather than the factory's
            // default. Getting this wrong resolves fine and then 403s on the audio.
            bounded(original.withUri(stream.url.toUri()).withAdditionalHeaders(stream.requestHeaders), stream)
        }
    }

    private fun fresh(original: DataSpec, videoId: String): ResolvedStream {
        Log.d(TAG, "resolve: resolving videoId=$videoId")
        val uri = original.uri
        val stream = qobuz(videoId, uri) ?: when {
            MediaItemMapper.isVideoUri(uri) -> resolver.resolveVideo(
                videoId,
                MediaItemMapper.videoHeight(uri) ?: graph.settings.settings.value.videoMaxHeight,
            ).let { if (MediaItemMapper.isVideoSound(uri)) it.sound else it.picture }
            MediaItemMapper.requiresAuthenticatedDirect(uri) -> resolver.resolveAuthenticatedDirect(videoId)
            else -> resolver.resolve(videoId)
        }
        Log.d(TAG, "resolve: $videoId -> ${stream.url.take(60)}...")
        return stream
    }

    /** The Qobuz match for a MAX-quality audio item, or null to fall back to the catalog stream. */
    private fun qobuz(videoId: String, uri: android.net.Uri): ResolvedStream? {
        if (graph.settings.settings.value.audioQuality != AudioQuality.MAX) return null
        if (MediaItemMapper.isVideoUri(uri) || MediaItemMapper.requiresAuthenticatedDirect(uri) ||
            MediaItemMapper.requiresAuthenticatedHls(uri)
        ) return null
        val track = trackLookup(videoId)?.takeUnless { it.isUpload } ?: return null
        // Loader threads may block, so this runs here without hopping to another dispatcher.
        val match = runCatching {
            runBlocking {
                graph.qobuzResolver.resolve(
                    id = track.id,
                    title = track.title,
                    artists = listOf(track.artist),
                    album = track.album,
                    durationMs = track.durationMs,
                    explicit = track.explicit,
                )
            }
        }.getOrNull() ?: return null
        Log.i(TAG, "resolve: $videoId via Qobuz (${match.bitrateKbps} kbps)")
        return ResolvedStream(
            url = match.streamUrl,
            mimeType = "audio/flac",
            expiresAtMs = System.currentTimeMillis() + QOBUZ_URL_LIFETIME_MS,
            bitrateKbps = match.bitrateKbps,
            isQobuz = true,
            bitDepth = match.bitDepth,
            sampleRate = match.sampleRate,
            hires = match.hires,
        )
    }

    /**
     * Gives a googlevideo request a concrete end offset, because a request with no Range header
     * is answered at dial-up speed and then cut off.
     *
     * Media3 opens a progressive stream at position 0 with no known length, and
     * `HttpUtil.buildRangeRequestHeader` answers that exact pair with null, so the request goes
     * out carrying no Range header at all. Measured against a resolved URL: no Range header
     * returned 200 and then dribbled 3145712 of 3497127 bytes over 98 seconds before the
     * connection died, while `bytes=0-3497126` returned 206 with the whole file in under a
     * second. Desktop reached the same conclusion in 716f5f0, which sends bounded ranges too.
     *
     * Only fills in a length that is genuinely unknown; a range Media3 asked for is already
     * bounded. Without a content length the spec passes through unchanged rather than guessing
     * an end that could be answered with 416.
     */
    private fun bounded(spec: DataSpec, stream: ResolvedStream): DataSpec {
        if (spec.length != C.LENGTH_UNSET.toLong()) return spec
        val remaining = stream.contentLength - spec.position
        if (stream.contentLength <= 0 || remaining <= 0) return spec
        return spec.subrange(0, remaining)
    }

    private companion object {
        // Shares the service tag so existing logcat filters keep working.
        const val TAG = "OrchardPlayback"
        const val EXPIRY_BUFFER_MS = 60_000L
        const val QOBUZ_URL_LIFETIME_MS = 4 * 3600_000L
    }
}
