/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

package dev.sfg.orchard.mobile.playback.smart.npu

import android.util.Log

/** `liborchard_npu.so` (src/main/cpp/npu): Neuron and QNN backends for Nirovi and Syvenu. */
internal object NpuNative {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("orchard_npu") }
            .onFailure { Log.w("OrchardNpu", "NPU library unavailable", it) }
            .isSuccess
    }

    /** `{"detected","library","version","devices","supported","reason"}` for the phone's Neuron adapter. */
    @JvmStatic external fun nativeDetectNeuron(beatGraph: String, vocalGraph: String): String
    /** An open backend, compiling into [cacheDir] on a miss; 0 with [nativeLastError] set. */
    @JvmStatic external fun nativeOpenNeuron(beatGraph: String, vocalGraph: String, cacheDir: String): Long
    @JvmStatic external fun nativeOpenQnn(addonLibDir: String, beatModel: String, vocalModel: String, cacheDir: String): Long
    @JvmStatic external fun nativeLastError(): String
    /** "" when both models produce finite output for a silent window. */
    @JvmStatic external fun nativeSelfTest(handle: Long): String
    /** The C table the Rust models call through; see `orchard-adaptive-mix/src/npu.rs`. */
    @JvmStatic external fun nativeVtable(): Long
    @JvmStatic external fun nativeClose(handle: Long)
}
