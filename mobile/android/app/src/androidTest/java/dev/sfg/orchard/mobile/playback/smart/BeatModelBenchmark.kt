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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

/**
 * Measures Beat This! inference on the device, fp16 against the shipping int8.
 *
 * This is a benchmark, so it asserts nothing about timing. Numerical agreement between the two
 * graphs was already established off-device by `tools/convert_beat_this_fp16.py`; what cannot be
 * established off -device is which one is actually faster on ARM, which is the whole question. Run
 * with: ./gradlew :app:connectedDebugAndroidTest \
 * ```
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.sfg.orchard.mobile.playback.smart.BeatModelBenchmark
 * ```
 * Results land in logcat under the [TAG] tag.
 */
class BeatModelBenchmark {

    /** Holds the models: they are packaged into the test APK, not the app's. */
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    /** Holds a real, provisioned data directory, as the test package has none. */
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** Copied out of assets because ORT wants a path or a byte array, not a stream. */
    private fun materialize(asset: String): File {
        val target = File(appContext.cacheDir, asset)
        if (target.exists() && target.length() > 0) return target
        testContext.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    /**
     * A log-mel spectrogram shaped like music rather than noise with spectral tilt, a periodic beat
     * grid, sustained harmonics, a per-band floor. Mirrors the synthetic input the conversion
     * script verifies against, so the two sets of numbers describe the same workload.
     */
    private fun spectrogram(frames: Int, bpm: Double = 126.0): FloatArray {
        val random = Random(7)
        val values = FloatArray(frames * MELS)
        val floor = DoubleArray(MELS) { 0.15 + random.nextDouble() * 0.35 }
        for (frame in 0 until frames) {
            for (band in 0 until MELS) {
                var value = exp(-band / 45.0) * 6.0 + floor[band]
                value += random.nextDouble(-0.2, 0.2)
                values[frame * MELS + band] = value.toFloat()
            }
            for (band in intArrayOf(12, 19, 26, 38, 51)) {
                values[frame * MELS + band] += (1.8 + 0.4 * sin(frame / 37.0)).toFloat()
            }
        }
        val beatFrames = FRAME_RATE * 60.0 / bpm
        var index = 0
        while (true) {
            val onset = Math.round(index * beatFrames).toInt()
            if (onset >= frames) break
            val downbeat = index % 4 == 0
            val strength = if (downbeat) 4.5 else 2.6
            for ((offset, decay) in doubleArrayOf(1.0, 0.6, 0.35, 0.18).withIndex()) {
                val frame = onset + offset
                if (frame >= frames) break
                for (band in 0 until MELS) {
                    val weight = exp(-band / if (downbeat) 90.0 else 70.0)
                    values[frame * MELS + band] += (strength * decay * weight).toFloat()
                }
            }
            index += 1
        }
        return values
    }

    private fun session(
            environment: OrtEnvironment,
            path: String,
            threads: Int,
            level: OrtSession.SessionOptions.OptLevel,
    ): OrtSession {
        val options =
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads)
                    setOptimizationLevel(level)
                }
        return environment.createSession(path, options)
    }

    private fun benchmark(
            asset: String,
            frames: Int,
            threads: Int,
            force: OrtSession.SessionOptions.OptLevel? = null,
    ): LongArray {
        val environment = OrtEnvironment.getEnvironment()
        val path = materialize(asset).absolutePath

        val loadStarted = System.nanoTime()
        if (force != null) {
            val forced = session(environment, path, threads, force)
            return measure(environment, forced, asset, frames, threads, force, loadStarted)
        }
        // ORT's extended optimizations fuse this model's Erf chains into
        // com.microsoft.Gelu, which onnxruntime-android has no fp16 kernel for.
        // Dropping to EXTENDED, then BASIC, finds the highest level that will
        // actually load. Which level that was is part of the result, since a model
        // that has to give up fusions is not competing on equal terms.
        var level = OrtSession.SessionOptions.OptLevel.ALL_OPT
        val session =
                try {
                    session(environment, path, threads, level)
                } catch (all: Exception) {
                    try {
                        level = OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
                        session(environment, path, threads, level)
                    } catch (extended: Exception) {
                        level = OrtSession.SessionOptions.OptLevel.BASIC_OPT
                        session(environment, path, threads, level)
                    }
                }
        return measure(environment, session, asset, frames, threads, level, loadStarted)
    }

    private fun measure(
            environment: OrtEnvironment,
            session: OrtSession,
            asset: String,
            frames: Int,
            threads: Int,
            level: OrtSession.SessionOptions.OptLevel,
            loadStarted: Long,
    ): LongArray {
        val loadMs = (System.nanoTime() - loadStarted) / 1_000_000

        val input = spectrogram(frames)
        val shape = longArrayOf(1, frames.toLong(), MELS.toLong())
        val name = session.inputNames.first()

        val timings = ArrayList<Long>(ITERATIONS)
        repeat(WARMUP + ITERATIONS) { iteration ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
                val started = System.nanoTime()
                session.run(mapOf(name to tensor)).use { /* outputs discarded; timing only */}
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (iteration >= WARMUP) timings += elapsed
            }
        }
        session.close()

        val sorted = timings.sorted()
        val median = sorted[sorted.size / 2]
        val best = sorted.first()
        val worst = sorted.last()
        Log.i(
                TAG,
                "$asset  threads=$threads frames=$frames opt=$level  load ${loadMs}ms  " +
                        "median ${median}ms  best ${best}ms  worst ${worst}ms",
        )
        return longArrayOf(loadMs, median, best, worst)
    }

    @Test
    fun compareInt8AgainstFp16() {
        Log.i(TAG, "=== Beat This! on-device benchmark ===")
        Log.i(TAG, "device ${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL}")
        Log.i(TAG, "cores ${Runtime.getRuntime().availableProcessors()}")

        for (threads in intArrayOf(1, 4)) {
            for (asset in arrayOf("beat_this_int8.onnx", "beat_this_fp16.onnx")) {
                val (_, median) = benchmark(asset, CHUNK_FRAMES, threads).let { it[0] to it[1] }
                // A chunk is CHUNK_FRAMES/FRAME_RATE seconds of audio. Anything under
                // 1.0 here analyses faster than the track plays.
                val audioSeconds = CHUNK_FRAMES / FRAME_RATE
                Log.i(
                        TAG,
                        "$asset threads=$threads  ${"%.3f".format(median / 1000.0 / audioSeconds)}x realtime",
                )
            }
        }

        // Control: fp16 could only load at BASIC_OPT, so the headline comparison
        // is not like-for-like. Running int8 at BASIC too separates "fp16 is
        // slower" from "losing the extended fusions is slower".
        Log.i(TAG, "--- control: int8 held down to BASIC_OPT ---")
        benchmark(
                "beat_this_int8.onnx",
                CHUNK_FRAMES,
                4,
                OrtSession.SessionOptions.OptLevel.BASIC_OPT
        )
    }

    private companion object {
        const val TAG = "BeatBench"
        const val MELS = 128
        const val FRAME_RATE = 50.0
        const val CHUNK_FRAMES = 1500
        const val WARMUP = 2
        const val ITERATIONS = 7
    }
}
