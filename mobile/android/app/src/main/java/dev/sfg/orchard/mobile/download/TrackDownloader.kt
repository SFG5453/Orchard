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
import dev.sfg.orchard.mobile.playback.NewPipeStreamResolver
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Downloads audio tracks with maximum speed by resolving streams via [YouTubeStreamResolver]
 * (with [NewPipeStreamResolver] fallback) and fetching multiple concurrent byte-ranges to bypass
 * YouTube's progressive per-stream rate limiting.
 */
class TrackDownloader(
    private val http: OkHttpClient,
    sessionProvider: YouTubeSessionProvider? = null,
    private val store: DownloadStore,
) {
    private val streamResolver by lazy { YouTubeStreamResolver(client = http, sessionProvider = sessionProvider) }
    private val newPipeResolver by lazy { NewPipeStreamResolver(http, sessionProvider) }

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

        // 1. Fast resolution: YouTubeStreamResolver first (sub-250ms), fallback to NewPipe
        val resolved: ResolvedStream = try {
            streamResolver.resolve(videoId)
        } catch (e: Exception) {
            Log.w(TAG, "Primary stream resolution failed, attempting fallback for $videoId", e)
            newPipeResolver.resolve(videoId)
        } ?: newPipeResolver.resolve(videoId) ?: run {
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

        try {
            // 2. Probe content length with a small initial range request (0..1023)
            val probeRequest = Request.Builder()
                .url(resolved.url)
                .header("User-Agent", resolved.userAgent)
                .header("Range", "bytes=0-1023")
                .build()

            var totalContentLength = 0L
            var initialBytes: ByteArray? = null

            http.newCall(probeRequest).execute().use { probeResponse ->
                if (probeResponse.isSuccessful || probeResponse.code == 206) {
                    val contentRange = probeResponse.header("Content-Range").orEmpty()
                    val slashIdx = contentRange.lastIndexOf('/')
                    if (slashIdx != -1) {
                        totalContentLength = contentRange.substring(slashIdx + 1).trim().toLongOrNull() ?: 0L
                    }
                    val body = probeResponse.body
                    if (totalContentLength <= 0L) {
                        totalContentLength = body.contentLength()
                    }
                    initialBytes = body.bytes()
                }
            }

            val chunk0 = initialBytes
            if (totalContentLength > 0L && chunk0 != null) {
                // High-speed concurrent ranged download
                downloadByRanges(
                    url = resolved.url,
                    userAgent = resolved.userAgent,
                    totalLength = totalContentLength,
                    initialChunk = chunk0,
                    tempFile = tempFile,
                    progressListener = progressListener,
                )
            } else {
                // Fallback to sequential stream
                downloadSequential(
                    url = resolved.url,
                    userAgent = resolved.userAgent,
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
            if (tempFile.exists()) {
                tempFile.delete()
            }
            item.copy(
                status = DownloadStatus.FAILED,
                errorMessage = e.localizedMessage ?: "Download failed",
            )
        }
    }

    private suspend fun downloadByRanges(
        url: String,
        userAgent: String,
        totalLength: Long,
        initialChunk: ByteArray,
        tempFile: File,
        progressListener: ProgressListener?,
    ) = coroutineScope {
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(totalLength)
            val channel = raf.channel

            // Write the first probed chunk
            val initialWritten = initialChunk.size.toLong()
            channel.write(ByteBuffer.wrap(initialChunk), 0L)

            val bytesReadAccumulator = AtomicLong(initialWritten)
            progressListener?.onProgress(initialWritten, totalLength, (initialWritten.toFloat() / totalLength.toFloat()).coerceIn(0f, 1f))

            if (initialWritten >= totalLength) {
                raf.fd.sync()
                return@coroutineScope
            }

            // Partition remaining bytes into chunks of CHUNK_SIZE
            val chunks = mutableListOf<Pair<Long, Long>>()
            var start = initialWritten
            while (start < totalLength) {
                val end = minOf(start + CHUNK_SIZE - 1, totalLength - 1)
                chunks.add(start to end)
                start = end + 1
            }

            // Distribute across concurrent coroutine workers
            val chunkQueue = ConcurrentLinkedQueue(chunks)
            val workers = (0 until CONCURRENCY).map {
                async(Dispatchers.IO) {
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (isActive) {
                        val chunk = chunkQueue.poll() ?: break
                        val (chunkStart, chunkEnd) = chunk
                        val rangeHeader = "bytes=$chunkStart-$chunkEnd"

                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent", userAgent)
                            .header("Range", rangeHeader)
                            .build()

                        http.newCall(request).execute().use { response ->
                            if (response.code != 206) {
                                throw java.io.IOException("HTTP ${response.code} on range $rangeHeader")
                            }
                            val body = response.body
                            val stream = body.byteStream()
                            var currentPos = chunkStart
                            var n: Int
                            while (stream.read(buffer).also { n = it } != -1) {
                                if (!isActive) throw java.io.InterruptedIOException("Download cancelled")
                                channel.write(ByteBuffer.wrap(buffer, 0, n), currentPos)
                                currentPos += n
                                val totalNow = bytesReadAccumulator.addAndGet(n.toLong())
                                val p = (totalNow.toFloat() / totalLength.toFloat()).coerceIn(0f, 1f)
                                progressListener?.onProgress(totalNow, totalLength, p)
                            }
                            val expectedBytes = chunkEnd - chunkStart + 1
                            if (currentPos - chunkStart != expectedBytes) {
                                throw java.io.EOFException("Connection closed while downloading range $rangeHeader")
                            }
                        }
                    }
                }
            }

            workers.awaitAll()
            raf.fd.sync()
        }
    }

    private suspend fun downloadSequential(
        url: String,
        userAgent: String,
        tempFile: File,
        progressListener: ProgressListener?,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code} downloading stream")
            }
            val body = response.body
            val contentLength = body.contentLength()
            val inputStream = body.byteStream()

            FileOutputStream(tempFile).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var totalRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!coroutineContext.isActive) {
                        throw java.io.InterruptedIOException("Download cancelled")
                    }
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val progress = if (contentLength > 0) {
                        (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    progressListener?.onProgress(totalRead, contentLength, progress)
                }
                outputStream.flush()
            }
        }
    }

    companion object {
        private const val TAG = "TrackDownloader"
        private const val BUFFER_SIZE = 32 * 1024
        private const val CHUNK_SIZE = 1024 * 1024L
        private const val CONCURRENCY = 3
    }
}
