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

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a rendered overlap to a WAV file the player can open as an ordinary media item.
 *
 * A file rather than an in-memory source because that is what lets the rendered mix be scheduled
 * through the same playlist machinery as everything else: the overlap becomes item one and the
 * remainder of the incoming track item two, and ExoPlayer's own item transition covers the seam
 * between them. Feeding raw PCM into the player would mean a custom MediaSource and a second code
 * path through the part of playback least tolerant of bugs.
 *
 * 16-bit rather than float: universally decodable, half the size, and the renderer's output has
 * already been through a filter bank and a resampler, so the last 8 bits are not carrying anything
 * a listener will hear.
 */
object TransitionAudio {

    private const val HEADER_BYTES = 44
    private const val CHANNELS = 2
    private const val BITS = 16

    /**
     * Writes [rendered] to [target], replacing anything already there.
     *
     * Returns null if writing fails, which the caller treats as "no prepared transition" and falls
     * back to the volume ramp.
     */
    fun writeWav(rendered: TransitionRenderer.Rendered, target: File): File? = runCatching {
        val frames = rendered.frames
        val dataBytes = frames * CHANNELS * (BITS / 8)
        target.parentFile?.mkdirs()

        RandomAccessFile(target, "rw").use { file ->
            file.setLength(0)
            file.write(header(dataBytes, TransitionRenderer.SAMPLE_RATE.toInt()))

            // Written in blocks rather than sample by sample: a few hundred thousand individual
            // writes through RandomAccessFile is slow enough to matter against a transition that
            // has to be ready before the playhead reaches it.
            val block = 8192
            val buffer = ByteBuffer.allocate(block * CHANNELS * 2).order(ByteOrder.LITTLE_ENDIAN)
            var frame = 0
            while (frame < frames) {
                buffer.clear()
                val end = minOf(frame + block, frames)
                for (index in frame until end) {
                    buffer.putShort(toPcm16(rendered.left[index]))
                    buffer.putShort(toPcm16(rendered.right[index]))
                }
                file.write(buffer.array(), 0, buffer.position())
                frame = end
            }
        }
        target
    }.getOrNull()

    /** Clamped before conversion: the renderer does not limit, and wrapping would be a loud click. */
    private fun toPcm16(value: Float): Short =
        (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private fun header(dataBytes: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * CHANNELS * (BITS / 8)
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(HEADER_BYTES - 8 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)                                   // PCM
            putShort(CHANNELS.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((CHANNELS * BITS / 8).toShort())     // block align
            putShort(BITS.toShort())
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()
    }
}
