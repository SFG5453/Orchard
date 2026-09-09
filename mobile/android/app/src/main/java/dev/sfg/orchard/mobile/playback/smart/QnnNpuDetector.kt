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

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.os.Build
import java.util.Base64
import java.nio.FloatBuffer

/** The independently testable layers required before Orchard can ask QNN to use an HTP NPU. */
enum class QnnNpuStatus {
    AVAILABLE,
    UNSUPPORTED_ABI,
    RUNTIME_UNAVAILABLE,
    HTP_BACKEND_UNAVAILABLE,
}

data class QnnNpuCapability(
    val status: QnnNpuStatus,
    val preferredAbi: String,
    val detail: String,
) {
    val isAvailable: Boolean get() = status == QnnNpuStatus.AVAILABLE
}

/**
 * Detects a usable Qualcomm HTP backend without guessing from a device name or SoC allow-list.
 *
 * QNN support has two separate halves: ONNX Runtime must contain the QNN execution provider, and
 * the HTP backend must initialize while creating a probe session. Checking both matters: the
 * ARM64 QNN build supplies the former, while an emulator and a non-Qualcomm phone do not supply
 * the latter. Initializing the backend is intentionally done off the main thread by the app graph.
 */
object QnnNpuDetector {
    @Volatile private var cached: QnnNpuCapability? = null

    fun detect(): QnnNpuCapability {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: detectUncached().also { cached = it }
        }
    }

    private fun detectUncached(): QnnNpuCapability {
        // The first ABI is the process's preferred native ABI. Some x86_64 emulators advertise
        // ARM64 translation as a secondary ABI, which must not make them look QNN-capable.
        val preferredAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (preferredAbi != ARM64_ABI) {
            return classifyQnnNpu(preferredAbi, qnnProviderAvailable = false, htpBackendError = null)
        }

        val providers = runCatching { OrtEnvironment.getAvailableProviders() }
            .getOrElse { error ->
                return QnnNpuCapability(
                    QnnNpuStatus.RUNTIME_UNAVAILABLE,
                    preferredAbi,
                    "ONNX Runtime provider query failed: ${error.conciseMessage()}",
                )
            }
        val qnnProviderAvailable = OrtProvider.QNN in providers
        if (!qnnProviderAvailable) {
            return classifyQnnNpu(preferredAbi, qnnProviderAvailable = false, htpBackendError = null)
        }

        val backendError = runCatching {
            System.loadLibrary(HTP_LIBRARY)
            OrtSession.SessionOptions().use { options ->
                options.addQnn(
                    mapOf(
                        "backend_path" to "libQnnHtp.so",
                    ),
                )
                options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                val environment = OrtEnvironment.getEnvironment()
                environment.createSession(PROBE_MODEL, options).use { session ->
                    OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(floatArrayOf(-1f, 0f, 1f, 2f)),
                        longArrayOf(1, 4),
                    ).use { input ->
                        session.run(mapOf("x" to input)).use { output ->
                            val buffer = (output[0] as OnnxTensor).floatBuffer
                            val actual = FloatArray(buffer.remaining()).also { buffer.get(it) }
                            val expected = floatArrayOf(0f, 0f, 1f, 2f)
                            // HTP floating-point execution can round differently from the CPU.
                            check(actual.size == expected.size && expected.indices.all {
                                kotlin.math.abs(actual[it] - expected[it]) <= 0.001f
                            }) {
                                "QNN HTP probe returned an incorrect Relu result: ${actual.contentToString()}"
                            }
                        }
                    }
                }
            }
        }.exceptionOrNull()
        return classifyQnnNpu(preferredAbi, qnnProviderAvailable = true, htpBackendError = backendError)
    }

    private const val ARM64_ABI = "arm64-v8a"
    private const val HTP_LIBRARY = "QnnHtp"

    // An ONNX opset-13 Relu graph with one [1, 4] float input and output. It is deliberately tiny:
    // its job is to verify QNN HTP initialization and inference, not benchmark
    // the NPU or warm the much larger music-analysis models.
    private val PROBE_MODEL = Base64.getDecoder().decode(
        "CAgSB09yY2hhcmQ6QwoMCgF4EgF5IgRSZWx1Eglxbm5fcHJvYmVaEwoBeBIOCgwIARIICgIIAQoCCARi" +
            "EwoBeRIOCgwIARIICgIIAQoCCARCBAoAEA0=",
    )
}

internal fun classifyQnnNpu(
    preferredAbi: String,
    qnnProviderAvailable: Boolean,
    htpBackendError: Throwable?,
): QnnNpuCapability = when {
    preferredAbi != "arm64-v8a" -> QnnNpuCapability(
        QnnNpuStatus.UNSUPPORTED_ABI,
        preferredAbi,
        "QNN HTP requires an ARM64 process; preferred ABI is ${preferredAbi.ifBlank { "unknown" }}.",
    )
    !qnnProviderAvailable -> QnnNpuCapability(
        QnnNpuStatus.RUNTIME_UNAVAILABLE,
        preferredAbi,
        "This ONNX Runtime binary does not expose the QNN execution provider.",
    )
    htpBackendError != null -> QnnNpuCapability(
        QnnNpuStatus.HTP_BACKEND_UNAVAILABLE,
        preferredAbi,
        "QNN could not initialize the HTP NPU: ${htpBackendError.conciseMessage()}",
    )
    else -> QnnNpuCapability(
        QnnNpuStatus.AVAILABLE,
        preferredAbi,
        "QNN execution provider and Qualcomm HTP backend are available.",
    )
}

private fun Throwable.conciseMessage(): String =
    message?.lineSequence()?.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
        ?: javaClass.simpleName
