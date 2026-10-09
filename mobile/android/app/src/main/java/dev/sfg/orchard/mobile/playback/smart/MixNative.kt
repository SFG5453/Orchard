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

import android.util.Log

/**
 * The desktop adaptive-mix worker, in process: `crates/orchard-transition-mobile/src/mix.rs`
 * over `orchard-adaptive-mix`. Analysis, Best Mix, the QuickJS planner and the render are the
 * desktop's own code; Kotlin only decodes audio and plays the result.
 *
 * Every handle is a native allocation the caller frees exactly once.
 */
internal object MixNative {
    private const val TAG = "OrchardMixNative"

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("orchard_earmark") }
            .onFailure { Log.w(TAG, "Adaptive mix library unavailable", it) }
            .isSuccess
    }

    /** Answers the sorter's `needPair` like the desktop host: full left tail, full right head. */
    fun interface BestMixPairs {
        /** `left` is -1 for the song already playing. Returns `{"left","right"}` or `{"error"}`. */
        fun pair(left: Int, right: Int): String
    }

    @JvmStatic external fun nativeSongBegin(rate: Int, head: Boolean, tail: Boolean): Long
    @JvmStatic external fun nativeSongPush(builder: Long, samples: FloatArray, count: Int): Boolean
    @JvmStatic external fun nativeBuilderFree(builder: Long)
    @JvmStatic external fun nativeSongFinish(builder: Long): Long
    @JvmStatic external fun nativeSongDuration(song: Long): Double
    @JvmStatic external fun nativeSongFree(song: Long)
    @JvmStatic external fun nativeBestMixAnalyze(song: Long, duration: Double): String
    @JvmStatic external fun nativeBestMixSort(summaries: String, initial: String, pairs: BestMixPairs): String
    @JvmStatic external fun nativeModelsLoad(beat: ByteArray, vocal: ByteArray, threads: Int): Long
    @JvmStatic external fun nativeModelsFree(models: Long)
    /** Interleaved stereo float overlap at [rate], framed as a u32 header length, header, PCM. */
    @JvmStatic external fun nativeMix(models: Long, request: String, outgoing: Long, incoming: Long, rate: Int): ByteArray?
}
