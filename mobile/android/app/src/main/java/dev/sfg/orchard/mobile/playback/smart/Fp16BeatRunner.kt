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

/** FP16 Beat This! through LiteRT's GPU compiled model, owned by the analysis thread. */
internal class Fp16BeatRunner(context: Context) : AutoCloseable {
    private val environment = Environment.create(context)
    private val model: CompiledModel
    private val inputs: List<TensorBuffer>
    private val outputs: List<TensorBuffer>

    init {
        try {
            check(Accelerator.GPU in environment.getAvailableAccelerators()) {
                "LiteRT GPU accelerator is unavailable"
            }
            // The exported graph has a few BROADCAST_TO nodes which stay on CPU.
            model = CompiledModel.create(
                context.assets,
                "beat_this_fp16_gpu.tflite",
                CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                    // The repaired graph keeps RMS normalization and rotary phases stable in FP16.
                    gpuOptions = CompiledModel.GpuOptions(
                        precision = CompiledModel.GpuOptions.Precision.FP16,
                        backend = CompiledModel.GpuOptions.Backend.OPENCL,
                    )
                },
                environment,
            )
            inputs = model.createInputBuffers()
            outputs = model.createOutputBuffers()
            check(inputs.size == 1 && outputs.size == 2)
        } catch (error: Throwable) {
            environment.close()
            throw error
        }
    }

    fun infer(chunk: FloatArray): Pair<FloatArray, FloatArray> {
        check(chunk.size == BeatTracker.CHUNK_FRAMES * 128)
        inputs[0].writeFloat(chunk)
        model.run(inputs, outputs)
        val beat = outputs[0].readFloat()
        val downbeat = outputs[1].readFloat()
        check(beat.size == BeatTracker.CHUNK_FRAMES && downbeat.size == BeatTracker.CHUNK_FRAMES)
        check(beat.all(Float::isFinite) && downbeat.all(Float::isFinite))
        return beat to downbeat
    }

    override fun close() {
        outputs.forEach(TensorBuffer::close)
        inputs.forEach(TensorBuffer::close)
        model.close()
        environment.close()
    }
}
