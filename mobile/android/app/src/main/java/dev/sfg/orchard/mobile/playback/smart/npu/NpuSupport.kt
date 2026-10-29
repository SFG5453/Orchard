/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

package dev.sfg.orchard.mobile.playback.smart.npu

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Whether adaptive mix runs Nirovi and Syvenu on the phone's NPU. Detection runs once per OS
 * build; compiled models are cached per device, app version and add-on version.
 */
internal object NpuSupport {
    private const val TAG = "OrchardNpu"

    data class State(
        /** Null until detection finishes, and on phones with neither vendor's NPU. */
        val device: NpuDevice? = null,
        /** Snapdragon: the add-on is installed. MediaTek: the user turned it on once. Shows "NPU Support". */
        val ready: Boolean = false,
        val enabled: Boolean = false,
        val promptSeen: Boolean = false,
        val downloading: Boolean = false,
        val compiling: Boolean = false,
        val downloadBytes: Long = NpuAddon.ESTIMATED_BYTES,
        val error: String? = null,
    ) {
        val supported get() = device?.supported == true
    }

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val openLock = Any()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("npu", Context.MODE_PRIVATE)
        mutable.update {
            it.copy(enabled = prefs.getBoolean(ENABLED, false), promptSeen = prefs.getBoolean(PROMPT_SEEN, false))
        }
        NpuAddon.watch(app) { thread(name = "orchard-npu") { onAddonChanged() } }
        thread(name = "orchard-npu") {
            mutable.update { it.copy(device = detect()) }
            refreshReady()
            val s = state.value
            if (s.enabled && s.ready && s.supported && !cacheReady()) prepare()
        }
    }

    /** "WOAH THERE" accepted: MediaTek turns on now; Snapdragon downloads its add-on first. */
    fun accept() {
        val device = state.value.device ?: return
        prefs.edit().putBoolean(PROMPT_SEEN, true).apply()
        mutable.update { it.copy(promptSeen = true, error = null) }
        if (device.vendor == NpuVendor.MEDIATEK) {
            prefs.edit().putBoolean(MEDIATEK_ON, true).apply()
            setEnabled(true)
            refreshReady()
            return
        }
        prefs.edit().putBoolean(ADDON_REQUESTED, true).apply()
        mutable.update { it.copy(downloading = true) }
        thread(name = "orchard-npu") {
            val release = NpuAddon.release(device.arch)
            if (release == null) {
                mutable.update { it.copy(downloading = false, error = "NPU support is not available to download right now.") }
                return@thread
            }
            NpuAddon.download(app, device.arch, release) { failure ->
                mutable.update { it.copy(downloading = false, error = failure) }
            }
        }
    }

    fun decline() {
        prefs.edit().putBoolean(PROMPT_SEEN, true).apply()
        mutable.update { it.copy(promptSeen = true) }
    }

    /** Sizes the download prompt from the published manifest. */
    fun fetchDownloadSize() {
        val device = state.value.device?.takeIf { it.vendor == NpuVendor.QUALCOMM } ?: return
        thread(name = "orchard-npu") {
            NpuAddon.release(device.arch)?.size?.takeIf { it > 0 }?.let { size -> mutable.update { it.copy(downloadBytes = size) } }
        }
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(ENABLED, enabled).apply()
        mutable.update { it.copy(enabled = enabled, error = if (enabled) null else it.error) }
        if (enabled) thread(name = "orchard-npu") { prepare() }
    }

    fun dismissError() = mutable.update { it.copy(error = null) }

    /** An open backend and the vtable for `MixNative.nativeModelsLoad`, or null to stay on the CPU. */
    fun openForMix(): Pair<Long, Long>? {
        val s = state.value
        if (!::app.isInitialized || !s.enabled || !s.ready || !s.supported) return null
        val handle = synchronized(openLock) { open() }
        return if (handle == 0L) null else handle to NpuNative.nativeVtable()
    }

    private fun onAddonChanged() {
        refreshReady()
        val s = state.value
        if (s.ready && prefs.getBoolean(ADDON_REQUESTED, false)) {
            prefs.edit().putBoolean(ADDON_REQUESTED, false).apply()
            setEnabled(true)
        }
    }

    private fun refreshReady() {
        val device = state.value.device
        val ready = when {
            device == null || !device.supported -> false
            device.vendor == NpuVendor.MEDIATEK -> prefs.getBoolean(MEDIATEK_ON, false)
            else -> NpuAddon.installed(app, device.arch) != null
        }
        mutable.update { it.copy(ready = ready) }
    }

    /** Compiles if needed and checks both models once; turns NPU support off when that fails. */
    private fun prepare() {
        val handle = synchronized(openLock) { open() }
        if (handle == 0L) {
            prefs.edit().putBoolean(ENABLED, false).apply()
            mutable.update { it.copy(enabled = false) }
            return
        }
        NpuNative.nativeClose(handle)
    }

    private fun open(): Long {
        val device = state.value.device ?: return 0L
        if (!NpuNative.available) return 0L
        val cache = cacheDir(device) ?: return 0L
        val marker = File(cache, "ready")
        val compile = !marker.isFile
        if (compile) mutable.update { it.copy(compiling = true) }
        try {
            val handle = when (device.vendor) {
                NpuVendor.MEDIATEK -> {
                    val (beat, vocal) = graphs()
                    NpuNative.nativeOpenNeuron(beat.absolutePath, vocal.absolutePath, cache.absolutePath)
                }
                NpuVendor.QUALCOMM -> {
                    val addon = NpuAddon.installed(app, device.arch) ?: return 0L
                    val models = File(app.noBackupFilesDir, "npu/qnn-models").apply { mkdirs() }
                    // Only a compile reads the QDQ models; the cached contexts replace them.
                    val names = listOf("nirovi_qnn.onnx", "syvenu_qnn.onnx")
                    if (compile) names.forEach { name ->
                        addon.context.assets.open(name).use { input -> File(models, name).outputStream().use { input.copyTo(it) } }
                    }
                    NpuNative.nativeOpenQnn(addon.libDir.absolutePath, File(models, names[0]).absolutePath,
                        File(models, names[1]).absolutePath, cache.absolutePath)
                        .also { models.deleteRecursively() }
                }
            }
            if (handle == 0L) return fail(NpuNative.nativeLastError(), cache)
            if (compile) {
                val test = NpuNative.nativeSelfTest(handle)
                if (test.isNotEmpty()) {
                    NpuNative.nativeClose(handle)
                    return fail(test, cache)
                }
                marker.writeText(device.reason)
            }
            return handle
        } catch (error: Throwable) {
            return fail(error.message ?: error.toString(), cache)
        } finally {
            if (compile) mutable.update { it.copy(compiling = false) }
        }
    }

    private fun fail(reason: String, cache: File): Long {
        Log.w(TAG, "NPU unavailable: $reason")
        cache.deleteRecursively()
        mutable.update { it.copy(error = "Your NPU could not run Adaptive Mix's models, so it stays on the CPU.") }
        return 0L
    }

    private fun cacheReady(): Boolean = state.value.device?.let { cacheDir(it) }?.let { File(it, "ready").isFile } == true

    /** One directory per device build, app build and add-on build; older ones are removed. */
    private fun cacheDir(device: NpuDevice): File? {
        val addon = if (device.vendor == NpuVendor.QUALCOMM) {
            NpuAddon.installed(app, device.arch)?.versionCode ?: return null
        } else 0L
        val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
        val key = "${device.vendor.name.lowercase()}-$version-$addon-${Integer.toHexString(Build.FINGERPRINT.hashCode())}"
        val root = File(app.noBackupFilesDir, "npu/cache")
        root.listFiles()?.filter { it.name != key }?.forEach { it.deleteRecursively() }
        return File(root, key).apply { mkdirs() }
    }

    private fun detect(): NpuDevice? {
        val key = "${Build.FINGERPRINT}|${app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode}"
        if (prefs.getString(DETECTION_KEY, null) == key) {
            return prefs.getString(DETECTION, null)?.let { NpuDevice.fromJson(JSONObject(it)) }
        }
        val device = runCatching { NpuDetector.detect(app, ::graphs) }
            .onFailure { Log.w(TAG, "NPU detection failed", it) }.getOrNull()
        prefs.edit().putString(DETECTION_KEY, key).putString(DETECTION, device?.toJson()?.toString()).apply()
        Log.i(TAG, "Detected ${device?.toJson() ?: "no NPU"}")
        return device
    }

    /** Neuron graphs extracted from assets; the code cache is cleared on app upgrades. */
    @Synchronized
    private fun graphs(): Pair<File, File> {
        fun extract(name: String): File {
            val file = AtomicFile(File(app.codeCacheDir, "npu/$name"))
            if (!file.baseFile.isFile) {
                file.baseFile.parentFile?.mkdirs()
                val output = file.startWrite()
                try {
                    app.assets.open("npu/$name").use { it.copyTo(output) }
                    file.finishWrite(output)
                } catch (error: Throwable) {
                    file.failWrite(output)
                    throw error
                }
            }
            return file.baseFile
        }
        return extract("nirovi.npug") to extract("syvenu.npug")
    }

    private const val ENABLED = "enabled"
    private const val PROMPT_SEEN = "prompt_seen"
    private const val MEDIATEK_ON = "mediatek_on"
    private const val ADDON_REQUESTED = "addon_requested"
    private const val DETECTION = "detection"
    private const val DETECTION_KEY = "detection_key"
}
