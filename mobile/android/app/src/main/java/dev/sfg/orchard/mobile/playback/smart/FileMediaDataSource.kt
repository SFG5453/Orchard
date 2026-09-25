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

package dev.sfg.orchard.mobile.playback.smart

import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile

/**
 * High-performance, thread-safe [MediaDataSource] wrapper around an on-disk audio [File].
 * Allows [android.media.MediaExtractor] and [AudioDecoder] to decode downloaded files
 * directly from local storage with minimal overhead.
 */
class FileMediaDataSource(private val file: File) : MediaDataSource() {
    private val raf: RandomAccessFile = RandomAccessFile(file, "r")
    private val length: Long = file.length()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position >= length) return -1
        synchronized(raf) {
            raf.seek(position)
            val toRead = minOf(size.toLong(), length - position).toInt()
            return raf.read(buffer, offset, toRead)
        }
    }

    override fun getSize(): Long = length

    override fun close() {
        runCatching { raf.close() }
    }
}
