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

package dev.sfg.orchard.mobile.playback.slop

import dev.sfg.orchard.mobile.playback.ResolvedStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SlopDownload(private val http: OkHttpClient) {
    suspend fun save(stream: ResolvedStream, file: File) {
        val size = stream.contentLength
        val uri = java.net.URI(stream.url)
        require(uri.scheme == "https" && uri.host?.let { it.endsWith(".googlevideo.com") || it.endsWith(".c.youtube.com") } == true && size in 1..MAX_BYTES)
        file.parentFile?.mkdirs()
        try {
            file.outputStream().use { out ->
                var start = 0L
                while (start < size) {
                    currentCoroutineContext().ensureActive()
                    val end = minOf(size - 1, start + (1 shl 20) - 1)
                    val request = Request.Builder().url(stream.url)
                        .apply { stream.requestHeaders.forEach { (k, v) -> header(k, v) } }
                        .header("Accept-Encoding", "identity").header("Range", "bytes=$start-$end").build()
                    out.write(range(request, start, end, size))
                    start = end + 1
                }
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private suspend fun range(request: Request, start: Long, end: Long, size: Long): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            check(it.code == 206 && it.header("Content-Range") == "bytes $start-$end/$size")
                            val source = it.body.source()
                            val bytes = source.readByteArray(end - start + 1)
                            check(source.exhausted()) { "Oversized scan range" }
                            bytes
                        }
                    }
                    if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            })
        }

    companion object { const val MAX_BYTES = 16L * 1024 * 1024 }
}
