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

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import eu.buney.kopus.OpusDecoder
import eu.buney.kopus.OpusLoader
import java.nio.ByteOrder

/** A song decoded into the shared Rust builder at [rate]. Closing frees the native copy. */
internal class DecodedSong(val handle: Long, val rate: Int) : AutoCloseable {
    val duration: Double get() = MixNative.nativeSongDuration(handle)
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        MixNative.nativeSongFree(handle)
    }
}

/**
 * Whole-song decode at the codec's own rate, streamed into `song.rs` as interleaved stereo.
 * Every conversion after decoding is shared Rust, which is what keeps Android's analysis equal
 * to desktop's: the FFmpeg there and MediaCodec here are the only part that differs.
 */
internal object SongDecoder {
    /**
     * Keeps the render windows asked for: a mix reads the outgoing [tail] and incoming [head].
     * Null when the container or codec is unusable; callers treat that as no analysis.
     */
    fun decode(source: MediaDataSource, head: Boolean = false, tail: Boolean = false): DecodedSong? {
        if (!MixNative.available) return null
        opus(source, head, tail)?.let { return it }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var builder = 0L
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            // Float where the codec offers it; 16-bit output is widened below either way.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            codec = createDecoder(mime).apply { configure(format, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var rate = 0
            var channels = 0
            var float = false
            var chunk = FloatArray(0)
            var raw = FloatArray(0)
            var shorts = ShortArray(0)
            while (true) {
                // Fill every free input slot, then wait only when the codec has nothing new:
                // one 20 ms packet per wait spent most of a song's decode asleep.
                var fed = false
                while (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index < 0) break
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    fed = true
                }
                val index = codec.dequeueOutputBuffer(info, if (fed) 0 else TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (index >= 0 && builder == 0L)) {
                    val output = codec.outputFormat
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (builder == 0L) {
                        rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        builder = MixNative.nativeSongBegin(rate, head, tail)
                        if (builder == 0L) return null
                    }
                }
                if (index < 0) continue
                if (info.size > 0 && channels > 0) {
                    val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset).limit(info.offset + info.size)
                    val samples = if (float) info.size / 4 else info.size / 2
                    val frames = samples / channels
                    if (raw.size < samples) raw = FloatArray(samples)
                    if (float) {
                        buffer.asFloatBuffer().get(raw, 0, samples)
                    } else {
                        if (shorts.size < samples) shorts = ShortArray(samples)
                        buffer.asShortBuffer().get(shorts, 0, samples)
                        for (i in 0 until samples) raw[i] = shorts[i] / 32768f
                    }
                    if (chunk.size < frames * 2) chunk = FloatArray(frames * 2)
                    for (frame in 0 until frames) {
                        val left = raw[frame * channels]
                        // Mono doubles up; extra channels beyond the front pair are dropped.
                        chunk[frame * 2] = left
                        chunk[frame * 2 + 1] = if (channels > 1) raw[frame * channels + 1] else left
                    }
                    if (!MixNative.nativeSongPush(builder, chunk, frames * 2)) {
                        codec.releaseOutputBuffer(index, false)
                        return null
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            val song = MixNative.nativeSongFinish(builder)
            builder = 0L
            return if (song == 0L) null else DecodedSong(song, rate)
        } catch (_: Exception) {
            return null
        } finally {
            if (builder != 0L) MixNative.nativeBuilderFree(builder)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /**
     * Opus without the media server: Media3's extractor and libopus, both in process. The
     * platform extractor and decoder each pay a binder round trip per 20 ms packet, which made a
     * 7-minute song take 51 s on a razr 2023; this path takes a few. Null when the stream is not
     * mono/stereo Opus or Kopus cannot load, so MediaCodec takes over.
     */
    private fun opus(source: MediaDataSource, head: Boolean, tail: Boolean): DecodedSong? {
        if (!loadOpus()) return null
        var decoder: OpusDecoder? = null
        var builder = 0L
        var channels = 0
        var skip = 0
        val pcm = FloatArray(MAX_OPUS_FRAME * 2)
        val stereo = FloatArray(MAX_OPUS_FRAME * 2)
        var failed = false
        try {
            val ok = OpusPackets.read(source, onFormat = { format ->
                channels = format.channelCount
                if (format.sampleMimeType != MimeTypes.AUDIO_OPUS || channels !in 1..2) return@read false
                // OpusHead carries the encoder's pre-skip at 48 kHz; FFmpeg and ExoPlayer both drop it.
                skip = format.initializationData.firstOrNull()?.takeIf { it.size >= 12 }
                    ?.let { (it[10].toInt() and 0xFF) or ((it[11].toInt() and 0xFF) shl 8) } ?: 0
                decoder = runCatching { OpusDecoder(OPUS_RATE, channels) }.getOrNull()
                builder = MixNative.nativeSongBegin(OPUS_RATE, head, tail)
                decoder != null && builder != 0L
            }) { packet, size, discardNs ->
                val decoded = decoder!!.decode(packet, 0, size, pcm, 0, MAX_OPUS_FRAME, false)
                if (decoded <= 0) return@read true
                // End padding comes off the decoded frame, as FFmpeg does.
                val frames = decoded - ((discardNs * OPUS_RATE + 500_000_000L) / 1_000_000_000L).toInt().coerceIn(0, decoded)
                val from = minOf(skip, frames)
                skip -= from
                var count = 0
                for (frame in from until frames) {
                    val left = pcm[frame * channels]
                    stereo[count * 2] = left
                    stereo[count * 2 + 1] = if (channels == 2) pcm[frame * 2 + 1] else left
                    count++
                }
                (count == 0 || MixNative.nativeSongPush(builder, stereo, count * 2)).also { failed = !it }
            }
            if (!ok || failed || builder == 0L) return null
            val song = MixNative.nativeSongFinish(builder)
            builder = 0L
            return if (song == 0L) null else DecodedSong(song, OPUS_RATE)
        } catch (_: Exception) {
            return null
        } finally {
            if (builder != 0L) MixNative.nativeBuilderFree(builder)
            decoder?.close()
        }
    }

    private val opusLock = Any()
    @Volatile private var opusUsable: Boolean? = null

    // Kopus marks itself loaded before dlopen returns, so a second thread can call natives that
    // are not registered yet. Best Mix decodes four songs at once; only one may load it.
    private fun loadOpus(): Boolean {
        opusUsable?.let { return it }
        synchronized(opusLock) {
            opusUsable?.let { return it }
            val loaded = runCatching { OpusLoader.load() }.isSuccess
            opusUsable = loaded
            return loaded
        }
    }

    /**
     * Software decoders, in-process first: the same libopus/AAC code ExoPlayer plays, without an
     * IPC round trip per 20 ms packet, which made a long song take half a minute.
     */
    private fun createDecoder(mime: String): MediaCodec {
        val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.isSoftwareOnly &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }.sortedBy { if (".inproc." in it.name) 0 else 1 }
        for (info in candidates) {
            runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull()?.let { return it }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private const val TIMEOUT_US = 10_000L
    private const val OPUS_RATE = 48_000
    /** 120 ms at 48 kHz, Opus's longest frame. */
    private const val MAX_OPUS_FRAME = 5_760
}
