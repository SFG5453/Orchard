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

import android.content.Context
import android.media.MediaDataSource
import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer

/** Standard is a trailing equal-power fade; smart is the desktop adaptive mix. */
enum class CrossfadeMode { STANDARD, SMART }

/** What preparing a pair produced, the three outcomes the desktop host also tells apart. */
internal sealed interface PreparedMix {
    /**
     * The rendered overlap as interleaved stereo PCM16 at [rate]. Times are track seconds:
     * the render replaces the outgoing song from [outgoingStart], and the incoming song plays
     * on from [incomingResume], reached by starting it at [incomingCue].
     */
    class Ready(
        val outgoingStart: Double,
        val incomingCue: Double,
        val incomingResume: Double,
        val duration: Double,
        val strategy: String,
        val targetBpm: Double,
        /** The render's rate: the outgoing player's own. */
        val rate: Int,
        /** The incoming player's rate, which its resume frame is counted in. */
        val incomingRate: Int,
        val pcm: ShortArray,
        val log: String,
    ) : PreparedMix

    /** The shared planner keeps the natural boundary: this song ends, the next one starts. */
    data class NoMix(val reason: String) : PreparedMix

    /** Preparation broke; playback falls back to the standard crossfade, as on desktop. */
    data class Failed(val reason: String) : PreparedMix
}

/**
 * The Android host of the desktop adaptive-mix worker (`AdaptiveMixController` on desktop).
 * Same request, same Rust `mix`, same QuickJS planner; only decoding and playback are local.
 */
internal object AdaptiveMix {
    private const val TAG = "OrchardAdaptiveMix"

    /** Matches `planner::NATURAL_BOUNDARY` in `orchard-adaptive-mix`. */
    internal const val NATURAL_BOUNDARY = "Shared planner keeps the natural boundary"

    /** Beat This's mobile export and the INT8 UMX vocals; same graphs, quantized weights. */
    private const val BEAT_MODEL = "beat_this_int8.onnx"
    private const val VOCAL_MODEL = "vocals_umxhq_int8.onnx"
    // Two threads leave the big cores to the UI; preparation has a two-minute head start anyway.
    private const val INFERENCE_THREADS = 2

    /**
     * Runs on the caller's thread, which needs a deep stack: QuickJS plans inside it.
     * [models] is asked only after both songs decode; the caller owns the handle it returns.
     */
    fun prepare(
        models: () -> Long?,
        outgoing: () -> MediaDataSource?,
        incoming: () -> MediaDataSource?,
        request: JSONObject,
    ): PreparedMix {
        if (!MixNative.available) return PreparedMix.Failed("Adaptive mix library unavailable")
        val started = System.currentTimeMillis()
        val outgoingSong = outgoing()?.use { SongDecoder.decode(it, tail = true) }
            ?: return PreparedMix.Failed("Could not decode the outgoing song")
        outgoingSong.use {
            val incomingSong = incoming()?.use { SongDecoder.decode(it, head = true) }
                ?: return PreparedMix.Failed("Could not decode the incoming song")
            incomingSong.use {
                val decoded = System.currentTimeMillis()
                val handle = models() ?: return PreparedMix.Failed("Adaptive mix models are unavailable")
                val framed = MixNative.nativeMix(handle, request.toString(), outgoingSong.handle,
                    incomingSong.handle, outgoingSong.rate)
                Log.d(TAG, "Prepared in ${System.currentTimeMillis() - started}ms " +
                    "(decode ${decoded - started}ms)")
                return parse(framed ?: return PreparedMix.Failed("Adaptive mix returned nothing"), incomingSong.rate)
            }
        }
    }

    /** Builds both ONNX sessions; free the handle with [freeModels]. Session threads inherit the caller's priority. */
    fun loadModels(context: Context): Long? = runCatching {
        if (!MixNative.available) return null
        // The app's ONNX Runtime; Rust binds to this copy instead of shipping its own.
        System.loadLibrary("onnxruntime")
        val started = System.currentTimeMillis()
        val beat = context.assets.open(BEAT_MODEL).use { it.readBytes() }
        val vocal = context.assets.open(VOCAL_MODEL).use { it.readBytes() }
        MixNative.nativeModelsLoad(beat, vocal, INFERENCE_THREADS).takeIf { it != 0L }
            ?.also { Log.d(TAG, "Models loaded in ${System.currentTimeMillis() - started}ms") }
    }.onFailure { Log.w(TAG, "Could not load adaptive mix models", it) }.getOrNull()

    fun freeModels(handle: Long) = MixNative.nativeModelsFree(handle)

    private fun parse(framed: ByteArray, incomingRate: Int): PreparedMix {
        val buffer = ByteBuffer.wrap(framed)
        if (framed.size < 4) return PreparedMix.Failed("Invalid adaptive mix response")
        val length = buffer.int
        if (length < 0 || length > framed.size - 4) return PreparedMix.Failed("Invalid adaptive mix response")
        val header = JSONObject(String(framed, 4, length))
        header.optString("error").takeIf { it.isNotEmpty() }?.let { error ->
            return if (error.startsWith(NATURAL_BOUNDARY)) PreparedMix.NoMix(error) else PreparedMix.Failed(error)
        }
        val bytes = header.optInt("bytes", -1)
        if (bytes != framed.size - 4 - length || bytes % 8 != 0) return PreparedMix.Failed("Invalid rendered overlap")
        buffer.position(4 + length)
        val floats = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        // The render's summing limiter may leave peaks above 1.0; PCM16 cannot carry them.
        val pcm = ShortArray(bytes / 4) { (floats.get(it).coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        return PreparedMix.Ready(
            outgoingStart = header.getDouble("outgoingStart"),
            incomingCue = header.getDouble("incomingCue"),
            incomingResume = header.getDouble("incomingResume"),
            duration = header.getDouble("duration"),
            strategy = header.optString("strategy"),
            targetBpm = header.optDouble("targetBpm", 0.0),
            rate = header.getInt("rate"),
            incomingRate = incomingRate,
            pcm = pcm,
            log = planLog(header),
        )
    }

    /** Same fields as desktop's "AdaptiveMix: plan" line, for matching a mix to its plan. */
    internal fun planLog(header: JSONObject): String {
        val loop = header.optInt("loopBeats")
        val lock = header.optDouble("kickLock", 0.0)
        return "strategy ${header.optString("strategy")} | bpm ${header.optDouble("targetBpm")} | " +
            "out ${header.optDouble("outgoingStart")} | cue ${header.optDouble("incomingCue")} | " +
            "dur ${header.optDouble("duration")}" +
            (if (loop > 0) " | loop $loop beats" else "") +
            (if (lock != 0.0) " | kick lock $lock ms" else "") +
            (if (header.optString("warning").isNotEmpty() && !header.isNull("warning")) " | no vocal shaping" else "")
    }
}
