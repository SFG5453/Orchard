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
        var rate = 0
        val sink = object : PcmSink {
            override fun begin(sampleRate: Int): Boolean {
                if (builder != 0L) MixNative.nativeBuilderFree(builder)
                builder = MixNative.nativeSongBegin(sampleRate, head, tail)
                rate = sampleRate
                return builder != 0L
            }
            override fun push(samples: FloatArray, count: Int) = MixNative.nativeSongPush(builder, samples, count)
        }
        try {
            if (!PcmDecoder.decode(source, sink)) return null
            val song = MixNative.nativeSongFinish(builder)
            builder = 0L
            return if (song == 0L) null else DecodedSong(song, rate)
        } finally {
            if (builder != 0L) MixNative.nativeBuilderFree(builder)
        }
    }
}
