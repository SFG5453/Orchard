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
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

/**
 * Resolves the highest-bitrate audio stream for a YouTube video using NewPipeExtractor.
 *
 * NewPipe extracts streams by parsing the YouTube web page directly, which often exposes
 * higher-bitrate Opus streams (up to ~320 kbps) that the innertube player API withholds.
 * This class is the sole consumer of NewPipeExtractor in the app; it lives behind
 * [YouTubeStreamResolver] and is called only when the user selects MAX quality.
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
     * Extracts the best audio stream for [videoId].
     *
     * @return a [ResolvedStream] with the CDN URL, or `null` when extraction fails.
     */
    fun resolve(videoId: String): ResolvedStream? = runCatching {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        val streams: List<AudioStream> = extractor.audioStreams.orEmpty()
        val best = streams
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
            ?: return null
        val format = runCatching { best.format }.getOrNull()
        val identity = YouTubeStreamRequestIdentity.fromUrl(
            best.content,
            YouTubeStreamRequestIdentity.WEB_USER_AGENT,
        )
        Log.d(TAG, "NewPipe resolved ${format?.name ?: "?"} " +
            "@${best.averageBitrate}kbps for $videoId")
        val bitrateKbps = if (best.averageBitrate > 1000) best.averageBitrate / 1000 else best.averageBitrate
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
