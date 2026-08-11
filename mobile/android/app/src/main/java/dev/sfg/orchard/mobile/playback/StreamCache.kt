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
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlin.math.roundToInt
import dev.sfg.orchard.mobile.model.AudioQuality
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * On-disk cache holding whole tracks rather than only what is ahead of the playhead.
 *
 * ExoPlayer's own buffering is a window around the playhead: it is enough to play smoothly and
 * nothing more, so seeking backwards, or skipping to a track and back, refetches audio that was
 * already downloaded once. Caching the whole file makes those instant, and it is what makes
 * offline analysis possible at all; a beat tracker cannot read audio the player has not fetched.
 *
 * Placement matters more than it looks. The cache sits *above* [YouTubeStreamResolver], so it sees
 * the stable `orchard://stream/<id>` URI rather than the expiring CDN URL the resolver swaps in. If
 * it sat below, every cache key would be a URL that is dead within hours and nothing would ever
 * hit. It also means a cache hit skips resolution entirely: no player request, no round trip.
 */
@UnstableApi
class StreamCache(
    context: Context,
    maxBytes: Long,
    private val quality: () -> AudioQuality,
) {
    private val cache: SimpleCache = SimpleCache(
        File(context.filesDir, CACHE_DIRECTORY),
        // The evictor's ceiling is fixed when the cache is opened, so a changed setting takes
        // effect the next time the playback service starts rather than immediately. Rebuilding it
        // in place is not worth the risk: the players hold open data sources reading from it.
        LeastRecentlyUsedCacheEvictor(maxBytes),
        StandaloneDatabaseProvider(context),
    )

    /**
     * Called when a track finishes caching, on the prefetch thread.
     *
     * Analysis needs the whole file, so it can only start once caching completes, and caching
     * completes long after the player events that requested it. Without this the work is simply
     * never triggered: the last "waiting on cache" check happens seconds before the download does.
     */
    @Volatile
    var onCached: ((Uri) -> Unit)? = null

    /** Bytes currently held, for the settings screen to show against the ceiling. */
    val usedBytes: Long get() = cache.cacheSpace

    private val prefetchExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "orchard-track-prefetch").apply {
            isDaemon = true
            // Prefetching a whole track must never compete with the decoder feeding the speaker.
            priority = Thread.MIN_PRIORITY
        }
    }
    private val inFlight = ConcurrentHashMap<String, MutableList<CacheWriter>>()

    /**
     * Separate from [prefetchExecutor] so the range workers of one track cannot be starved by
     * another track's job sitting in the same queue.
     */
    private val rangeExecutor = Executors.newFixedThreadPool(CONCURRENCY) { runnable ->
        Thread(runnable, "orchard-track-range").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private lateinit var upstreamFactory: DataSource.Factory
    private lateinit var readFactory: CacheDataSource.Factory
    private lateinit var writeFactory: CacheDataSource.Factory

    /**
     * Wraps [upstream] so reads are served from disk when possible and written to it when not.
     *
     * Quality is folded into the cache key: the same track at DATA_SAVER and HIGH are different
     * bytes, and keying both as the track alone would serve whichever was fetched first.
     */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory {
        upstreamFactory = upstream
        readFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory { spec -> cacheKey(spec.uri) }
            // A corrupt or unreadable cache entry should cost a re-download, not playback.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        writeFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> cacheKey(spec.uri) }
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        return readFactory
    }

    private fun cacheKey(uri: Uri): String = "${uri}|${quality().name}"

    /** True when the whole track is already on disk, so nothing needs fetching. */
    fun isFullyCached(uri: Uri): Boolean {
        val key = cacheKey(uri)
        val length = cache.getContentMetadata(key).get(
            androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH,
            C.LENGTH_UNSET.toLong(),
        )
        return length > 0 && cache.getCachedBytes(key, 0, length) == length
    }

    /**
     * The measured bitrate of the cached stream for [uri], in kbps, or 0 when it cannot be
     * measured yet.
     *
     * This is bytes actually on disk over the track's real duration, so unlike the rate a stream
     * resolver reports it is not a nominal target the encoder was aiming at: it is what the file
     * turned out to be. Opus is VBR, so the two genuinely differ.
     *
     * Requires the whole file, because a partial cache is a count of bytes with no way to say how
     * many seconds of music they hold. Includes container overhead, which for WebM is well under a
     * percent at these rates and cannot be separated out without parsing the stream.
     */
    fun cachedBitrateKbps(uri: Uri, durationMs: Long): Int {
        if (durationMs <= 0 || !isFullyCached(uri)) return 0
        val bytes = cache.getContentMetadata(cacheKey(uri)).get(
            androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH,
            C.LENGTH_UNSET.toLong(),
        )
        if (bytes <= 0) return 0
        return (bytes * 8.0 / durationMs).roundToInt()
    }

    /**
     * Pulls the whole of [uri] into the cache in the background.
     *
     * Idempotent: a track already being fetched, or already complete, returns immediately. Failures
     * are logged and dropped; a prefetch is an optimization, and the player will fetch what it
     * needs on its own if this never finishes.
     */
    fun prefetch(uri: Uri) {
        if (!::readFactory.isInitialized || !::upstreamFactory.isInitialized) return
        // HLS consists of a short-lived manifest and many segments. Treating the
        // manifest as a progressive track marks a few KB as a fully cached song and
        // later hands playlist text to the audio analyzer.
        if (MediaItemMapper.requiresAuthenticatedHls(uri)) return
        val key = cacheKey(uri)
        if (inFlight.containsKey(key) || isFullyCached(uri)) return
        val writers = java.util.Collections.synchronizedList(mutableListOf<CacheWriter>())
        if (inFlight.putIfAbsent(key, writers) != null) return

        prefetchExecutor.execute {
            val started = System.currentTimeMillis()
            try {
                val length = resolveLength(uri, key)
                if (length > 0) {
                    // Recorded explicitly, because nothing else will. Media3 writes the content
                    // length only when a source is opened unbounded, and every request below is a
                    // bounded range, so without this the track downloads in full while the cache
                    // still has no idea how long it is meant to be, and isFullyCached can never
                    // return true. That silently starves analysis, which gates on it.
                    val mutations = androidx.media3.datasource.cache.ContentMetadataMutations()
                    androidx.media3.datasource.cache.ContentMetadataMutations
                        .setContentLength(mutations, length)
                    cache.applyContentMetadataMutations(key, mutations)

                    fetchByRanges(uri, key, length, writers)
                    if (isFullyCached(uri)) {
                        Log.d(
                            TAG,
                            "Cached $uri (${length / 1024}KB) in ${System.currentTimeMillis() - started}ms",
                        )
                        onCached?.invoke(uri)
                    } else {
                        Log.w(
                            TAG,
                            "Prefetch of $uri finished with missing ranges (${cache.getCachedBytes(key, 0, length)} / $length bytes)",
                        )
                    }
                } else {
                    // No length means no ranges; one sequential pass is all that is available.
                    fetchWhole(uri, key, writers)
                    if (isFullyCached(uri)) {
                        Log.d(TAG, "Cached $uri sequentially in ${System.currentTimeMillis() - started}ms")
                        onCached?.invoke(uri)
                    } else {
                        Log.w(TAG, "Prefetch of $uri (sequential) finished incomplete")
                    }
                }
            } catch (cancelled: java.io.InterruptedIOException) {
                // What cancel() raises, and it carries no message. This is the ordinary path when
                // the queue moves on, not a fault; the partial content stays cached and a later
                // prefetch resumes from it rather than starting over.
                Log.d(TAG, "Prefetch of $uri cancelled; partial content kept")
            } catch (error: Exception) {
                Log.w(TAG, "Prefetch of $uri failed", error)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** Total bytes of the resource, or 0 when the upstream will not say. */
    private fun resolveLength(uri: Uri, key: String): Long {
        cache.getContentMetadata(key).get(
            androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH,
            C.LENGTH_UNSET.toLong(),
        ).takeIf { it > 0 }?.let { return it }

        // Must probe upstream directly rather than through readFactory (CacheDataSource).
        // If the cache contains any partial span at position 0 (e.g. from the player buffering),
        // opening CacheDataSource without a known total content length returns only the size of
        // that single cached span rather than the full upstream resource length.
        val probe = upstreamFactory.createDataSource()
        return try {
            // Opened unbounded because that is what makes the source report the whole length, then
            // closed immediately without reading; the point is the header, not the body.
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(0)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
            probe.open(spec).takeIf { it != C.LENGTH_UNSET.toLong() } ?: 0L
        } catch (error: Exception) {
            Log.d(TAG, "Could not determine length of $uri: ${error.message}")
            0L
        } finally {
            runCatching { probe.close() }
        }
    }

    /**
     * Fills the cache from several bounded requests at once.
     *
     * This is the whole reason prefetching is worth doing at all. YouTube throttles a single
     * progressive response to roughly playback rate, so one sequential request caches a track at
     * about 3x realtime no matter how much bandwidth is available. The throttle is applied per
     * request, so a handful of concurrent ranged requests multiply the throughput more or less
     * linearly. Orchard desktop reaches the same conclusion in src/audio/engine/audioFetch.js.
     *
     * The first range is fetched alone before the rest start. A server that advertises ranges but
     * ignores them would otherwise have several full-length responses opened against it at once.
     */
    private fun fetchByRanges(uri: Uri, key: String, length: Long, writers: MutableList<CacheWriter>) {
        // Several passes, because a range can be read in full and still not be stored. The player
        // holds a write lock on whatever region it is reading, or a worker could hit a transient
        // network error. Re-checking what actually landed and refetching gaps ensures the entire
        // track is fully cached on disk.
        repeat(FILL_PASSES) { pass ->
            val missing = missingRanges(key, length)
            if (missing.isEmpty()) return
            try {
                fillRanges(uri, key, missing, writers, probeFirst = pass == 0)
            } catch (cancelled: java.io.InterruptedIOException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Pass $pass of fillRanges for $uri had errors: ${error.message}")
            }
        }
    }

    /** Chunk-sized regions of the resource that are not yet completely on disk. */
    private fun missingRanges(key: String, length: Long): List<Pair<Long, Long>> {
        // Bounded below so tiny chunks do not turn one download into hundreds of requests, and
        // above so a long track does not exceed the range count either.
        val chunk = maxOf(MIN_CHUNK_BYTES, (length + MAX_RANGES - 1) / MAX_RANGES)
        val missing = ArrayList<Pair<Long, Long>>()
        var start = 0L
        while (start < length) {
            val size = minOf(chunk, length - start)
            if (cache.getCachedBytes(key, start, size) < size) missing += start to size
            start += size
        }
        return missing
    }

    private fun fillRanges(
        uri: Uri,
        key: String,
        ranges: List<Pair<Long, Long>>,
        writers: MutableList<CacheWriter>,
        probeFirst: Boolean,
    ) {
        if (ranges.isEmpty()) return

        // One bounded request alone before the rest, so a server that advertises ranges but
        // ignores them does not get several full-length responses opened against it at once.
        val remaining = if (probeFirst) {
            writeRange(uri, key, ranges.first(), writers)
            ranges.drop(1)
        } else {
            ranges
        }
        if (remaining.isEmpty()) return
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = minOf(CONCURRENCY, remaining.size)
        val latch = java.util.concurrent.CountDownLatch(workers)
        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)

        repeat(workers) {
            rangeExecutor.execute {
                try {
                    while (failure.get() == null) {
                        val index = next.getAndIncrement()
                        if (index >= remaining.size) break
                        writeRange(uri, key, remaining[index], writers)
                    }
                } catch (error: Exception) {
                    failure.compareAndSet(null, error)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        failure.get()?.let { throw it }
    }

    private fun writeRange(
        uri: Uri,
        key: String,
        range: Pair<Long, Long>,
        writers: MutableList<CacheWriter>,
    ) {
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setKey(key)
            .setPosition(range.first)
            .setLength(range.second)
            .build()
        val writer = CacheWriter(writeFactory.createDataSource(), spec, null, null)
        writers += writer
        writer.cache()
    }

    private fun fetchWhole(uri: Uri, key: String, writers: MutableList<CacheWriter>) {
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setKey(key)
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        val writer = CacheWriter(writeFactory.createDataSource(), spec, null, null)
        writers += writer
        writer.cache()
        val length = cache.getContentMetadata(key).get(
            androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH,
            C.LENGTH_UNSET.toLong(),
        )
        if (length <= 0) {
            val spans = cache.getCachedSpans(key)
            val total = spans.sumOf { it.length }
            if (total > 0) {
                val mutations = androidx.media3.datasource.cache.ContentMetadataMutations()
                androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(mutations, total)
                cache.applyContentMetadataMutations(key, mutations)
            }
        }
    }

    /** Stops prefetching anything not in [keep], so a queue edit does not keep downloading. */
    fun retainOnly(keep: Collection<Uri>) {
        val wanted = keep.map(::cacheKey).toSet()
        for ((key, writers) in inFlight) {
            if (key in wanted) continue
            synchronized(writers) { writers.forEach { it.cancel() } }
        }
    }

    /**
     * Exposes a cached track to [android.media.MediaExtractor], which needs random access.
     *
     * When fully cached, reads directly from the on-disk span files via RandomAccessFile, bypassing
     * CacheDataSource allocation and lock overhead for maximum demuxing throughput.
     */
    fun mediaDataSource(uri: Uri): MediaDataSource? {
        if (!::readFactory.isInitialized) return null
        val key = cacheKey(uri)
        val spans = synchronized(cache) {
            val raw = cache.getCachedSpans(key)
            raw.filter { it.file != null && it.file!!.exists() }.sortedBy { it.position }
        }
        val totalLength = cache.getContentMetadata(key).get(
            androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH,
            C.LENGTH_UNSET.toLong(),
        )

        val isFullyCached = totalLength > 0 && spans.isNotEmpty() && spans.first().position == 0L && run {
            var covered = 0L
            for (span in spans) {
                if (span.position > covered) return@run false
                covered = maxOf(covered, span.position + span.length)
            }
            covered >= totalLength
        }

        if (isFullyCached) {
            val files = spans.map { span ->
                SpanEntry(span.position, span.position + span.length, java.io.RandomAccessFile(span.file!!, "r"))
            }
            return object : MediaDataSource() {
                override fun readAt(at: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (size == 0) return 0
                    val entry = files.firstOrNull { it.start <= at && at < it.end } ?: return -1
                    val toRead = minOf(size.toLong(), entry.end - at).toInt()
                    synchronized(entry.raf) {
                        entry.raf.seek(at - entry.start)
                        return entry.raf.read(buffer, offset, toRead)
                    }
                }

                override fun getSize(): Long = totalLength

                override fun close() {
                    files.forEach { runCatching { it.raf.close() } }
                }
            }
        }

        return object : MediaDataSource() {
            private var source: CacheDataSource? = null
            private var position = -1L

            private fun openAt(at: Long): CacheDataSource {
                close()
                val spec = DataSpec.Builder()
                    .setUri(uri)
                    .setKey(key)
                    .setPosition(at)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
                val opened = readFactory.createDataSource()
                opened.open(spec)
                source = opened
                position = at
                return opened
            }

            override fun readAt(at: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (size == 0) return 0
                val active = if (source != null && position == at) source!! else openAt(at)
                val read = active.read(buffer, offset, size)
                if (read > 0) position += read
                return read
            }

            override fun getSize(): Long = totalLength

            override fun close() {
                runCatching { source?.close() }
                source = null
                position = -1L
            }
        }
    }

    private class SpanEntry(val start: Long, val end: Long, val raf: java.io.RandomAccessFile)

    fun release() {
        for ((_, writers) in inFlight) synchronized(writers) { writers.forEach { it.cancel() } }
        inFlight.clear()
        rangeExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
        cache.release()
    }

    private companion object {
        const val TAG = "OrchardStreamCache"
        /**
         * Held in the app's own files directory rather than the cache directory: Android empties
         * the latter under storage pressure without warning, and losing a track mid-analysis is
         * worse than holding the space. The ceiling comes from OrchardSettings.cacheSizeMb.
         */
        const val CACHE_DIRECTORY = "stream-cache"

        /** Matches Orchard desktop's audioFetch.js, where the same throttle was measured. */
        const val CONCURRENCY = 6
        const val MIN_CHUNK_BYTES = 512L * 1024L
        const val MAX_RANGES = 64L

        /**
         * How many times to re-check and refill. Two is enough in practice: the first pass races
         * the playhead over one region, and by the second that region has been released.
         */
        const val FILL_PASSES = 3

    }
}
