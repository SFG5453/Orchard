package dev.sfg.orchard.mobile

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

class UpdateManager(private val context: Context) {

    private val baseUrl = "https://downloads.sfg545.dev/orchard"
    private val jsonUrl = "$baseUrl/latest-android.json"

    fun checkForUpdates() {
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
                        downloadAndInstallUpdate(metadata.version)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
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
            val lParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val cParts = current.split(".").map { it.toIntOrNull() ?: 0 }

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

    private fun downloadAndInstallUpdate(version: String) {
        val apkUrl = "$baseUrl/orchard-$version.apk"
        val fileName = "orchard-$version.apk"
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        if (destination.exists()) destination.delete()

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
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    installApk(destination)
                }
            }
        }
        
        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
    }

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
