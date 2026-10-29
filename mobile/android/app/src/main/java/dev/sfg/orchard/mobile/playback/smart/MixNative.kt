/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

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
    /** Desktop's `bestMixApple`: Best Mix features from Apple Music's analysis JSON, or `{"error"}`. */
    @JvmStatic external fun nativeBestMixApple(analysis: String, duration: Double): String
    @JvmStatic external fun nativeBestMixSort(summaries: String, initial: String, pairs: BestMixPairs): String
    /** A nonzero [npu] is an open NPU backend that the models take over, with [vtable] from `NpuNative`. */
    @JvmStatic external fun nativeModelsLoad(beat: String, vocal: String, threads: Int, npu: Long, vtable: Long): Long
    @JvmStatic external fun nativeModelsFree(models: Long)
    /** False when the request's Apple Music analysis covers both songs, so no model has to load. */
    @JvmStatic external fun nativeMixNeedsModels(request: String): Boolean
    /** Interleaved stereo float overlap at [rate], framed as a u32 header length, header, PCM. */
    @JvmStatic external fun nativeMix(models: Long, request: String, outgoing: Long, incoming: Long, rate: Int): ByteArray?
}
