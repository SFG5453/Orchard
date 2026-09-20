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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Makes Orchard's resolved audio available to a Cast receiver on the same LAN.
 *
 * A receiver cannot open the app-only `orchard://` URI, while handing it a short-lived YouTube URL
 * loses the exact request headers that URL was minted for. This tiny, capability-URL server
 * resolves on demand and relays byte ranges with the correct identity. It deliberately exposes no
 * index and rotates its unguessable token every time the playback service starts.
 */
class ChromecastStreamServer(
    context: Context,
    private val client: OkHttpClient,
    private val resolver: YouTubeStreamResolver,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0)
    private val acceptor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "orchard-cast-accept").apply { isDaemon = true }
    }
    private val clients = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "orchard-cast-stream").apply { isDaemon = true }
    }

    val port: Int
        get() = server.localPort

    init {
        acceptor.execute {
            while (running.get()) {
                try {
                    val socket = server.accept()
                    clients.execute { socket.use(::serve) }
                } catch (error: Exception) {
                    if (running.get()) Log.w(TAG, "Cast stream accept failed", error)
                }
            }
        }
    }

    fun urlFor(mediaId: String): String {
        require(mediaId.isNotBlank()) { "A media id is required for Cast playback" }
        val host =
            localIpv4Address()?.hostAddress
                ?: throw IllegalStateException(
                    "Connect this phone to the same Wi-Fi network as the Cast device"
                )
        val encoded = URLEncoder.encode(mediaId, StandardCharsets.UTF_8.name())
        return "http://$host:$port/cast/$token/$encoded"
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        runCatching {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2 || parts[0] !in setOf("GET", "HEAD")) {
                    writeError(output, 405, "Method Not Allowed")
                    return
                }
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }
                val prefix = "/cast/$token/"
                val rawPath = parts[1].substringBefore('?')
                if (!rawPath.startsWith(prefix)) {
                    writeError(output, 404, "Not Found")
                    return
                }
                val mediaId =
                    URLDecoder.decode(rawPath.removePrefix(prefix), StandardCharsets.UTF_8.name())
                if (mediaId.isBlank()) {
                    writeError(output, 404, "Not Found")
                    return
                }
                val stream = resolver.resolve(mediaId)
                val isHead = parts[0] == "HEAD"
                if (stream.url.startsWith("file:")) {
                    serveFile(output, File(URI(stream.url)), stream, headers["range"], isHead)
                } else {
                    serveRemote(output, stream, headers["range"], isHead)
                }
            }
            .onFailure { error ->
                Log.w(TAG, "Cast stream request failed", error)
                runCatching { writeError(output, 502, "Bad Gateway") }
            }
        runCatching { output.flush() }
    }

    private fun serveRemote(
        output: BufferedOutputStream,
        stream: ResolvedStream,
        range: String?,
        isHead: Boolean,
    ) {
        val request =
            Request.Builder()
                .url(stream.url)
                .get()
                .apply {
                    stream.requestHeaders.forEach { (name, value) -> header(name, value) }
                    if (!range.isNullOrBlank()) header("Range", range)
                }
                .build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            val code = response.code
            val reason = response.message.ifBlank { reasonFor(code) }
            writeStatus(output, code, reason)
            writeHeader(output, "Content-Type", response.header("Content-Type") ?: stream.mimeType)
            response.header("Content-Length")?.let { writeHeader(output, "Content-Length", it) }
            response.header("Content-Range")?.let { writeHeader(output, "Content-Range", it) }
            writeHeader(output, "Accept-Ranges", response.header("Accept-Ranges") ?: "bytes")
            writeHeader(output, "Cache-Control", "no-store")
            writeHeader(output, "Connection", "close")
            finishHeaders(output)
            if (!isHead && response.isSuccessful) {
                body.byteStream().use { source -> source.copyTo(output, COPY_BUFFER_BYTES) }
            }
        }
    }

    private fun serveFile(
        output: BufferedOutputStream,
        file: File,
        stream: ResolvedStream,
        rangeHeader: String?,
        isHead: Boolean,
    ) {
        if (!file.isFile) {
            writeError(output, 404, "Not Found")
            return
        }
        val length = file.length()
        val requested = parseRange(rangeHeader, length)
        val start = requested?.first ?: 0L
        val end = requested?.last ?: (length - 1).coerceAtLeast(0)
        val count = (end - start + 1).coerceAtLeast(0)
        writeStatus(
            output,
            if (requested == null) 200 else 206,
            if (requested == null) "OK" else "Partial Content",
        )
        writeHeader(output, "Content-Type", stream.mimeType)
        writeHeader(output, "Content-Length", count.toString())
        if (requested != null) writeHeader(output, "Content-Range", "bytes $start-$end/$length")
        writeHeader(output, "Accept-Ranges", "bytes")
        writeHeader(output, "Cache-Control", "no-store")
        writeHeader(output, "Connection", "close")
        finishHeaders(output)
        if (isHead || count == 0L) return
        RandomAccessFile(file, "r").use { source ->
            source.seek(start)
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var remaining = count
            while (remaining > 0) {
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun localIpv4Address(): Inet4Address? {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.activeNetwork
        if (
            active != null &&
                connectivity
                    .getNetworkCapabilities(active)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
        ) {
            connectivity
                .getLinkProperties(active)
                ?.linkAddresses
                ?.asSequence()
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.let {
                    return it
                }
        }
        return NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan", ignoreCase = true)) 0 else 1 }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server.close() }
        acceptor.shutdownNow()
        clients.shutdownNow()
    }

    private fun parseRange(value: String?, length: Long): LongRange? {
        if (value == null || !value.startsWith("bytes=") || length <= 0) return null
        val bounds = value.removePrefix("bytes=").substringBefore(',').split('-', limit = 2)
        if (bounds.size != 2) return null
        val start = bounds[0].toLongOrNull() ?: return null
        val end = bounds[1].toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        if (start !in 0 until length || end < start) return null
        return start..end
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < MAX_HEADER_LINE_BYTES) {
            val next = input.read()
            if (next < 0)
                return if (bytes.isEmpty()) null
                else bytes.toByteArray().toString(Charsets.ISO_8859_1)
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes += next.toByte()
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun writeError(output: BufferedOutputStream, code: Int, reason: String) {
        val body = "$code $reason\n".toByteArray()
        writeStatus(output, code, reason)
        writeHeader(output, "Content-Type", "text/plain; charset=utf-8")
        writeHeader(output, "Content-Length", body.size.toString())
        writeHeader(output, "Connection", "close")
        finishHeaders(output)
        output.write(body)
    }

    private fun writeStatus(output: BufferedOutputStream, code: Int, reason: String) {
        output.write("HTTP/1.1 $code $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeHeader(output: BufferedOutputStream, name: String, value: String) {
        output.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun finishHeaders(output: BufferedOutputStream) {
        output.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun reasonFor(code: Int): String =
        when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            else -> "Upstream Response"
        }

    private companion object {
        const val TAG = "OrchardCastStream"
        const val SOCKET_TIMEOUT_MS = 30_000
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
    }
}
