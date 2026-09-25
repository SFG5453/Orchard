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

package dev.sfg.orchard.mobile.qobuz

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private const val TAG = "QobuzStreamServer"

class QobuzPlaybackSession(
    val playbackId: String,
    val source: QobuzStreamSource,
    val init: ParsedInitSegment,
    private val httpClient: OkHttpClient,
    val contentKey: ByteArray?,
) {
    // All sessions share one byte budget. Keeping six lossless segments per resolution
    // retained audio from every previous track (and every parallel cache range).
    private companion object {
        val segmentCache = QobuzSegmentCache(8 * 1024 * 1024)
    }

    fun fetchSegment(number: Int): ByteArray = segmentCache.getOrLoad(playbackId, number) {
        val url = source.urlTemplate.replace("\$SEGMENT\$", number.toString())
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", QOBUZ_USER_AGENT)
            .header("Accept", "*/*")
            .build()
        val rawBytes = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Qobuz segment $number failed (${response.code})")
            }
            response.body.bytes()
        }

        decryptQobuzAudioSegment(rawBytes, contentKey)
    }

    fun streamRange(rangeStart: Long, rangeEnd: Long, output: OutputStream) {
        var currentOffset = rangeStart
        val headerLength = init.flacHeader.size.toLong()

        // 1. Stream from flacHeader if within header range
        if (currentOffset < headerLength) {
            val headerSliceEnd = min(rangeEnd, headerLength - 1)
            val count = (headerSliceEnd - currentOffset + 1).toInt()
            output.write(init.flacHeader, currentOffset.toInt(), count)
            currentOffset += count
        }

        if (currentOffset > rangeEnd) return

        // 2. Stream audio segments
        val audioOffset = currentOffset - headerLength
        val audioTargetEnd = rangeEnd - headerLength

        // Find which segments cover audioOffset..audioTargetEnd
        for (index in init.segmentTable.indices) {
            val entry = init.segmentTable[index]
            val segmentStart = entry.byteOffset.toLong()
            val segmentEnd = segmentStart + entry.byteLength - 1

            if (segmentEnd < audioOffset) continue
            if (segmentStart > audioTargetEnd) break

            val segmentNumber = index + 1
            val segmentData = fetchSegment(segmentNumber)

            val inSegStart = max(0L, audioOffset - segmentStart).toInt()
            val inSegEnd = min(segmentData.size.toLong() - 1, audioTargetEnd - segmentStart).toInt()
            val lengthToWrite = inSegEnd - inSegStart + 1
            if (lengthToWrite > 0 && inSegStart < segmentData.size) {
                output.write(segmentData, inSegStart, lengthToWrite)
                currentOffset += lengthToWrite
            }
        }
    }
}

class QobuzStreamServer(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val sessions = ConcurrentHashMap<String, QobuzPlaybackSession>()
    private val serverDispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        Log.i(TAG, "QobuzStreamServer started on port ${socket.localPort}")

        serverJob = scope.launch(serverDispatcher) {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch(serverDispatcher) {
                        try {
                            handleClient(client)
                        } catch (e: Exception) {
                            Log.d(TAG, "Client socket error (likely disconnected): ${e.message}")
                        } finally {
                            runCatching { client.close() }
                        }
                    }
                } catch (e: Exception) {
                    if (socket.isClosed) break
                    Log.w(TAG, "Server socket accept error: ${e.message}")
                }
            }
        }
    }

    fun registerSession(session: QobuzPlaybackSession) {
        sessions[session.playbackId] = session
    }

    fun unregisterSession(playbackId: String) {
        sessions.remove(playbackId)
    }

    fun getSession(playbackId: String): QobuzPlaybackSession? = sessions[playbackId]

    fun urlFor(playbackId: String): String {
        start()
        return "http://127.0.0.1:$port/qobuz/$playbackId"
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isBlank()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            if (method == "OPTIONS") {
                val response = "HTTP/1.1 204 No Content\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Range, Content-Type\r\n" +
                    "Access-Control-Expose-Headers: Accept-Ranges, Content-Length, Content-Range\r\n" +
                    "\r\n"
                output.write(response.toByteArray(Charsets.US_ASCII))
                output.flush()
                return
            }

            val playbackId = path.removePrefix("/qobuz/").substringBefore('?')
            val session = sessions[playbackId]
            if (session == null) {
                val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                output.write(notFound.toByteArray(Charsets.US_ASCII))
                output.flush()
                return
            }

            val totalLength = session.init.totalLength
            val rangeHeader = headers["range"]

            var start = 0L
            var end = totalLength - 1
            var isPartial = false

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                val dash = rangeSpec.indexOf('-')
                if (dash >= 0) {
                    val first = rangeSpec.substring(0, dash).trim()
                    val second = rangeSpec.substring(dash + 1).trim()
                    if (first.isNotBlank()) {
                        start = first.toLongOrNull() ?: 0L
                        if (second.isNotBlank()) {
                            end = second.toLongOrNull() ?: (totalLength - 1)
                        }
                    } else if (second.isNotBlank()) {
                        val suffix = second.toLongOrNull() ?: 0L
                        start = max(0L, totalLength - suffix)
                    }
                    isPartial = true
                }
            }

            start = start.coerceIn(0L, totalLength - 1)
            end = end.coerceIn(start, totalLength - 1)
            val contentLength = end - start + 1

            val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
            val headersList = mutableListOf(
                statusLine,
                "Content-Type: audio/flac",
                "Accept-Ranges: bytes",
                "Content-Length: $contentLength",
                "Access-Control-Allow-Origin: *",
            )
            if (isPartial) {
                headersList.add("Content-Range: bytes $start-$end/$totalLength")
            }
            headersList.add("\r\n")

            val headerData = headersList.joinToString("\r\n").toByteArray(Charsets.US_ASCII)
            output.write(headerData)
            output.flush()

            if (method == "GET") {
                session.streamRange(start, end, output)
                output.flush()
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (bytes.isEmpty()) return null
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        sessions.clear()
    }
}
