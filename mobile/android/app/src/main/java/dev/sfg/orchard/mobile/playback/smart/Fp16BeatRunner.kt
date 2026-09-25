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
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.playback.smart

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

/** FP16 Beat This! through LiteRT's GPU compiled model, owned by the analysis thread. */
internal class Fp16BeatRunner(
    context: Context,
    private val modelFile: File? = null,
    private val chunkFrames: Int = BeatTracker.GPU_CHUNK_FRAMES,
) : AutoCloseable {
    private val environment = Environment.create(context)
    private val model: CompiledModel
    private val inputs: List<TensorBuffer>
    private val outputs: List<TensorBuffer>

    init {
        try {
            check(Accelerator.GPU in environment.getAvailableAccelerators()) {
                "LiteRT GPU accelerator is unavailable"
            }
            // The model is closed after each track to free its large GPU allocation. Keep only
            // compiled OpenCL programs on disk so the next track avoids compiling them again.
            val programCacheDir = if (modelFile == null) {
                File(context.cacheDir, "beat-gpu-program-cache")
                    .takeIf { it.isDirectory || it.mkdirs() }
            } else null
            // The exported graph has a few BROADCAST_TO nodes which stay on CPU.
            val options = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                // The repaired graph keeps RMS normalization and rotary phases stable in FP16.
                gpuOptions = CompiledModel.GpuOptions(
                    precision = CompiledModel.GpuOptions.Precision.FP16,
                    backend = CompiledModel.GpuOptions.Backend.OPENCL,
                    serializationDir = programCacheDir?.absolutePath,
                    modelCacheKey = if (programCacheDir != null) MODEL_CACHE_KEY else null,
                    serializeProgramCache = if (programCacheDir != null) true else null,
                )
            }
            model = if (modelFile != null) {
                CompiledModel.create(modelFile.absolutePath, options, environment)
            } else {
                CompiledModel.create(context.assets, "beat_this_fp16_gpu.tflite", options, environment)
            }
            inputs = model.createInputBuffers()
            outputs = model.createOutputBuffers()
            check(inputs.size == 1 && outputs.size == 2)
        } catch (error: Throwable) {
            environment.close()
            throw error
        }
    }

    fun infer(chunk: FloatArray): Pair<FloatArray, FloatArray> {
        check(chunk.size == chunkFrames * 128)
        inputs[0].writeFloat(chunk)
        model.run(inputs, outputs)
        val beat = outputs[0].readFloat()
        val downbeat = outputs[1].readFloat()
        check(beat.size == chunkFrames && downbeat.size == chunkFrames)
        check(beat.all(Float::isFinite) && downbeat.all(Float::isFinite))
        return beat to downbeat
    }

    override fun close() {
        outputs.forEach(TensorBuffer::close)
        inputs.forEach(TensorBuffer::close)
        model.close()
        environment.close()
    }

    private companion object {
        // Model SHA-256 is 2de6374233c93a3389477ef76747bdedb631aa09c5feaf618b9f156d7ae09b68.
        // Change this key when the asset, GPU backend, precision, or LiteRT version changes.
        const val MODEL_CACHE_KEY = "beat_this_fp16_gpu_2de6374233c93a33_opencl_fp16_litert220"
    }
}
