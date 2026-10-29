/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

package dev.sfg.orchard.mobile.playback.smart.npu

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One entry of npu-addons.json, written by mobile/android/npu-addon/build.sh. */
internal data class AddonRelease(val url: String, val sha256: String, val size: Long, val versionCode: Int)

/** The installed add-on: its extracted libraries and its packaged QNN models. */
internal class InstalledAddon(val packageName: String, val versionCode: Long, val libDir: File, val context: Context)

/** Snapdragon add-on APKs: QNN, its HTP libraries for one Hexagon generation, and the QDQ models. */
internal object NpuAddon {
    private const val MANIFEST_URL = "https://depot.sfg545.dev/android/npu/npu-addons.json"
    /** Oldest add-on this build can drive; bump with npu-addon/build.sh's VERSION_CODE when the runner ABI changes. */
    const val MIN_VERSION_CODE = 1L
    /** Shown before the manifest answers: the V69 add-on, rounded up. */
    const val ESTIMATED_BYTES = 54L * 1024 * 1024

    fun packageName(arch: String) = "dev.sfg.orchard.npu.$arch"

    /** The add-on when it is installed, current, and signed with Orchard's own key. */
    fun installed(context: Context, arch: String): InstalledAddon? = runCatching {
        val name = packageName(arch)
        val pm = context.packageManager
        val info = pm.getPackageInfo(name, 0)
        // Anyone can claim the package name; only Orchard's signer may supply code we load.
        if (pm.checkSignatures(context.packageName, name) != PackageManager.SIGNATURE_MATCH) return null
        if (info.longVersionCode < MIN_VERSION_CODE) return null
        val app = info.applicationInfo ?: return null
        InstalledAddon(name, info.longVersionCode, File(app.nativeLibraryDir), context.createPackageContext(name, 0))
    }.getOrNull()

    fun release(arch: String): AddonRelease? = runCatching {
        val connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val entry = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONObject(arch) ?: return null
        AddonRelease(entry.getString("url"), entry.optString("sha256"), entry.optLong("size"), entry.optInt("versionCode"))
    }.getOrNull()

    /** Downloads and hands the APK to the system installer; [onResult] gets null or a failure reason. */
    fun download(context: Context, arch: String, release: AddonRelease, onResult: (String?) -> Unit) {
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "orchard-npu-$arch.apk")
        destination.delete()
        val request = DownloadManager.Request(release.url.toUri())
            .setTitle("Orchard NPU support")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(destination))
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                context.unregisterReceiver(this)
                val status = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
                }
                val failure = when {
                    status != DownloadManager.STATUS_SUCCESSFUL -> "The download did not complete."
                    release.sha256.isNotBlank() && !sha256(destination).equals(release.sha256, ignoreCase = true) ->
                        "The download did not match its checksum."
                    else -> null
                }
                if (failure != null) destination.delete() else install(context, destination)
                onResult(failure)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED)
    }

    /** Calls [onChange] whenever an add-on package is installed, replaced or removed. */
    fun watch(context: Context, onChange: () -> Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart?.startsWith("dev.sfg.orchard.npu.") == true) onChange()
            }
        }, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
