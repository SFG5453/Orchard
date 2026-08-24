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
import eu.buney.kopus.OpusLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

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

    /**
     * Reads the audio duration advertised by a fully available media container.
     *
     * Queue metadata is not authoritative: some YouTube Music search rows omit the duration even
     * though the resolved WebM carries it. Analysis already requires a fully cached file, so the
     * container is the best fallback and opening only its track formats is much cheaper than a
     * decode. The caller owns [source]; this function only owns the extractor attached to it.
     */
    fun containerDurationSeconds(source: MediaDataSource): Double? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source)
            (0 until extractor.trackCount)
                .mapNotNull { index ->
                    val format = extractor.getTrackFormat(index)
                    val audio = format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    if (!audio || !format.containsKey(MediaFormat.KEY_DURATION)) return@mapNotNull null
                    format.getLong(MediaFormat.KEY_DURATION)
                        .takeIf { it > 0 }
                        ?.div(1_000_000.0)
                }
                .maxOrNull()
                ?.takeIf { it.isFinite() && it > 0 }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read duration from cached media", error)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Resamples a decoded stream as it arrives, so it is never held in full at the rate it was
     * decoded at.
     *
     * Five minutes of 44.1 kHz mono is 53 MB of floats, and resampling that in one call needs the
     * result live alongside it while the chunks it was assembled from are still reachable. Two
     * model-grade analyses and a transition render overlap by design, so a 192 MB heap runs out --
     * and the allocation that finally fails is some small one elsewhere, which is why this was slow
     * to find. Feeding the resampler as the decoder produces buffers holds one block instead.
     *
     * Blocks are whole periods of the rate ratio and are padded with the filter's own context, so
     * the result has the same length as resampling the whole stream at once and is bit-identical
     * across every block boundary. That exactness is the point: a discontinuity at a boundary is an
     * onset as far as the beat tracker is concerned, and a wrong beat grid is trusted by everything
     * downstream.
     */
    private class StreamResampler private constructor(
        private val inputRate: Double,
        val outputRate: Double,
        private val context: Int,
        private val block: Int,
        expectedOutput: Int,
    ) {
        private var staged = FloatArray(context + block + context)
        private var stagedCount = 0

        /** Samples at the front of [staged] that are context for the block behind them. */
        private var leading = 0

        private var output = FloatArray(max(expectedOutput, 4096))
        private var produced = 0

        /** Takes a count so a caller with a reusable buffer need not copy it out first. */
        fun add(chunk: FloatArray, count: Int = chunk.size) {
            if (count <= 0) return
            if (stagedCount + count > staged.size) {
                staged = staged.copyOf(max(staged.size * 2, stagedCount + count))
            }
            chunk.copyInto(staged, stagedCount, 0, count)
            stagedCount += count

            while (stagedCount >= leading + block + context) {
                emit(leading + block + context, trailing = context)
                // Everything but the trailing context is done with; that context is the next
                // block's leading context, so it stays.
                val consumed = leading + block - context
                staged.copyInto(staged, 0, consumed, stagedCount)
                stagedCount -= consumed
                leading = context
            }
        }

        fun finish(): FloatArray {
            // No trailing context: what is left runs to the end of the stream, where clamping the
            // filter window is the right answer rather than an artifact.
            if (stagedCount > leading) emit(stagedCount, trailing = 0)
            stagedCount = 0
            return if (produced == output.size) output else output.copyOf(produced)
        }

        private fun emit(length: Int, trailing: Int) {
            val chunk = MelSpectrogram.resampleInterior(
                staged, 0, length, inputRate, outputRate, leading, trailing,
            )
            if (chunk.isEmpty()) return
            if (produced + chunk.size > output.size) {
                output = output.copyOf(max(output.size * 2, produced + chunk.size))
            }
            chunk.copyInto(output, produced)
            produced += chunk.size
        }

        companion object {
            /**
             * A resampler for this pair of rates, or null when they admit no exact block period and
             * the caller must resample the stream in one call instead. No rate this app decodes at
             * is in that case: they are all whole numbers.
             */
            fun of(inputRate: Double, outputRate: Double, expectedSeconds: Double): StreamResampler? {
                val period = MelSpectrogram.resamplePeriod(inputRate, outputRate)
                val reach = MelSpectrogram.resampleContext(inputRate, outputRate)
                if (period <= 0 || reach <= 0) return null

                val context = roundUp(reach, period)
                // Roughly a second of input per block, and never so few blocks that the context
                // dominates: the shift after each block has to leave the next one's context behind.
                val block = roundUp(max(inputRate.toInt(), context * 4), period)
                // Sized from the region that was asked for so the ordinary decode never grows it.
                // Clamped because a container's advertised duration is not always believable.
                val expected = (expectedSeconds.coerceIn(0.0, MAX_PRESIZE_SECONDS) * outputRate).toInt()
                return StreamResampler(inputRate, outputRate, context, block, expected)
            }

            private fun roundUp(value: Int, multiple: Int): Int =
                (value + multiple - 1) / multiple * multiple

            private const val MAX_PRESIZE_SECONDS = 1_200.0
        }
    }

    /** Decoded mono PCM at [sampleRate], which is the rate asked for when one was. */
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
     *
     * [targetRate] is honoured by every codec, not only Opus: the audio is resampled as it is
     * decoded, so a whole track is never held at the container's rate. Check the returned
     * [Pcm.sampleRate] rather than assuming it, since the Opus decoder only offers its own rates.
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
                decodeOpusMono(extractor, actualStart, endUs, rate, channels, targetRate)?.let { return it }
                // Kopus could not do it. The platform decoder still can, but only from the
                // top of the region: the extractor may have been advanced already.
                extractor.seekTo((startSeconds * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, 32767)
            codec = createDecoder(mime)
            handlerThread = HandlerThread("OrchardDecoder").apply { start() }
            val handler = Handler(handlerThread.looper)

            val latch = CountDownLatch(1)
            var fatalError: Throwable? = null
            // Written from the codec's callback thread and read after the latch, which is what
            // publishes it.
            val downsampler = targetRate
                ?.takeIf { abs(it - inputRate) > 1.0 }
                ?.let { StreamResampler.of(inputRate, it.toDouble(), endSeconds - actualStart) }
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
                            if (downsampler != null) downsampler.add(chunk) else collected += chunk
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
            val samples = downsampler?.finish() ?: FloatArray(total).also { merged ->
                var offset = 0
                for (chunk in collected) {
                    chunk.copyInto(merged, offset)
                    offset += chunk.size
                }
            }
            val rate = downsampler?.outputRate ?: inputRate

            Log.d(
                TAG,
                "decode ${System.currentTimeMillis() - decodeStarted}ms via ${codec.name} " +
                    "(${samples.size} @ ${rate.toInt()}Hz from ${inputRate.toInt()}Hz, ${channels}ch)",
            )
            return Pcm(samples, rate) to actualStart
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

    /** Decoded planar stereo PCM at [sampleRate], which is the rate asked for when one was. */
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
     *
     * As in [decodeRegion], [targetRate] is applied during the decode rather than after it, and the
     * rate actually delivered is on the returned [StereoPcm].
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
                decodeOpusStereo(extractor, actualStart, endUs, rate, channels, targetRate)
                    ?.let { return it }
                extractor.seekTo((startSeconds * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, 32767)
            codec = createDecoder(mime)
            handlerThread = HandlerThread("OrchardDecoderStereo").apply { start() }
            val handler = Handler(handlerThread.looper)

            val latch = CountDownLatch(1)
            var fatalError: Throwable? = null
            val requested = targetRate?.takeIf { abs(it - inputRate) > 1.0 }?.toDouble()
            val leftDown = requested?.let { StreamResampler.of(inputRate, it, endSeconds - actualStart) }
            val rightDown = requested?.let { StreamResampler.of(inputRate, it, endSeconds - actualStart) }
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
                            if (leftDown != null && rightDown != null) {
                                leftDown.add(left)
                                rightDown.add(right)
                            } else {
                                leftChunks += left
                                rightChunks += right
                            }
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
            val left: FloatArray
            val right: FloatArray
            if (leftDown != null && rightDown != null) {
                left = leftDown.finish()
                right = rightDown.finish()
            } else {
                left = FloatArray(total)
                right = FloatArray(total)
                var offset = 0
                for (index in leftChunks.indices) {
                    leftChunks[index].copyInto(left, offset)
                    rightChunks[index].copyInto(right, offset)
                    offset += leftChunks[index].size
                }
            }
            val rate = leftDown?.outputRate ?: inputRate

            Log.d(
                TAG,
                "stereo decode ${System.currentTimeMillis() - decodeStarted}ms via ${codec.name} " +
                    "(${left.size} @ ${rate.toInt()}Hz from ${inputRate.toInt()}Hz, ${channels}ch)",
            )
            return StereoPcm(left, right, rate) to actualStart
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

    /**
     * A resampler from [decodedRate] to [outputRate] for a region running to [endUs], or null when
     * the decoder already delivers the rate that was asked for.
     */
    private fun resamplerTo(
        outputRate: Int?,
        decodedRate: Int,
        actualStart: Double,
        endUs: Long,
    ): StreamResampler? = outputRate
        ?.takeIf { abs(it - decodedRate) > 1 }
        ?.let { StreamResampler.of(decodedRate.toDouble(), it.toDouble(), endUs / 1_000_000.0 - actualStart) }

    /** Guards the one-time native load below. */
    private val opusLoadLock = Any()

    /** Null until the first attempt settles it, so a hopeless load is not retried per track. */
    @Volatile
    private var opusNativeUsable: Boolean? = null

    /**
     * Opens a Kopus decoder, or null when the native library cannot serve one.
     *
     * Kopus flips its "already loaded" flag *before* `System.loadLibrary` returns, so a second
     * thread arriving while the first is still inside `dlopen` is told the library is ready and
     * calls straight into natives that are not registered yet, which is an `UnsatisfiedLinkError`
     * rather than an exception and therefore kills the process. Best Mix is what finds it: it
     * fans a whole playlist across the analysis pool at once, so several threads open their first
     * decoder in the same instant. Doing the load ourselves under a lock means only the winner is
     * ever inside it and everyone else waits for a library that is genuinely there.
     */
    private fun openOpusDecoder(sampleRate: Int, channels: Int): OpusDecoder? {
        if (opusNativeUsable == false) return null
        synchronized(opusLoadLock) {
            if (opusNativeUsable == false) return null
            try {
                OpusLoader.load()
            } catch (error: Throwable) {
                Log.w(TAG, "Opus native library unavailable, using the platform decoder", error)
                opusNativeUsable = false
                return null
            }
        }
        return try {
            OpusDecoder(sampleRate, channels).also { opusNativeUsable = true }
        } catch (error: LinkageError) {
            // The library loaded but cannot be called: nothing a later track will do better.
            Log.w(TAG, "Opus native decoder unusable, using the platform decoder", error)
            opusNativeUsable = false
            null
        }
    }

    /**
     * [outputRate] is what the caller wants; [sampleRate] is the nearest rate Opus will decode at,
     * which is rarely the same one. Converting here rather than afterwards keeps a whole track from
     * being held at both rates at once.
     */
    private fun decodeOpusMono(
        extractor: MediaExtractor,
        actualStart: Double,
        endUs: Long,
        sampleRate: Int,
        channels: Int,
        outputRate: Int?,
    ): Pair<Pcm, Double>? {
        val decoder = openOpusDecoder(sampleRate, channels) ?: return null
        val downsampler = resamplerTo(outputRate, sampleRate, actualStart, endUs)
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
                        if (downsampler != null) downsampler.add(buffer, bufferPos)
                        else collected += buffer.copyOf(bufferPos)
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

            if (bufferPos > 0) {
                if (downsampler != null) downsampler.add(buffer, bufferPos)
                else collected += buffer.copyOf(bufferPos)
            }
            if (total == 0) return null

            val samples = downsampler?.finish() ?: FloatArray(total).also { merged ->
                var offset = 0
                for (chunk in collected) {
                    chunk.copyInto(merged, offset)
                    offset += chunk.size
                }
            }
            val rate = downsampler?.outputRate ?: sampleRate.toDouble()

            Log.d(
                TAG,
                "decode ${System.currentTimeMillis() - decodeStarted}ms via Kopus " +
                    "(mono, ${samples.size} @ ${rate.toInt()}Hz from ${sampleRate}Hz)",
            )
            return Pcm(samples, rate) to actualStart
        } finally {
            decoder.close()
        }
    }

    /** As [decodeOpusMono]: [outputRate] is what was asked for, [sampleRate] what Opus can give. */
    private fun decodeOpusStereo(
        extractor: MediaExtractor,
        actualStart: Double,
        endUs: Long,
        sampleRate: Int,
        channels: Int,
        outputRate: Int?,
    ): Pair<StereoPcm, Double>? {
        val decoder = openOpusDecoder(sampleRate, channels) ?: return null
        val leftDown = resamplerTo(outputRate, sampleRate, actualStart, endUs)
        val rightDown = resamplerTo(outputRate, sampleRate, actualStart, endUs)
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
                        if (leftDown != null && rightDown != null) {
                            leftDown.add(leftBuf, bufferPos)
                            rightDown.add(rightBuf, bufferPos)
                        } else {
                            leftChunks += leftBuf.copyOf(bufferPos)
                            rightChunks += rightBuf.copyOf(bufferPos)
                        }
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
                if (leftDown != null && rightDown != null) {
                    leftDown.add(leftBuf, bufferPos)
                    rightDown.add(rightBuf, bufferPos)
                } else {
                    leftChunks += leftBuf.copyOf(bufferPos)
                    rightChunks += rightBuf.copyOf(bufferPos)
                }
            }
            if (total == 0) return null

            val left: FloatArray
            val right: FloatArray
            if (leftDown != null && rightDown != null) {
                left = leftDown.finish()
                right = rightDown.finish()
            } else {
                left = FloatArray(total)
                right = FloatArray(total)
                var offset = 0
                for (i in leftChunks.indices) {
                    leftChunks[i].copyInto(left, offset)
                    rightChunks[i].copyInto(right, offset)
                    offset += leftChunks[i].size
                }
            }
            val rate = leftDown?.outputRate ?: sampleRate.toDouble()

            Log.d(
                TAG,
                "decode ${System.currentTimeMillis() - decodeStarted}ms via Kopus " +
                    "(stereo, ${left.size} @ ${rate.toInt()}Hz from ${sampleRate}Hz)",
            )
            return StereoPcm(left, right, rate) to actualStart
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
