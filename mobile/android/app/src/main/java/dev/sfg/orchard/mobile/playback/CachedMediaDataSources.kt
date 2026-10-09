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

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import java.io.RandomAccessFile

/** A fully cached track read straight from its span files, skipping CacheDataSource locking. */
internal class SpanMediaDataSource(private val spans: List<Span>, private val totalLength: Long) : MediaDataSource() {
    class Span(val start: Long, val end: Long, val file: RandomAccessFile)

    override fun readAt(at: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        val span = spans.firstOrNull { it.start <= at && at < it.end } ?: return -1
        val toRead = minOf(size.toLong(), span.end - at).toInt()
        synchronized(span.file) {
            span.file.seek(at - span.start)
            return span.file.read(buffer, offset, toRead)
        }
    }

    override fun getSize(): Long = totalLength

    override fun close() {
        spans.forEach { runCatching { it.file.close() } }
    }
}

/** A partly cached track through the cache's read path; reopens only when a read jumps. */
@UnstableApi
internal class CacheReadMediaDataSource(
    private val factory: CacheDataSource.Factory,
    private val uri: Uri,
    private val key: String,
    private val totalLength: Long,
) : MediaDataSource() {
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
        return factory.createDataSource().also {
            it.open(spec)
            source = it
            position = at
        }
    }

    override fun readAt(at: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        val active = source?.takeIf { position == at } ?: openAt(at)
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
