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

import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-mel GPU probe for an adb-staged Beat This LiteRT candidate. */
@RunWith(AndroidJUnit4::class)
class BeatFp16GpuProbeDeviceTest {
    @Test
    fun compareCandidateWithHostFp32() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("beatFp16Probe") == "true")
        val context = instrumentation.targetContext

        fun stage(name: String): File {
            val path = args.getString(name) ?: error("Missing $name")
            require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9_./-]+")))
            val file = File(context.cacheDir, "$name-${path.substringAfterLast('/')}")
            instrumentation.uiAutomation.executeShellCommand("cat $path").use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    file.outputStream().use(input::copyTo)
                }
            }
            return file
        }

        fun floats(file: File): FloatArray {
            val bytes = file.readBytes()
            require(bytes.size % Float.SIZE_BYTES == 0)
            return FloatArray(bytes.size / Float.SIZE_BYTES).also {
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
            }
        }

        val modelFile = stage("beatModelPath")
        val input = floats(stage("beatInputPath"))
        val expected = floats(stage("beatReferencePath"))
        assertEquals(BeatTracker.CHUNK_FRAMES * 128, input.size)
        val intermediateProbe = args.getString("beatIntermediateProbe") == "true"
        if (!intermediateProbe) {
            assertEquals(BeatTracker.CHUNK_FRAMES * 2, expected.size)
        }
        val precision = CompiledModel.GpuOptions.Precision.valueOf(
            args.getString("beatGpuPrecision") ?: "FP32",
        )
        val maxMae = args.getString("beatMaxMae")?.toDoubleOrNull()
        val minPeakMatchRate = args.getString("beatMinPeakMatchRate")?.toDoubleOrNull()
        val backend = CompiledModel.GpuOptions.Backend.valueOf(
            args.getString("beatGpuBackend") ?: "OPENCL",
        )
        val measureMemory = args.getString("beatMeasureMemory") == "true"
        fun logMemory(stage: String) {
            if (!measureMemory) return
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            val status = File("/proc/self/status").readLines()
            fun kilobytes(key: String): String = status.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim() ?: "unavailable"
            Log.i("BeatFp16GpuProbe", "$backend $precision memory $stage " +
                "pssKb=${memory.totalPss} privateDirtyKb=${memory.totalPrivateDirty} " +
                "nativeHeapKb=${Debug.getNativeHeapAllocatedSize() / 1024} " +
                "graphicsKb=${memory.getMemoryStat("summary.graphics")} " +
                "VmRSS=${kilobytes("VmRSS")} VmHWM=${kilobytes("VmHWM")}")
        }
        val environment = Environment.create(context)
        try {
            logMemory("beforeCompile")
            val started = System.nanoTime()
            val model = CompiledModel.create(
                modelFile.absolutePath,
                CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                    gpuOptions = CompiledModel.GpuOptions(
                        precision = precision,
                        backend = backend,
                    )
                },
                environment,
            )
            try {
                logMemory("afterCompile")
                val inputs = model.createInputBuffers()
                val outputs = model.createOutputBuffers()
                try {
                    logMemory("afterBuffers")
                    val compileMs = (System.nanoTime() - started) / 1_000_000
                    inputs[0].writeFloat(input)
                    val inferenceStart = System.nanoTime()
                    model.run(inputs, outputs)
                    val inferenceMs = (System.nanoTime() - inferenceStart) / 1_000_000
                    logMemory("afterRun")
                    val actualOutputs = outputs.map { it.readFloat() }
                    assertEquals(expected.size, actualOutputs.sumOf { it.size })
                    if (intermediateProbe) {
                        var offset = 0
                        actualOutputs.forEachIndexed { index, values ->
                            val mae = values.indices.sumOf { i ->
                                abs(values[i] - expected[offset + i]).toDouble()
                            } / values.size
                            Log.i("BeatFp16GpuProbe", "$backend $precision output[$index] " +
                                "size=${values.size} MAE=$mae min=${values.min()} " +
                                "max=${values.max()} compileMs=$compileMs " +
                                "inferenceMs=$inferenceMs")
                            offset += values.size
                        }
                        return
                    }
                    val actual = actualOutputs.flatMap { it.asIterable() }
                    val beat = actual.take(BeatTracker.CHUNK_FRAMES)
                    val downbeat = actual.drop(BeatTracker.CHUNK_FRAMES)
                    assertTrue(actual.all(Float::isFinite))
                    for ((name, values, offset) in listOf(
                        Triple("beat", beat, 0),
                        Triple("downbeat", downbeat, BeatTracker.CHUNK_FRAMES),
                    )) {
                        val mae = values.indices.sumOf { i ->
                            abs(values[i] - expected[offset + i]).toDouble()
                        } / values.size
                        Log.i("BeatFp16GpuProbe", "$backend $precision $name MAE=$mae " +
                            "min=${values.min()} max=${values.max()} " +
                            "compileMs=$compileMs inferenceMs=$inferenceMs")
                        fun peaks(data: List<Float>): List<Int> =
                            (3 until data.size - 3).filter { frame ->
                                data[frame] > 0f &&
                                    (frame - 3..frame + 3).all { data[frame] >= data[it] }
                            }
                        val found = peaks(values)
                        val reference = peaks(expected.copyOfRange(offset, offset + values.size).toList())
                        val matched = reference.count { frame ->
                            found.any { kotlin.math.abs(it - frame) <= 4 }
                        }
                        Log.i("BeatFp16GpuProbe", "$backend $precision $name peaks " +
                            "reference=${reference.size} actual=${found.size} matched=$matched")
                        if (maxMae != null) {
                            assertTrue("$name MAE $mae exceeds $maxMae", mae < maxMae)
                        }
                        if (minPeakMatchRate != null && reference.isNotEmpty()) {
                            assertTrue("$name matched $matched/${reference.size} peaks",
                                matched.toDouble() / reference.size >= minPeakMatchRate)
                        }
                        if (precision == CompiledModel.GpuOptions.Precision.FP32) {
                            assertTrue("FP32 GPU $name diverged from the host reference: $mae", mae < 0.01)
                        }
                    }
                } finally {
                    outputs.forEach { it.close() }
                    inputs.forEach { it.close() }
                }
            } finally {
                model.close()
                logMemory("afterModelClose")
            }
        } finally {
            environment.close()
            logMemory("afterEnvironmentClose")
        }
    }
}
