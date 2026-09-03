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
import dev.sfg.orchard.mobile.model.AudioQuality
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

/**
 * Resolves an audio stream for a YouTube video using NewPipeExtractor.
 *
 * NewPipe extracts streams by parsing the YouTube web page directly, which often exposes
 * higher-bitrate Opus streams (up to ~320 kbps) that the innertube player API withholds.
 * This class is the sole consumer of NewPipeExtractor in the app; it lives behind
 * [YouTubeStreamResolver], which uses it for public tracks and keeps Innertube as the fallback.
 */
class NewPipeStreamResolver(
    client: OkHttpClient,
    sessionProvider: dev.sfg.orchard.mobile.auth.YouTubeSessionProvider? = null,
) {

    init {
        synchronized(initLock) {
            if (!initialized) {
                NewPipe.init(NewPipeOkHttpDownloader(client, sessionProvider))
                initialized = true
            }
        }
    }

    /**
     * Extracts the best audio stream for [videoId] within [quality]'s bitrate tier.
     *
     * @return a [ResolvedStream] with the CDN URL, or `null` when extraction fails.
     */
    fun resolve(
        videoId: String,
        quality: AudioQuality = AudioQuality.MAX,
    ): ResolvedStream? = runCatching {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        val streams: List<AudioStream> = extractor.audioStreams.orEmpty()
        val playable = streams.filter { it.content.isNotBlank() }
        val selectedIndex = selectNewPipeStreamIndex(
            playable.map { it.averageBitrate },
            quality,
        ) ?: return null
        val best = playable[selectedIndex]
        val format = runCatching { best.format }.getOrNull()
        val identity = YouTubeStreamRequestIdentity.fromUrl(
            best.content,
            YouTubeStreamRequestIdentity.WEB_USER_AGENT,
        )
        val bitrateKbps = newPipeBitrateKbps(best.averageBitrate)
        Log.d(TAG, "NewPipe resolved ${format?.name ?: "?"} " +
            "@${bitrateKbps}kbps for $videoId (${quality.name})")
        val contentLength = best.itagItem?.contentLength?.takeIf { it > 0L }
            ?: best.content.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
            ?: 0L
        ResolvedStream(
            url = best.content,
            mimeType = format?.mimeType ?: "audio/webm",
            // NewPipe doesn't expose an exact expiry; CDN URLs typically live ~6 hours.
            // Use a conservative 45-minute window consistent with the innertube path.
            expiresAtMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(45),
            bitrateKbps = bitrateKbps.coerceAtLeast(0),
            userAgent = identity.userAgent,
            contentLength = contentLength,
            origin = identity.origin,
            referer = identity.referer,
            clientKey = identity.clientKey,
            supportsParallelRanges = true,
        )
    }.onFailure {
        Log.w(TAG, "NewPipe stream extraction failed for $videoId", it)
    }.getOrNull()

    companion object {
        private const val TAG = "NewPipeStreamResolver"
        private val initLock = Any()
        @Volatile private var initialized = false
    }
}

internal fun newPipeBitrateKbps(rawBitrate: Int): Int =
    (if (rawBitrate > 1_000) rawBitrate / 1_000 else rawBitrate).coerceAtLeast(0)

/** Returns the stream index that best preserves Orchard's four quality tiers. */
internal fun selectNewPipeStreamIndex(
    rawBitrates: List<Int>,
    quality: AudioQuality,
): Int? {
    if (rawBitrates.isEmpty()) return null
    val bitrates = rawBitrates.map(::newPipeBitrateKbps)
    val known = bitrates.indices.filter { bitrates[it] > 0 }
    val candidates = known.ifEmpty { bitrates.indices.toList() }
    val capped = when (quality) {
        AudioQuality.DATA_SAVER -> emptyList()
        AudioQuality.NORMAL -> candidates.filter { bitrates[it] <= NORMAL_MAX_KBPS }
        AudioQuality.HIGH -> candidates.filter { bitrates[it] <= HIGH_MAX_KBPS }
        AudioQuality.MAX -> candidates
    }
    return when (quality) {
        AudioQuality.DATA_SAVER -> candidates.minByOrNull { bitrates[it] }
        AudioQuality.NORMAL, AudioQuality.HIGH ->
            capped.maxByOrNull { bitrates[it] }
                ?: candidates.minByOrNull { bitrates[it] }
        AudioQuality.MAX -> candidates.maxByOrNull { bitrates[it] }
    }
}

private const val NORMAL_MAX_KBPS = 140
private const val HIGH_MAX_KBPS = 160
