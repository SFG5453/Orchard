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

package dev.sfg.orchard.mobile.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class CachedAnimatedArtwork(
    /** The URI the player should open. For a master HLS manifest this is its best rendition. */
    val playbackUrl: String,
    val bytes: Long,
)

/**
 * Persistent, non-evicting Media3 cache for motion covers saved with offline tracks.
 *
 * Media3's downloaders understand HLS segment maps, encryption keys and relative URLs, while the
 * shared [CacheDataSource] lets the existing artwork player read the exact same resources offline.
 */
@OptIn(UnstableApi::class)
object AnimatedArtworkCache {
    private const val DIRECTORY = "animated_artwork"
    private const val USER_AGENT = "Orchard Android/2.0"
    private const val MAX_MASTER_DEPTH = 3

    @Volatile
    private var instance: SimpleCache? = null

    private fun cache(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(DownloadStore.defaultDownloadDir(context), DIRECTORY).apply { mkdirs() },
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { instance = it }
    }

    /** Data source used by the UI; cached resources are read first and remote URLs remain valid. */
    fun dataSourceFactory(context: Context): CacheDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context.applicationContext, http))
    }

    fun bytesUsed(context: Context): Long = cache(context).cacheSpace

    suspend fun download(context: Context, http: OkHttpClient, sourceUrl: String): CachedAnimatedArtwork =
        withContext(Dispatchers.IO) {
            val playbackUrl = resolvePlayableUrl(http, sourceUrl)
            val factory = CacheDataSource.Factory()
                .setCache(cache(context))
                .setUpstreamDataSourceFactory(
                    DefaultDataSource.Factory(
                        context.applicationContext,
                        OkHttpDataSource.Factory(http).setUserAgent(USER_AGENT),
                    ),
                )
            val item = MediaItem.fromUri(playbackUrl)
            val downloader: Downloader = if (playbackUrl.isHlsUrl()) {
                HlsDownloader.Factory(factory).create(item)
            } else {
                ProgressiveDownloader(item, factory)
            }
            var downloadedBytes = 0L
            runInterruptible {
                downloader.download { _, bytesDownloaded, _ -> downloadedBytes = bytesDownloaded }
            }
            CachedAnimatedArtwork(playbackUrl, downloadedBytes)
        }

    suspend fun remove(context: Context, http: OkHttpClient, playbackUrl: String) =
        withContext(Dispatchers.IO) {
            if (playbackUrl.isBlank()) return@withContext
            val factory = CacheDataSource.Factory()
                .setCache(cache(context))
                .setUpstreamDataSourceFactory(
                    DefaultDataSource.Factory(
                        context.applicationContext,
                        OkHttpDataSource.Factory(http).setUserAgent(USER_AGENT),
                    ),
                )
            val item = MediaItem.fromUri(playbackUrl)
            val downloader: Downloader = if (playbackUrl.isHlsUrl()) {
                HlsDownloader.Factory(factory).create(item)
            } else {
                ProgressiveDownloader(item, factory)
            }
            downloader.remove()
        }

    /**
     * HLS downloaders save every rendition in a master manifest. Resolve it first so an artwork
     * download keeps only the highest-quality video loop instead of several copies of the same
     * animation. Media playlists and direct videos pass through unchanged.
     */
    private fun resolvePlayableUrl(http: OkHttpClient, sourceUrl: String): String {
        var current = sourceUrl
        repeat(MAX_MASTER_DEPTH) {
            if (!current.isHlsUrl()) return current
            val manifest = http.newCall(
                Request.Builder().url(current).header("User-Agent", USER_AGENT).build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return current
                response.body.string()
            }
            val variant = highestQualityVariant(manifest) ?: return current
            current = current.toHttpUrlOrNull()?.resolve(variant)?.toString() ?: return current
        }
        return current
    }

    internal fun highestQualityVariant(manifest: String): String? {
        val lines = manifest.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        var bestUrl: String? = null
        var bestScore = Long.MIN_VALUE
        for (index in lines.indices) {
            val descriptor = lines[index]
            if (!descriptor.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) continue
            val url = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: continue
            val attributes = descriptor.substringAfter(':')
            val bandwidth = Regex("(?:AVERAGE-)?BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                .find(attributes)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val resolution = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
                .find(attributes)?.groupValues
            val pixels = if (resolution != null) {
                (resolution.getOrNull(1)?.toLongOrNull() ?: 0L) *
                    (resolution.getOrNull(2)?.toLongOrNull() ?: 0L)
            } else {
                0L
            }
            val score = pixels * 1_000_000L + bandwidth
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun String.isHlsUrl(): Boolean =
        substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)
}
