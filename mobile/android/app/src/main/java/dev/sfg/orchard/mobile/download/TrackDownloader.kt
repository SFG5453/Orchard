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

import android.util.Log
import dev.sfg.orchard.mobile.auth.YouTubeSessionProvider
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.playback.ResolvedStream
import dev.sfg.orchard.mobile.playback.YouTubeStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Downloads audio tracks through the same client-profile resolver used for playback.
 *
 * NewPipe is intentionally absent from the retry path. [YouTubeStreamResolver] invokes it only
 * when the user explicitly selects [AudioQuality.MAX]; every other quality remains Innertube-only.
 */
class TrackDownloader(
    private val http: OkHttpClient,
    sessionProvider: YouTubeSessionProvider? = null,
    private val store: DownloadStore,
    poTokenMinter: () -> dev.sfg.orchard.mobile.playback.YouTubePoTokenMinter? = { null },
    challengeSolver: () -> dev.sfg.orchard.mobile.playback.YouTubeChallengeSolver? = { null },
    qualityProvider: () -> AudioQuality = { AudioQuality.HIGH },
) {
    private val streamResolver by lazy {
        YouTubeStreamResolver(
            client = http,
            sessionProvider = sessionProvider,
            qualityProvider = qualityProvider,
            poTokenMinter = poTokenMinter(),
            challengeSolver = challengeSolver(),
        )
    }
    private data class ResumeIdentity(
        val mimeType: String,
        val contentLength: Long,
        val bitrateKbps: Int,
        val supportsParallelRanges: Boolean,
    )

    private val resumeIdentities = ConcurrentHashMap<String, ResumeIdentity>()
    private val knownContentLengths = ConcurrentHashMap<String, Long>()
    private val parallelRangeRejected = ConcurrentHashMap.newKeySet<String>()

    fun interface ProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long, progress: Float)
    }

    suspend fun download(
        item: DownloadItem,
        progressListener: ProgressListener? = null,
    ): DownloadItem = withContext(Dispatchers.IO) {
        val videoId = item.track.id
        if (videoId.isBlank()) {
            return@withContext item.copy(
                status = DownloadStatus.FAILED,
                errorMessage = "Invalid video ID",
            )
        }

        Log.d(TAG, "Resolving stream for track: ${item.track.title} ($videoId)")

        // Uploads live only in the listener's own library, so the guest client catalog cannot see
        // them. Saying so before resolving is what separates "sign in to reach this" from the
        // "video unavailable" a deleted track earns.
        if (item.track.isUpload) streamResolver.markAccountOnly(videoId)

        val resolved: ResolvedStream = runCatching { streamResolver.resolve(videoId) }
            .onFailure { Log.w(TAG, "Stream resolution failed for $videoId", it) }
            .getOrNull()
            ?: run {
            return@withContext item.copy(
                status = DownloadStatus.FAILED,
                errorMessage = "Could not resolve audio stream URL",
            )
        }

        val ext = when {
            resolved.mimeType.contains("webm", ignoreCase = true) -> "webm"
            resolved.mimeType.contains("mp4", ignoreCase = true) || resolved.mimeType.contains("m4a", ignoreCase = true) -> "m4a"
            resolved.mimeType.contains("opus", ignoreCase = true) -> "opus"
            else -> "webm"
        }

        val tempFile = store.getTempFile(videoId)
        val targetFile = store.getTargetFile(videoId, ext)
        val resumeIdentity = ResumeIdentity(
            mimeType = resolved.mimeType,
            contentLength = resolved.contentLength,
            bitrateKbps = resolved.bitrateKbps,
            supportsParallelRanges = resolved.supportsParallelRanges,
        )
        val previousIdentity = resumeIdentities.put(videoId, resumeIdentity)
        if (previousIdentity != null && previousIdentity != resumeIdentity && tempFile.exists()) {
            Log.i(TAG, "Discarding incompatible partial download for $videoId")
            tempFile.delete()
            knownContentLengths.remove(videoId)
        }

        val expectedLength = resolved.contentLength.takeIf { it > 0L }
            ?: knownContentLengths[videoId]
            ?: 0L
        val useParallelRanges = resolved.supportsParallelRanges &&
            expectedLength >= PARALLEL_RANGE_THRESHOLD &&
            videoId !in parallelRangeRejected

        try {
            if (useParallelRanges) {
                // A range download writes chunks out of order, so an old sequential partial cannot
                // be reused safely. This mode is restricted to NewPipe URLs with an exact length.
                tempFile.delete()
                downloadByRanges(
                    stream = resolved,
                    tempFile = tempFile,
                    expectedLength = expectedLength,
                    progressListener = progressListener,
                )
            } else {
                downloadSequential(
                    videoId = videoId,
                    stream = resolved,
                    tempFile = tempFile,
                    progressListener = progressListener,
                )
            }

            if (tempFile.length() == 0L) {
                tempFile.delete()
                return@withContext item.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "Downloaded file is empty",
                )
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(TAG, "Download completed for ${item.track.title}: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            resumeIdentities.remove(videoId)
            knownContentLengths.remove(videoId)
            parallelRangeRejected.remove(videoId)

            item.copy(
                status = DownloadStatus.COMPLETED,
                progress = 1.0f,
                bytesDownloaded = targetFile.length(),
                totalBytes = targetFile.length(),
                filePath = targetFile.absolutePath,
                mimeType = resolved.mimeType,
                downloadedAtMs = System.currentTimeMillis(),
                errorMessage = "",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $videoId", e)
            if (useParallelRanges) {
                // A sparse file cannot be resumed by the sequential path. Drop it and make the
                // next manager attempt use the conservative resumable downloader.
                parallelRangeRejected.add(videoId)
                tempFile.delete()
                streamResolver.invalidate(videoId)
            } else if (e is StreamHttpException) {
                streamResolver.reject(videoId, resolved, e.responseCode)
            } else {
                // A reset or early EOF is a transport interruption, not evidence that the client
                // profile is invalid. Keep the bytes and mint a fresh URL for the next range.
                streamResolver.invalidate(videoId)
            }
            item.copy(
                status = DownloadStatus.FAILED,
                bytesDownloaded = tempFile.length(),
                totalBytes = resolved.contentLength.takeIf { it > 0L }
                    ?: knownContentLengths[videoId]
                    ?: 0L,
                progress = progress(tempFile.length(), resolved.contentLength.takeIf { it > 0L }
                    ?: knownContentLengths[videoId]
                    ?: 0L),
                errorMessage = e.localizedMessage ?: "Download interrupted",
            )
        }
    }

    private suspend fun downloadByRanges(
        stream: ResolvedStream,
        tempFile: File,
        expectedLength: Long,
        progressListener: ProgressListener?,
    ) = withContext(Dispatchers.IO) {
        val ranges = buildList {
            var start = 0L
            while (start < expectedLength) {
                val end = minOf(start + RANGE_CHUNK_SIZE - 1L, expectedLength - 1L)
                add(start..end)
                start = end + 1L
            }
        }
        val downloaded = AtomicLong(0L)

        RandomAccessFile(tempFile, "rw").use { output ->
            output.setLength(expectedLength)
            progressListener?.onProgress(0L, expectedLength, 0f)

            // Process fixed-size batches so one track never opens more than four GVS requests.
            for (batch in ranges.chunked(RANGE_CONCURRENCY)) {
                coroutineScope {
                    batch.map { range ->
                        async(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(stream.url)
                                .header("Accept", "*/*")
                                .header("Accept-Encoding", "identity")
                                .apply {
                                    stream.requestHeaders.forEach { (name, value) -> header(name, value) }
                                }
                                .header("Range", "bytes=${range.first}-${range.last}")
                                .build()

                            http.newCall(request).execute().use { response ->
                                if (response.code != 206) {
                                    throw StreamHttpException(response.code)
                                }
                                val returnedRange = response.header("Content-Range")
                                    ?.substringBefore('/')
                                    ?.removePrefix("bytes ")
                                if (returnedRange != "${range.first}-${range.last}") {
                                    throw java.io.IOException(
                                        "Server returned unexpected range ${response.header("Content-Range")}",
                                    )
                                }

                                val expectedRangeBytes = range.last - range.first + 1L
                                val input = response.body.byteStream()
                                val buffer = ByteArray(BUFFER_SIZE)
                                var rangeBytes = 0L

                                while (rangeBytes < expectedRangeBytes) {
                                    if (!coroutineContext.isActive) {
                                        throw java.io.InterruptedIOException("Download cancelled")
                                    }
                                    val remaining = expectedRangeBytes - rangeBytes
                                    val bytesRead = input.read(
                                        buffer,
                                        0,
                                        minOf(buffer.size.toLong(), remaining).toInt(),
                                    )
                                    if (bytesRead == -1) break

                                    val position = range.first + rangeBytes
                                    val bytes = ByteBuffer.wrap(buffer, 0, bytesRead)
                                    while (bytes.hasRemaining()) {
                                        output.channel.write(bytes, position + bytes.position())
                                    }
                                    rangeBytes += bytesRead
                                    val totalDownloaded = downloaded.addAndGet(bytesRead.toLong())
                                    progressListener?.onProgress(
                                        totalDownloaded,
                                        expectedLength,
                                        progress(totalDownloaded, expectedLength),
                                    )
                                }

                                if (rangeBytes != expectedRangeBytes) {
                                    throw java.io.EOFException(
                                        "Range ${range.first}-${range.last} closed after " +
                                            "$rangeBytes of $expectedRangeBytes bytes",
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            output.channel.force(false)
        }

        if (downloaded.get() != expectedLength || tempFile.length() != expectedLength) {
            throw java.io.EOFException(
                "Parallel download wrote ${downloaded.get()} of $expectedLength bytes",
            )
        }
    }

    private suspend fun downloadSequential(
        videoId: String,
        stream: ResolvedStream,
        tempFile: File,
        progressListener: ProgressListener?,
    ) = withContext(Dispatchers.IO) {
        var expectedLength = stream.contentLength.takeIf { it > 0L }
            ?: knownContentLengths[videoId]
            ?: 0L
        if (expectedLength > 0L && tempFile.length() > expectedLength) {
            tempFile.delete()
        }
        val requestedOffset = tempFile.length()
        if (expectedLength > 0L && requestedOffset == expectedLength) {
            progressListener?.onProgress(expectedLength, expectedLength, 1f)
            return@withContext
        }
        // Every request carries an explicit bounded range, walking the file in [RANGE_CHUNK_SIZE]
        // pieces the way the parallel path above already does. A request with no Range header is
        // answered too, but throttled to a trickle and then cut off short of the end — measured
        // at 3145712 of 3497127 bytes over 98 seconds — so "just read the body" is not an option
        // even when the URL is perfectly good.
        var totalRead = requestedOffset
        progressListener?.onProgress(totalRead, expectedLength, progress(totalRead, expectedLength))

        do {
            val chunkStart = totalRead
            val chunkEnd = if (expectedLength > 0L) {
                minOf(chunkStart + RANGE_CHUNK_SIZE, expectedLength) - 1L
            } else {
                chunkStart + RANGE_CHUNK_SIZE - 1L
            }
            val request = Request.Builder()
                .url(stream.url)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=$chunkStart-$chunkEnd")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw StreamHttpException(response.code)
                }
                val body = response.body
                // The total only has to be discovered once, from the first response that states it.
                expectedLength = expectedLength.takeIf { it > 0L }
                    ?: response.header("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.toLongOrNull()
                    ?: 0L
                if (expectedLength > 0L) knownContentLengths[videoId] = expectedLength

                FileOutputStream(tempFile, chunkStart > 0L).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (body.byteStream().read(buffer).also { bytesRead = it } != -1) {
                        if (!coroutineContext.isActive) {
                            throw java.io.InterruptedIOException("Download cancelled")
                        }
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        progressListener?.onProgress(
                            totalRead,
                            expectedLength,
                            progress(totalRead, expectedLength),
                        )
                    }
                    outputStream.flush()
                }
                // A piece that returned nothing would otherwise spin this loop forever.
                if (totalRead <= chunkStart) {
                    throw java.io.EOFException("Stream ended at $totalRead of $expectedLength bytes")
                }
            }
        } while (expectedLength > 0L && totalRead < expectedLength)

        if (expectedLength > 0L && tempFile.length() != expectedLength) {
            throw java.io.EOFException(
                "Connection closed after ${tempFile.length()} of $expectedLength bytes",
            )
        }
    }

    private fun progress(downloaded: Long, total: Long): Float =
        if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    private class StreamHttpException(val responseCode: Int) :
        java.io.IOException("HTTP $responseCode downloading stream")

    companion object {
        private const val TAG = "TrackDownloader"
        private const val BUFFER_SIZE = 32 * 1024
        private const val RANGE_CHUNK_SIZE = 1024 * 1024L
        private const val RANGE_CONCURRENCY = 4
        private const val PARALLEL_RANGE_THRESHOLD = 2 * RANGE_CHUNK_SIZE
    }
}
