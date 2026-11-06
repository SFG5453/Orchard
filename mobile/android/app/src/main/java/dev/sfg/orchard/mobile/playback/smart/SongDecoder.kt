/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

package dev.sfg.orchard.mobile.playback.smart

import android.media.MediaDataSource

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

/** Whole-song storage for adaptive mixing; decoding is shared with the fingerprint scanner. */
internal object SongDecoder {
    fun decode(source: MediaDataSource, head: Boolean = false, tail: Boolean = false): DecodedSong? {
        if (!MixNative.available) return null
        var builder = 0L
        var decodedRate = 0
        val sink = object : PcmSink {
            override fun begin(rate: Int): Boolean {
                if (builder != 0L) MixNative.nativeBuilderFree(builder)
                builder = MixNative.nativeSongBegin(rate, head, tail)
                decodedRate = rate
                return builder != 0L
            }
            override fun push(samples: FloatArray, count: Int) = MixNative.nativeSongPush(builder, samples, count)
        }
        try {
            if (!PcmDecoder.decode(source, sink)) return null
            val song = MixNative.nativeSongFinish(builder)
            builder = 0L
            return if (song == 0L) null else DecodedSong(song, decodedRate)
        } finally {
            if (builder != 0L) MixNative.nativeBuilderFree(builder)
        }
    }
}
