/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

package dev.sfg.orchard.mobile.playback.smart.npu

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

enum class NpuVendor(val brand: String) { QUALCOMM("Snapdragon"), MEDIATEK("MediaTek") }

/** What detection found. [supported] means Orchard can run both models on it. */
data class NpuDevice(
    val vendor: NpuVendor,
    val soc: String,
    /** Hexagon generation ("v69") for Snapdragon; the add-on is built per generation. */
    val arch: String = "",
    val supported: Boolean,
    val reason: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("vendor", vendor.name).put("soc", soc).put("arch", arch)
        .put("supported", supported).put("reason", reason)

    companion object {
        fun fromJson(json: JSONObject): NpuDevice? = runCatching {
            NpuDevice(
                NpuVendor.valueOf(json.getString("vendor")), json.getString("soc"), json.optString("arch"),
                json.getBoolean("supported"), json.optString("reason"),
            )
        }.getOrNull()
    }
}

internal object NpuDetector {
    /** Hexagon generation per Snapdragon part number; one add-on APK serves each generation. */
    private val HEXAGON = mapOf(
        "SM8350" to "v68", "SM7325" to "v68", "SM7350" to "v68",
        "SM8450" to "v69", "SM8475" to "v69", "SM7450" to "v69", "SM7475" to "v69",
        "SM8550" to "v73", "SM7550" to "v73", "SM8635" to "v73", "SM7675" to "v73",
        "SM8650" to "v75",
        "SM8750" to "v79", "SM8735" to "v79",
        "SM8850" to "v81", "SM8845" to "v81",
    )

    /** Null when the SoC is neither vendor. Neuron detection loads the phone's adapter; run it off the main thread. */
    fun detect(context: Context, graphs: () -> Pair<File, File>): NpuDevice? {
        val maker = Build.SOC_MANUFACTURER.lowercase()
        val soc = Build.SOC_MODEL.uppercase()
        return when {
            maker.contains("qualcomm") || maker.contains("qti") || soc.startsWith("SM") -> snapdragon(soc)
            maker.contains("mediatek") || soc.startsWith("MT") -> mediatek(soc, graphs)
            else -> null
        }
    }

    private fun snapdragon(soc: String): NpuDevice {
        val arch = HEXAGON[soc.take(6)]
        return when {
            arch == null -> NpuDevice(NpuVendor.QUALCOMM, soc, "", false, "No Orchard NPU add-on for $soc")
            // The DSP RPC library the QNN stub talks through; absent on parts with the NPU locked away.
            !File("/vendor/lib64/libcdsprpc.so").isFile -> NpuDevice(NpuVendor.QUALCOMM, soc, arch, false, "No DSP RPC library")
            else -> NpuDevice(NpuVendor.QUALCOMM, soc, arch, true, "Hexagon ${arch.uppercase()}")
        }
    }

    private fun mediatek(soc: String, graphs: () -> Pair<File, File>): NpuDevice {
        if (!NpuNative.available) return NpuDevice(NpuVendor.MEDIATEK, soc, "", false, "NPU library unavailable")
        val (beat, vocal) = graphs()
        val result = JSONObject(NpuNative.nativeDetectNeuron(beat.absolutePath, vocal.absolutePath))
        val supported = result.optBoolean("detected") && result.optBoolean("supported")
        val reason = if (supported) "Neuron ${result.optString("version")} on ${result.optJSONArray("devices")?.optString(0)}"
            else result.optString("reason")
        return NpuDevice(NpuVendor.MEDIATEK, soc, "", supported, reason)
    }
}
