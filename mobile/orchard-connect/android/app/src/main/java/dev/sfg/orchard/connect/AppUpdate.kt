package dev.sfg.orchard.connect

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val UPDATE_MANIFEST_URL = "https://downloads.sfg545.dev/orchard/latest-android.json"

data class UpdateInfo(val status: String = "checking", val update: JSONObject? = null)

object AppUpdate {
    fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun installedVersionName(context: Context): String {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
    }

    fun check(context: Context): UpdateInfo {
        return try {
            val installed = installedVersionCode(context)
            val connection = URL("$UPDATE_MANIFEST_URL?installed=$installed").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val available = json.optLong("versionCode") > installed && json.optString("apkUrl").startsWith("https://")
            UpdateInfo(if (available) "available" else "current", if (available) json else null)
        } catch (_: Exception) {
            UpdateInfo("error", null)
        }
    }

    fun openDownload(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
