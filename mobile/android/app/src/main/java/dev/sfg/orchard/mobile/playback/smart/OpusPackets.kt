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
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/** Compressed audio packets from a container, parsed in process by Media3's extractors. */
@UnstableApi
internal object OpusPackets {
    /**
     * Calls [onFormat] once with the first audio track's format, then [onPacket] for each of its
     * packets with the nanoseconds of padding to drop from its end (WebM DiscardPadding). Either
     * returning false stops the read. Returns false when nothing was accepted or the read stopped early.
     */
    fun read(
        source: MediaDataSource,
        onFormat: (Format) -> Boolean,
        onPacket: (packet: ByteArray, size: Int, discardNs: Long) -> Boolean,
    ): Boolean {
        val length = source.size.takeIf { it >= 0 } ?: C.LENGTH_UNSET.toLong()
        val extractor = DefaultExtractorsFactory().createExtractors().firstOrNull { candidate ->
            runCatching { candidate.sniff(DefaultExtractorInput(Reader(source, 0), 0, length)) }.getOrDefault(false)
        } ?: return false
        val collector = Collector(onFormat, onPacket)
        try {
            extractor.init(collector)
            var input = DefaultExtractorInput(Reader(source, 0), 0, length)
            val position = PositionHolder()
            while (!collector.stopped) {
                when (extractor.read(input, position)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> input = DefaultExtractorInput(
                        Reader(source, position.position), position.position, length)
                }
            }
            return collector.accepted && !collector.stopped
        } finally {
            extractor.release()
        }
    }

    /** Buffered reads: extractors ask for a few bytes at a time. */
    private class Reader(private val source: MediaDataSource, private var position: Long) : DataReader {
        private val block = ByteArray(1 shl 16)
        private var blockStart = 0L
        private var blockLength = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position < blockStart || position >= blockStart + blockLength) {
                blockStart = position
                blockLength = source.readAt(position, block, 0, block.size).coerceAtLeast(0)
                if (blockLength == 0) return C.RESULT_END_OF_INPUT
            }
            val from = (position - blockStart).toInt()
            val count = minOf(length, blockLength - from)
            System.arraycopy(block, from, buffer, offset, count)
            position += count
            return count
        }
    }

    private class Collector(
        private val onFormat: (Format) -> Boolean,
        private val onPacket: (ByteArray, Int, Long) -> Boolean,
    ) : ExtractorOutput, TrackOutput {
        var accepted = false
        var stopped = false
        private var audioTrack = -1
        private var pending = ByteArray(1 shl 16)
        private var filled = 0
        private var packet = ByteArray(1 shl 12)

        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_AUDIO || (audioTrack >= 0 && audioTrack != id)) return DiscardingTrackOutput()
            audioTrack = id
            return this
        }

        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit

        override fun format(format: Format) {
            if (accepted || stopped) return
            accepted = onFormat(format)
            if (!accepted) stopped = true
        }

        private fun reserve(length: Int) {
            if (filled + length > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, filled + length))
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            reserve(length)
            val read = input.read(pending, filled, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            filled += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            reserve(length)
            data.readBytes(pending, filled, length)
            filled += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val start = filled - offset - size
            if (start < 0 || stopped) return
            var from = start
            var length = size
            var discardNs = 0L
            // Supplemental layout: big-endian main size, the packet, then Matroska's 8-byte
            // little-endian DiscardPadding, which FFmpeg trims from the song's last frame.
            if (flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA != 0 && size >= 4) {
                val main = ((pending[start].toInt() and 0xFF) shl 24) or ((pending[start + 1].toInt() and 0xFF) shl 16) or
                    ((pending[start + 2].toInt() and 0xFF) shl 8) or (pending[start + 3].toInt() and 0xFF)
                if (main in 0..size - 4) {
                    from = start + 4
                    length = main
                    val extra = start + 4 + main
                    if (size - 4 - main >= 8) {
                        for (i in 7 downTo 0) discardNs = (discardNs shl 8) or (pending[extra + i].toLong() and 0xFF)
                    }
                }
            }
            if (packet.size < length) packet = ByteArray(length)
            System.arraycopy(pending, from, packet, 0, length)
            System.arraycopy(pending, filled - offset, pending, start, offset)
            filled = start + offset
            if (accepted && !onPacket(packet, length, discardNs)) stopped = true
        }
    }
}
