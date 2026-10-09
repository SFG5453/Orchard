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
import dev.sfg.orchard.mobile.provider.ProviderHost
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private const val TAG = "QobuzStreamServer"
private const val READ_CHUNK = 512 * 1024L
private const val MAX_SESSIONS = 16

/**
 * Loopback HTTP server that gives Media3 a seekable URL for a Qobuz FLAC stream.
 * Bytes come from the provider's `playback.read`, which owns segments and decryption.
 */
class QobuzStreamServer(private val provider: ProviderHost) {
    private class Session(val totalBytes: Long, val remote: RemoteQobuz?) {
        @Volatile var reported = false
    }

    private val sessions = object : LinkedHashMap<String, Session>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>) = size > MAX_SESSIONS
    }
    private val workers = Executors.newCachedThreadPool { Thread(it, "QobuzStream").apply { isDaemon = true } }
    private var serverSocket: ServerSocket? = null

    @Synchronized
    fun register(playbackId: String, totalBytes: Long, remote: RemoteQobuz? = null): String {
        val socket = serverSocket ?: ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also {
            serverSocket = it
            workers.execute { acceptLoop(it) }
        }
        synchronized(sessions) { sessions[playbackId] = Session(totalBytes, remote) }
        return "http://127.0.0.1:${socket.localPort}/qobuz/$playbackId"
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(sessions) { sessions.clear() }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                workers.execute {
                    try {
                        client.use(::handle)
                    } catch (e: Exception) {
                        Log.d(TAG, "Client closed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (!socket.isClosed) Log.w(TAG, "Accept failed: ${e.message}")
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val request = (readLine(input) ?: return).split(" ")
        if (request.size < 2) return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input)?.takeIf { it.isNotBlank() } ?: break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val playbackId = request[1].removePrefix("/qobuz/").substringBefore('?')
        val session = synchronized(sessions) { sessions[playbackId] }
        if (session == null) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val total = session.totalBytes
        val range = parseRange(headers["range"], total)
        if (range == null) {
            output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val (start, end) = range
        val partial = headers["range"] != null
        val head = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: audio/flac\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray())
        if (request[0].uppercase() != "GET") return
        reportStarted(playbackId, session)
        stream(playbackId, session, start, end, output)
    }

    // Pull in chunks so a long seek never holds a whole track in memory.
    private fun stream(playbackId: String, session: Session, start: Long, end: Long, output: OutputStream) {
        var position = start
        while (position <= end) {
            val last = minOf(end, position + READ_CHUNK - 1)
            val bytes = runBlocking {
                session.remote?.read(playbackId, position, last) ?: provider.invokeBytes(
                    "playback.read",
                    JSONObject().put("playbackId", playbackId).put("start", position).put("end", last),
                )
            }
            if (bytes.isEmpty()) return
            output.write(bytes)
            position += bytes.size
        }
        output.flush()
    }

    // Qobuz counts a play when audio is first requested.
    private fun reportStarted(playbackId: String, session: Session) {
        if (session.reported) return
        session.reported = true
        session.remote?.let {
            it.report(playbackId, true, 0.0)
            return
        }
        runCatching {
            runBlocking {
                provider.invokeRaw("playback.started", JSONObject().put("playbackId", playbackId).put("position", 0))
            }
        }
    }

    /** Inclusive byte range for a Range header, or null when it lies outside the stream. */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        val spec = header?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")?.trim()
            ?: return 0L to total - 1
        val dash = spec.indexOf('-')
        if (dash < 0) return 0L to total - 1
        val first = spec.substring(0, dash).trim()
        val second = spec.substring(dash + 1).trim()
        val start = if (first.isEmpty()) max(0L, total - (second.toLongOrNull() ?: 0L)) else first.toLongOrNull() ?: 0L
        val end = if (first.isEmpty() || second.isEmpty()) total - 1 else second.toLongOrNull() ?: (total - 1)
        if (start >= total) return null
        return start to end.coerceIn(start, total - 1)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII)
            if (b == '\n'.code) return bytes.toString(Charsets.US_ASCII)
            if (b != '\r'.code) bytes.write(b)
        }
    }
}
