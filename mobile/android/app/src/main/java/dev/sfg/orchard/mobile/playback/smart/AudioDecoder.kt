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

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.util.UnstableApi
import eu.buney.kopus.OpusDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Decodes a region of a cached track to mono float PCM at the beat model's rate.
 *
 * Only a region: a transition needs the tail of the outgoing track and the head of the incoming
 * one, not both in full, and decoding a whole album's worth of audio to analyse thirty seconds of
 * it would cost battery for nothing. The container still has to be parsed to seek, which is why
 * this reads through a [MediaDataSource] rather than taking a byte range.
 *
 * Everything here is best-effort. A codec that will not configure, a container Android cannot
 * parse, a region past the end, all return null, and the caller falls back to no analysis, which
 * the transition policy already handles as its bottom rung.
 */
@UnstableApi
object AudioDecoder {

    /** Decoded mono PCM at the container's own sample rate; the caller resamples. */
    data class Pcm(val samples: FloatArray, val sampleRate: Double) {
        val durationSeconds: Double get() = samples.size / sampleRate

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Pcm && sampleRate == other.sampleRate && samples.contentEquals(other.samples))

        override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate.hashCode()
    }

    /**
     * Decodes [startSeconds] to [endSeconds] of [source], downmixed to mono and resampled.
     *
     * The extractor seeks to the closest sync sample at or before the requested start, so a little
     * more audio than asked for may come back at the front; the caller is given the real start via
     * the returned offset so frame indices still map to true track times.
     */
    fun decodeRegion(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        targetRate: Int? = null,
    ): Pair<Pcm, Double>? {
        if (endSeconds <= startSeconds) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var handlerThread: HandlerThread? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val inputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble()
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            extractor.seekTo((startSeconds * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val actualStart = extractor.sampleTime / 1_000_000.0
            val endUs = (endSeconds * 1_000_000).toLong()

            if (mime == "audio/opus") {
                val rate = closestOpusRate(targetRate ?: inputRate.toInt())
                return decodeOpusMono(extractor, actualStart, endUs, rate, channels)
            }

            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, 32767)
            codec = createDecoder(mime)
            handlerThread = HandlerThread("OrchardDecoder").apply { start() }
            val handler = Handler(handlerThread.looper)

            val latch = CountDownLatch(1)
            var fatalError: Throwable? = null
            val collected = ArrayList<FloatArray>()
            var total = 0
            var outputEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                ENCODING_PCM_16BIT
            }
            var inputDone = false

            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (inputDone) return
                    try {
                        val buffer = codec.getInputBuffer(index) ?: return
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || (endUs in 0..extractor.sampleTime)) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val time = extractor.sampleTime
                            extractor.advance()
                            codec.queueInputBuffer(index, 0, size, time, 0)
                        }
                    } catch (e: Throwable) {
                        fatalError = e
                        latch.countDown()
                    }
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val chunk = downmix(buffer, info, outputEncoding, channels)
                            collected += chunk
                            total += chunk.size
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            latch.countDown()
                        }
                    } catch (e: Throwable) {
                        fatalError = e
                        latch.countDown()
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    fatalError = e
                    latch.countDown()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
            }, handler)

            codec.configure(format, null, null, 0)
            val decodeStarted = System.currentTimeMillis()
            codec.start()

            latch.await(30, TimeUnit.SECONDS)
            fatalError?.let { throw it }

            if (total == 0) return null
            val samples = FloatArray(total)
            var offset = 0
            for (chunk in collected) {
                chunk.copyInto(samples, offset)
                offset += chunk.size
            }

            Log.d(
                TAG,
                "decode ${System.currentTimeMillis() - decodeStarted}ms via ${codec.name} " +
                    "(${samples.size} @ ${inputRate.toInt()}Hz, ${channels}ch)",
            )
            return Pcm(samples, inputRate) to actualStart
        } catch (error: Exception) {
            Log.w(TAG, "Decode of ${startSeconds}s..${endSeconds}s failed", error)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { handlerThread?.quitSafely() }
        }
    }

    /** Decoded planar stereo PCM at the container's own rate, for the vocal model. */
    data class StereoPcm(val left: FloatArray, val right: FloatArray, val sampleRate: Double) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is StereoPcm && sampleRate == other.sampleRate &&
                left.contentEquals(other.left) && right.contentEquals(other.right))

        override fun hashCode(): Int =
            31 * (31 * left.contentHashCode() + right.contentHashCode()) + sampleRate.hashCode()
    }

    /**
     * Decodes a region as planar stereo, which is what the vocal-separation model expects.
     *
     * Separate from [decodeRegion] rather than a flag on it because almost everything here wants
     * mono (the mel front end, the envelope analyzer), and carrying two channels through those
     * paths would double the memory for data they immediately average away. A mono source is
     * duplicated across both channels, matching how the transition renderer treats every source as
     * having two.
     */
    fun decodeRegionStereo(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        targetRate: Int? = null,
    ): Pair<StereoPcm, Double>? {
        if (endSeconds <= startSeconds) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var handlerThread: HandlerThread? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val inputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble()
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            extractor.seekTo((startSeconds * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val actualStart = extractor.sampleTime / 1_000_000.0
            val endUs = (endSeconds * 1_000_000).toLong()

            if (mime == "audio/opus") {
                val rate = closestOpusRate(targetRate ?: inputRate.toInt())
                return decodeOpusStereo(extractor, actualStart, endUs, rate, channels)
            }

            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, 32767)
            codec = createDecoder(mime)
            handlerThread = HandlerThread("OrchardDecoderStereo").apply { start() }
            val handler = Handler(handlerThread.looper)

            val latch = CountDownLatch(1)
            var fatalError: Throwable? = null
            val leftChunks = ArrayList<FloatArray>()
            val rightChunks = ArrayList<FloatArray>()
            var total = 0
            var outputEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                ENCODING_PCM_16BIT
            }
            var inputDone = false

            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (inputDone) return
                    try {
                        val buffer = codec.getInputBuffer(index) ?: return
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || (endUs in 0..extractor.sampleTime)) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val time = extractor.sampleTime
                            extractor.advance()
                            codec.queueInputBuffer(index, 0, size, time, 0)
                        }
                    } catch (e: Throwable) {
                        fatalError = e
                        latch.countDown()
                    }
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val (left, right) = splitChannels(buffer, info, outputEncoding, channels)
                            leftChunks += left
                            rightChunks += right
                            total += left.size
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            latch.countDown()
                        }
                    } catch (e: Throwable) {
                        fatalError = e
                        latch.countDown()
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    fatalError = e
                    latch.countDown()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
            }, handler)

            codec.configure(format, null, null, 0)
            val decodeStarted = System.currentTimeMillis()
            codec.start()

            latch.await(30, TimeUnit.SECONDS)
            fatalError?.let { throw it }

            if (total == 0) return null
            val left = FloatArray(total)
            val right = FloatArray(total)
            var offset = 0
            for (index in leftChunks.indices) {
                leftChunks[index].copyInto(left, offset)
                rightChunks[index].copyInto(right, offset)
                offset += leftChunks[index].size
            }

            Log.d(
                TAG,
                "stereo decode ${System.currentTimeMillis() - decodeStarted}ms via ${codec.name} " +
                    "(${left.size} @ ${inputRate.toInt()}Hz, ${channels}ch)",
            )
            return StereoPcm(left, right, inputRate) to actualStart
        } catch (error: Exception) {
            Log.w(TAG, "Stereo decode of ${startSeconds}s..${endSeconds}s failed", error)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { handlerThread?.quitSafely() }
        }
    }

    private fun decodeOpusMono(
        extractor: MediaExtractor,
        actualStart: Double,
        endUs: Long,
        sampleRate: Int,
        channels: Int,
    ): Pair<Pcm, Double>? {
        val decoder = OpusDecoder(sampleRate, channels)
        val collected = ArrayList<FloatArray>()
        var total = 0
        val inputBuffer = ByteBuffer.allocateDirect(16384)
        val outPcm = FloatArray(5760 * channels) // Max opus frame size (120ms at 48kHz)
        val reusableData = ByteArray(16384)

        // Buffer up to 1 second of audio at a time to minimize object allocations.
        val buffer = FloatArray(sampleRate)
        var bufferPos = 0

        try {
            val decodeStarted = System.currentTimeMillis()
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || (endUs in 0 until sampleTime)) break

                val size = extractor.readSampleData(inputBuffer, 0)
                if (size < 0) break

                inputBuffer.position(0)
                inputBuffer.get(reusableData, 0, size)

                val decodedSamples = decoder.decode(reusableData, 0, size, outPcm, 0, 5760, false)
                if (decodedSamples > 0) {
                    if (bufferPos + decodedSamples > buffer.size) {
                        collected += buffer.copyOf(bufferPos)
                        bufferPos = 0
                    }

                    if (channels == 1) {
                        outPcm.copyInto(buffer, bufferPos, 0, decodedSamples)
                    } else {
                        val invChannels = 1f / channels
                        for (i in 0 until decodedSamples) {
                            var sum = 0f
                            for (c in 0 until channels) sum += outPcm[i * channels + c]
                            buffer[bufferPos + i] = sum * invChannels
                        }
                    }
                    bufferPos += decodedSamples
                    total += decodedSamples
                }
                extractor.advance()
            }

            if (bufferPos > 0) collected += buffer.copyOf(bufferPos)
            if (total == 0) return null

            val samples = FloatArray(total)
            var offset = 0
            for (chunk in collected) {
                chunk.copyInto(samples, offset)
                offset += chunk.size
            }

            Log.d(TAG, "decode ${System.currentTimeMillis() - decodeStarted}ms via Kopus (mono, ${samples.size} samples)")
            return Pcm(samples, sampleRate.toDouble()) to actualStart
        } finally {
            decoder.close()
        }
    }

    private fun decodeOpusStereo(
        extractor: MediaExtractor,
        actualStart: Double,
        endUs: Long,
        sampleRate: Int,
        channels: Int,
    ): Pair<StereoPcm, Double>? {
        val decoder = OpusDecoder(sampleRate, channels)
        val leftChunks = ArrayList<FloatArray>()
        val rightChunks = ArrayList<FloatArray>()
        var total = 0
        val inputBuffer = ByteBuffer.allocateDirect(16384)
        val outPcm = FloatArray(5760 * channels)
        val reusableData = ByteArray(16384)

        // Buffer up to 1 second of audio at a time to minimize object allocations.
        val leftBuf = FloatArray(sampleRate)
        val rightBuf = FloatArray(sampleRate)
        var bufferPos = 0

        try {
            val decodeStarted = System.currentTimeMillis()
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || (endUs in 0 until sampleTime)) break

                val size = extractor.readSampleData(inputBuffer, 0)
                if (size < 0) break

                inputBuffer.position(0)
                inputBuffer.get(reusableData, 0, size)

                val decodedSamples = decoder.decode(reusableData, 0, size, outPcm, 0, 5760, false)
                if (decodedSamples > 0) {
                    if (bufferPos + decodedSamples > leftBuf.size) {
                        leftChunks += leftBuf.copyOf(bufferPos)
                        rightChunks += rightBuf.copyOf(bufferPos)
                        bufferPos = 0
                    }

                    if (channels >= 2) {
                        for (i in 0 until decodedSamples) {
                            leftBuf[bufferPos + i] = outPcm[i * channels]
                            rightBuf[bufferPos + i] = outPcm[i * channels + 1]
                        }
                    } else {
                        outPcm.copyInto(leftBuf, bufferPos, 0, decodedSamples)
                        outPcm.copyInto(rightBuf, bufferPos, 0, decodedSamples)
                    }
                    bufferPos += decodedSamples
                    total += decodedSamples
                }
                extractor.advance()
            }

            if (bufferPos > 0) {
                leftChunks += leftBuf.copyOf(bufferPos)
                rightChunks += rightBuf.copyOf(bufferPos)
            }
            if (total == 0) return null

            val left = FloatArray(total)
            val right = FloatArray(total)
            var offset = 0
            for (i in leftChunks.indices) {
                leftChunks[i].copyInto(left, offset)
                rightChunks[i].copyInto(right, offset)
                offset += leftChunks[i].size
            }

            Log.d(TAG, "decode ${System.currentTimeMillis() - decodeStarted}ms via Kopus (stereo, ${left.size} samples)")
            return StereoPcm(left, right, sampleRate.toDouble()) to actualStart
        } finally {
            decoder.close()
        }
    }

    /** Splits one output buffer into planar channels, duplicating a mono source across both. */
    private fun splitChannels(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        encoding: Int,
        channels: Int,
    ): Pair<FloatArray, FloatArray> {
        buffer.order(ByteOrder.nativeOrder())
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        if (encoding == ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            val available = floats.remaining()
            val frames = available / channels
            val left = FloatArray(frames)
            val right = FloatArray(frames)
            val bulk = FloatArray(available)
            floats.get(bulk, 0, available)
            if (channels >= 2) {
                var src = 0
                for (i in 0 until frames) {
                    left[i] = bulk[src]
                    right[i] = bulk[src + 1]
                    src += channels
                }
            } else {
                bulk.copyInto(left, 0, 0, frames)
                bulk.copyInto(right, 0, 0, frames)
            }
            return left to right
        } else {
            val shorts = buffer.asShortBuffer()
            val available = shorts.remaining()
            val frames = available / channels
            val left = FloatArray(frames)
            val right = FloatArray(frames)
            val bulk = ShortArray(available)
            shorts.get(bulk, 0, available)
            val scale = 1f / 32768f
            if (channels >= 2) {
                var src = 0
                for (i in 0 until frames) {
                    left[i] = bulk[src].toInt() * scale
                    right[i] = bulk[src + 1].toInt() * scale
                    src += channels
                }
            } else {
                for (i in 0 until frames) {
                    val v = bulk[i].toInt() * scale
                    left[i] = v
                    right[i] = v
                }
            }
            return left to right
        }
    }

    /**
     * Converts one output buffer to mono float.
     *
     * Decoders may emit 16-bit integer or float PCM depending on the codec and the device, so the
     * encoding is read from the output format rather than assumed. Channels are averaged rather
     * than taking the left one: a track mixed with the beat on one side would otherwise lose it.
     */
    private fun downmix(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        encoding: Int,
        channels: Int,
    ): FloatArray {
        buffer.order(ByteOrder.nativeOrder())
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        return if (encoding == ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            val available = floats.remaining()
            val frames = available / channels
            val out = FloatArray(frames)
            val bulk = FloatArray(available)
            floats.get(bulk, 0, available)
            when (channels) {
                1 -> bulk.copyInto(out, 0, 0, frames)
                2 -> {
                    var outIdx = 0
                    val limit = frames * 2
                    for (i in 0 until limit step 2) {
                        out[outIdx++] = (bulk[i] + bulk[i + 1]) * 0.5f
                    }
                }
                else -> {
                    val invChannels = 1f / channels
                    var srcIdx = 0
                    for (frame in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) sum += bulk[srcIdx++]
                        out[frame] = sum * invChannels
                    }
                }
            }
            out
        } else {
            val shorts = buffer.asShortBuffer()
            val available = shorts.remaining()
            val frames = available / channels
            val out = FloatArray(frames)
            val bulk = ShortArray(available)
            shorts.get(bulk, 0, available)
            when (channels) {
                1 -> {
                    val scale = 1f / 32768f
                    for (i in 0 until frames) out[i] = bulk[i].toInt() * scale
                }
                2 -> {
                    val scale = 0.5f / 32768f
                    var outIdx = 0
                    val limit = frames * 2
                    for (i in 0 until limit step 2) {
                        out[outIdx++] = (bulk[i].toInt() + bulk[i + 1].toInt()) * scale
                    }
                }
                else -> {
                    val invChannels = 1f / (channels * 32768f)
                    var srcIdx = 0
                    for (frame in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += bulk[srcIdx++].toInt()
                        out[frame] = sum * invChannels
                    }
                }
            }
            out
        }
    }

    /** Prefers zero-IPC in-process codecs and CPU SIMD decoders over throttled DSP codecs. */
    private fun createDecoder(mime: String): MediaCodec {
        val inproc = when (mime.lowercase()) {
            "audio/opus" -> "c2.android.inproc.opus.decoder"
            "audio/mp4a-latm" -> "c2.android.inproc.aac.decoder"
            else -> null
        }
        if (inproc != null) {
            runCatching { MediaCodec.createByCodecName(inproc) }.getOrNull()?.let { return it }
        }
        return runCatching {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            var fallbackSwName: String? = null
            for (info in list.codecInfos) {
                if (info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val name = info.name.lowercase()
                if (name.contains(".inproc.")) {
                    runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull()?.let { return it }
                }
                if (fallbackSwName == null && (name.startsWith("c2.android.") || name.startsWith("omx.google.") ||
                    name.contains(".sw.") || name.contains("software"))
                ) {
                    fallbackSwName = info.name
                }
            }
            if (fallbackSwName != null) {
                runCatching { MediaCodec.createByCodecName(fallbackSwName) }.getOrNull()?.let { return it }
            }
            MediaCodec.createDecoderByType(mime)
        }.getOrElse { MediaCodec.createDecoderByType(mime) }
    }

    private fun closestOpusRate(rate: Int): Int {
        val rates = intArrayOf(8000, 12000, 16000, 24000, 48000)
        return rates.minByOrNull { abs(it - rate) } ?: 48000
    }

    private const val TAG = "OrchardAudioDecoder"

    // AudioFormat constants, named here so this file does not depend on android.media.AudioFormat
    // purely for two integers.
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_FLOAT = 4
}
