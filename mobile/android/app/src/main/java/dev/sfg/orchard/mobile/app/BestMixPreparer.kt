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

package dev.sfg.orchard.mobile.app

import android.util.Log
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.ResolvedStream
import dev.sfg.orchard.mobile.playback.smart.BestMixSorter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Gathers Best Mix features as desktop's `BestMixController` does: cached rows first, then every
 * missing song downloaded at saver quality, then the shared Rust analysis of each. Saver is the
 * encode desktop analyzes; features from another encode sort a queue differently.
 */
internal class BestMixPreparer(private val graph: OrchardGraph, cacheRoot: File) {
    private val audioDir = File(cacheRoot, "best-mix-v2")
    private val http = graph.http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    /** Ensures every analyzable song in [tracks] has a feature row. */
    suspend fun prepare(tracks: List<Track>, label: String, onProgress: (String) -> Unit) {
        val store = graph.bestMixFeatures
        val missing = withContext(Dispatchers.IO) {
            tracks.distinctBy(Track::id).filter { track ->
                val duration = track.durationMs / 1000.0
                track.id.isNotBlank() && duration in 2.0..7200.0 && !store.has(track.id, duration)
            }
        }
        Log.i(TAG, "$label reused ${tracks.size - missing.size}/${tracks.size} cached analyses")
        if (missing.isEmpty()) return
        // Every download before any analysis, so decoding never holds a network slot.
        val files = download(missing, onProgress)
        if (files.isNotEmpty()) analyze(files, onProgress)
    }

    private suspend fun download(tracks: List<Track>, onProgress: (String) -> Unit): List<Pair<Track, File>> {
        val done = AtomicInteger(0)
        val slots = Semaphore(DOWNLOAD_SLOTS)
        onProgress("Downloading tracks (0/${tracks.size})...")
        return coroutineScope {
            tracks.map { track ->
                async(Dispatchers.IO) {
                    slots.withPermit {
                        fetch(track).also { onProgress("Downloading tracks (${done.incrementAndGet()}/${tracks.size})...") }
                    }?.let { track to it }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** The saver stream on disk, or the listener's own download when the provider cannot serve one. */
    private suspend fun fetch(track: Track): File? {
        val file = File(audioDir, "${track.id}.audio")
        if (file.length() in 1..MAX_BYTES) return file
        val saved = try {
            save(graph.streams.resolveSaver(track), file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Best Mix download failed for ${track.id}", error)
            false
        }
        return if (saved) file else graph.downloads.getDownloadedFile(track.id)
    }

    /** Desktop's fetch: googlevideo only, at most 24 MB, 1 MB ranges each checked against Content-Range. */
    private suspend fun save(stream: ResolvedStream, file: File): Boolean {
        val size = stream.contentLength
        val host = runCatching { java.net.URI(stream.url) }.getOrNull()
        if (host?.scheme != "https" || host.host?.endsWith(".googlevideo.com") != true || size !in 1..MAX_BYTES) {
            return false
        }
        audioDir.mkdirs()
        val partial = File(audioDir, "${file.name}.part")
        try {
            partial.outputStream().use { out ->
                var offset = 0L
                while (offset < size) {
                    currentCoroutineContext().ensureActive()
                    val end = minOf(size - 1, offset + CHUNK_BYTES - 1)
                    val request = Request.Builder().url(stream.url)
                        .header("Accept-Encoding", "identity")
                        .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
                        .header("Range", "bytes=$offset-$end")
                        .build()
                    http.newCall(request).execute().use { response ->
                        val range = response.header("Content-Range")?.trim()
                        if (response.code != 206 || range == null || !range.startsWith("bytes $offset-$end/")) {
                            throw IOException("Unexpected range reply ${response.code} $range")
                        }
                        val bytes = response.body.bytes()
                        if (bytes.size.toLong() != end - offset + 1) throw IOException("Short range")
                        out.write(bytes)
                    }
                    offset = end + 1
                }
            }
            return partial.renameTo(file)
        } finally {
            partial.delete()
        }
    }

    private suspend fun analyze(files: List<Pair<Track, File>>, onProgress: (String) -> Unit) {
        val total = files.size
        val completed = AtomicInteger(0)
        // Desktop's worker count: each holds one decoded song and one core.
        val semaphore = Semaphore((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))
        onProgress("Analyzing audio (0/$total)...")
        coroutineScope {
            files.map { (track, file) ->
                async(Dispatchers.Default) {
                    semaphore.withPermit {
                        analyzeTrack(track, file)
                        // Features are what is kept; the audio would only cost the phone storage.
                        if (file.parentFile == audioDir) file.delete()
                        onProgress("Analyzing audio (${completed.incrementAndGet()}/$total)...")
                    }
                }
            }.awaitAll()
        }
    }

    private fun analyzeTrack(track: Track, file: File) = try {
        BestMixSorter.analyze(graph.bestMixFeatures, track, file)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        // One unanalysable song keeps its queue position; it must not cancel the other workers.
        Log.w(TAG, "Best Mix analysis failed for ${track.id}", error)
        false
    }

    private companion object {
        const val TAG = "BestMixPreparer"
        /** Desktop's six concurrent downloads, 1 MB ranges and 24 MB ceiling. */
        const val DOWNLOAD_SLOTS = 6
        const val CHUNK_BYTES = 1L shl 20
        const val MAX_BYTES = 24L * 1024 * 1024
    }
}
