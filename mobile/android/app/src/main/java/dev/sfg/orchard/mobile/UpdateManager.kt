package dev.sfg.orchard.mobile

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import dev.sfg.orchard.connect.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

data class MobileUpdateMetadata(
    val version: String,
    val codename: String = "",
    val versionCode: Int = 0,
    val apkUrl: String = "",
    val sha256: String = "",
    val publishedAt: String = "",
    val releaseNotes: String = "",
)

/** What the update flow is currently doing, for the in-app prompt to render. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val metadata: MobileUpdateMetadata) : UpdateState
    data class Downloading(val version: String) : UpdateState
    data class Failed(val version: String, val reason: String) : UpdateState
    data class ReadyToInstall(val version: String) : UpdateState
}

class UpdateManager(private val context: Context) {

    private val baseUrl = "https://downloads.sfg545.dev/orchard"
    private val jsonUrl = "$baseUrl/latest-android.json"

    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    fun checkForUpdates() {
        // Debug builds carry a "-debug" versionNameSuffix and a different applicationId; the
        // published release APK is neither an upgrade nor installable over them.
        if (BuildConfig.DEBUG) return

        thread {
            try {
                val connection = URL(jsonUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val metadata = parseUpdateMetadata(response)

                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersion = packageInfo.versionName

                    if (compareVersions(metadata.version, currentVersion) > 0) {
                        // Surface it and let the user decide; downloading and firing the system
                        // installer unprompted is what made this feel like a random intrusion.
                        mutableState.value = UpdateState.Available(metadata)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
            }
        }
    }

    fun dismiss() {
        mutableState.value = UpdateState.Idle
    }

    companion object {
        private const val TAG = "UpdateManager"

        fun parseUpdateMetadata(jsonString: String): MobileUpdateMetadata {
            val json = JSONObject(jsonString)
            return MobileUpdateMetadata(
                version = json.getString("version"),
                codename = json.optString("codename", ""),
                versionCode = json.optInt("versionCode", 0),
                apkUrl = json.optString("apkUrl", ""),
                sha256 = json.optString("sha256", ""),
                publishedAt = json.optString("publishedAt", ""),
                releaseNotes = json.optString("releaseNotes", ""),
            )
        }

        fun compareVersions(latest: String, current: String?): Int {
            if (current == null) return 1
            // Version names carry build suffixes ("1.1.1-debug", "1.2.0-rc1"). Comparing the raw
            // segment made "1-debug" parse as 0, so every suffixed build looked out of date.
            fun parts(value: String) = value.trim().split(".")
                .map { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

            val lParts = parts(latest)
            val cParts = parts(current)

            val length = maxOf(lParts.size, cParts.size)
            for (i in 0 until length) {
                val l = lParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (l > c) return 1
                if (l < c) return -1
            }
            return 0
        }
    }

    fun downloadAndInstallUpdate(metadata: MobileUpdateMetadata) {
        val version = metadata.version
        // The published asset is not always named the way we would guess (it is "Orchard-x.y.z.apk",
        // not "orchard-x.y.z.apk"), so honour the URL the manifest gives us. Guessing produced a 404
        // whose HTML body got saved as the .apk, which is why installs failed to parse.
        val apkUrl = metadata.apkUrl.ifBlank { "$baseUrl/Orchard-$version.apk" }
        val fileName = "Orchard-$version.apk"
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        if (destination.exists()) destination.delete()

        mutableState.value = UpdateState.Downloading(version)

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Orchard Update")
            .setDescription("Downloading version $version")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                context.unregisterReceiver(this)

                // ACTION_DOWNLOAD_COMPLETE fires for failures too. Handing a failed download to
                // the installer is what produced "problem parsing the package".
                val status = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.statusOf() else null }

                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Log.w(TAG, "Update download failed for $version (status=$status)")
                    destination.delete()
                    mutableState.value = UpdateState.Failed(version, "The download did not complete.")
                    return
                }

                if (metadata.sha256.isNotBlank()) {
                    val actual = sha256Of(destination)
                    if (!actual.equals(metadata.sha256, ignoreCase = true)) {
                        Log.w(TAG, "Update checksum mismatch for $version: expected ${metadata.sha256}, got $actual")
                        destination.delete()
                        mutableState.value =
                            UpdateState.Failed(version, "The downloaded file did not match its checksum.")
                        return
                    }
                }

                mutableState.value = UpdateState.ReadyToInstall(version)
                installApk(destination)
            }
        }

        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
    }

    private fun Cursor.statusOf(): Int =
        getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

    private fun sha256Of(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
