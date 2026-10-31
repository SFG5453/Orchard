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

import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QobuzStreamServerTest {
    private val audio = "fLaCabcdefgh".toByteArray()

    private fun request(url: String, range: String? = null): Pair<String, ByteArray> {
        val uri = URI(url)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 2_000
            val rangeHeader = range?.let { "Range: bytes=$it\r\n" }.orEmpty()
            socket.getOutputStream().write("GET ${uri.path} HTTP/1.1\r\nHost: localhost\r\n${rangeHeader}\r\n".toByteArray())
            val bytes = socket.getInputStream().readBytes()
            val separator = "\r\n\r\n".toByteArray()
            val end = (0..bytes.size - separator.size).first { index ->
                separator.indices.all { bytes[index + it] == separator[it] }
            } + separator.size
            bytes.copyOfRange(0, end).decodeToString() to bytes.copyOfRange(end, bytes.size)
        }
    }

    @Test fun `audio streams while the play report is pending and reports only once`() {
        val reporting = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val reports = AtomicInteger()
        val source = object : QobuzStreamSource {
            override suspend fun read(playbackId: String, start: Long, end: Long): ByteArray =
                audio.copyOfRange(start.toInt(), minOf(end + 1, start + 3).toInt())

            override suspend fun started(playbackId: String) {
                reports.incrementAndGet()
                reporting.countDown()
                releaseReport.await(5, TimeUnit.SECONDS)
            }
        }
        val server = QobuzStreamServer(source)
        try {
            val url = server.register("test", audio.size.toLong())
            val (headers, body) = request(url)
            assertTrue(reporting.await(2, TimeUnit.SECONDS))
            assertEquals(1L, releaseReport.count)
            assertTrue(headers.startsWith("HTTP/1.1 200"))
            assertTrue(headers.contains("Content-Length: ${audio.size}"))
            assertArrayEquals(audio, body)
            assertArrayEquals(audio, request(url).second)
            assertEquals(1, reports.get())
        } finally {
            releaseReport.countDown()
            server.stop()
        }
    }

    @Test fun `seeks resume after each short chunk without gaps or duplicates`() {
        val offsets = mutableListOf<Long>()
        val source = object : QobuzStreamSource {
            override suspend fun read(playbackId: String, start: Long, end: Long): ByteArray {
                offsets.add(start)
                return audio.copyOfRange(start.toInt(), minOf(end + 1, start + 2).toInt())
            }
            override suspend fun started(playbackId: String) = Unit
        }
        val server = QobuzStreamServer(source)
        try {
            val url = server.register("seek", audio.size.toLong())
            val (headers, body) = request(url, "5-9")
            assertTrue(headers.startsWith("HTTP/1.1 206"))
            assertTrue(headers.contains("Content-Range: bytes 5-9/${audio.size}"))
            assertArrayEquals(audio.copyOfRange(5, 10), body)
            assertEquals(listOf(5L, 7L, 9L), offsets)
        } finally {
            server.stop()
        }
    }
}
