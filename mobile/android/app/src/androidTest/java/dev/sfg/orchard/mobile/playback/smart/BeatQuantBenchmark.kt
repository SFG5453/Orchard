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
import ai.onnxruntime.TensorInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Explicitly selected CPU quantization benchmark. */
class BeatQuantBenchmark {
    @Test
    fun runVariant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Select variant/backend explicitly to run this benchmark",
            args.containsKey("variant") || args.containsKey("backend"))
        val variant = requireNotNull(args.getString("variant"))
        val backend = requireNotNull(args.getString("backend"))
        require(backend == "cpu")
        require(variant.matches(Regex("[a-z0-9_]+")))
        require(backend != "cpu" || variant.substringAfter("_") in setOf("int8", "a8w8", "int4")) {
            "CPU benchmarks are restricted to INT8 or lower"
        }
        val validateOnly = args.getString("validateOnly") == "true"
        val report = JSONObject().put("phase", if (validateOnly) "validation" else "benchmark").put("variant", variant).put("backend", backend)
            .put("device", android.os.Build.MODEL).put("soc", android.os.Build.SOC_MODEL)
        val model = File(instrumentation.targetContext.cacheDir, "beat_quant.onnx")
        try {
            instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/beat-quant/$variant.onnx").use { fd ->
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    model.outputStream().use { input.copyTo(it) }
                }
            }
            report.put("bytes", model.length())
            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(4)
                opts.setCPUArenaAllocator(false)
                opts.setMemoryPatternOptimization(false)
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                opts.addCPU(false)
                val power = instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java)
                val cooldownDeadline = SystemClock.elapsedRealtime() + 180_000
                while (power.currentThermalStatus > android.os.PowerManager.THERMAL_STATUS_LIGHT &&
                    SystemClock.elapsedRealtime() < cooldownDeadline) {
                    Thread.sleep(1000)
                }
                check(power.currentThermalStatus <= android.os.PowerManager.THERMAL_STATUS_LIGHT) {
                    "Phone did not cool down before benchmark"
                }
                report.put("thermal_start", power.currentThermalStatus)
                val start = SystemClock.elapsedRealtimeNanos()
                env.createSession(model.absolutePath, opts).use { session ->
                    report.put("load_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                    if (args.getString("accuracy") == "true") {
                        val frames = (session.inputInfo.values.first().info as TensorInfo).shape[1].toInt()
                        require(frames in setOf(300, 1500))
                        val inputFile = File(instrumentation.targetContext.cacheDir, "accuracy-input.bin")
                        instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/beat-accuracy/input.bin").use { fd ->
                            ParcelFileDescriptor.AutoCloseInputStream(fd).use { source ->
                                inputFile.outputStream().use { source.copyTo(it) }
                            }
                        }
                        val chunkBytes = frames * 128 * 4
                        require(inputFile.length() > 0 && inputFile.length() % chunkBytes == 0L)
                        val chunks = (inputFile.length() / chunkBytes).toInt()
                        val outputFile = File(instrumentation.targetContext.cacheDir, "accuracy-$variant-$backend.bin")
                        inputFile.inputStream().buffered().use { source ->
                            java.io.DataInputStream(source).use { data ->
                                outputFile.outputStream().buffered().use { sink ->
                                    val bytes = ByteArray(chunkBytes)
                                    repeat(chunks) { index ->
                                        data.readFully(bytes)
                                        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                        OnnxTensor.createTensor(env, floats, longArrayOf(1, frames.toLong(), 128)).use { input ->
                                            session.run(mapOf(session.inputNames.first() to input)).use { outputs ->
                                                for (name in listOf("beat", "downbeat")) {
                                                    val buffer = (outputs.get(name).get() as OnnxTensor).floatBuffer
                                                    check(buffer.remaining() == frames)
                                                    val encoded = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
                                                    while (buffer.hasRemaining()) {
                                                        val value = buffer.get()
                                                        check(value.isFinite())
                                                        encoded.putFloat(value)
                                                    }
                                                    sink.write(encoded.array())
                                                }
                                            }
                                        }
                                        if (index % 100 == 0) android.util.Log.i("BeatQuant", "accuracy $variant $backend chunk $index/$chunks")
                                    }
                                }
                            }
                        }
                        inputFile.delete()
                        report.put("phase", "accuracy").put("chunks", chunks).put("status", "ok")
                            .put("frames", frames).put("thermal_end", power.currentThermalStatus)
                        return
                    }
                    // Same deterministic synthetic workload for all variants; timing, not accuracy validation.
                    val frames = (session.inputInfo.values.first().info as TensorInfo).shape[1].toInt()
                    require(frames in 1..1500)
                    report.put("frames", frames)
                    val values = FloatArray(frames * 128) { i ->
                        val frame = i / 128
                        val band = i % 128
                        (0.3 + 6.0 * kotlin.math.exp(-band / 45.0) +
                            (if (frame % 24 < 3) 3.0 else 0.0) * kotlin.math.exp(-band / 70.0)).toFloat()
                    }
                    val times = mutableListOf<Double>()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1,frames.toLong(),128)).use { input ->
                        repeat(if (validateOnly) 1 else 9) { iteration ->
                            val t = SystemClock.elapsedRealtimeNanos()
                            session.run(mapOf(session.inputNames.first() to input)).use { outputs ->
                                val elapsed = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                                if (iteration >= 2) times.add(elapsed)
                                for (output in outputs) {
                                    val buffer = (output.value as OnnxTensor).floatBuffer
                                    while (buffer.hasRemaining()) check(buffer.get().isFinite())
                                }
                            }
                        }
                    }
                    if (!validateOnly) {
                        report.put("timings_ms", org.json.JSONArray(times))
                            .put("median_ms", times.sorted()[3])
                    }
                    report.put("thermal_end", power.currentThermalStatus)
                    report.put("status", "ok")
                }
            }
        } catch (error: Exception) {
            report.put("status", "unsupported_or_error").put("error", error.toString())
            if (args.getString("accuracy") == "true") throw error
        } finally {
            model.delete()
            println("BEAT_QUANT_RESULT $report")
            android.util.Log.i("BeatQuant", report.toString())
        }
    }
}
