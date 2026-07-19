package dev.sfg.orchard.connect

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale

const val DEVICE_TOKEN_KEY = "orchard-connect:device-token"
const val SERVER_HOST_KEY = "orchard-connect:server-host"
const val SERVER_URL_KEY = "orchard-connect:server-url"

data class PairingInput(val serverUrl: String = "", val token: String = "")

object ConnectDiscovery {
    private const val DISCOVERY_PORT = "32145"
    private const val DISCOVERY_TIMEOUT_MS = 1400
    private val random = SecureRandom()

    fun createDeviceToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun cleanServerUrl(value: String?): String {
        return try {
            val uri = URI(value.orEmpty().trim())
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return ""
            if (scheme != "http" && scheme != "https") return ""
            val host = uri.host ?: return ""
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) {
            ""
        }
    }

    fun serverHost(value: String?): String {
        return try {
            URI(value.orEmpty().trim()).host.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun parsePairingInput(value: String?): PairingInput {
        var text = value.orEmpty().trim()
        if (text.isEmpty()) return PairingInput()
        if (Regex("%[0-9a-f]{2}", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            try {
                text = URLDecoder.decode(text, Charsets.UTF_8.name())
            } catch (_: Exception) {
            }
        }

        try {
            val uri = URI(text)
            if (uri.scheme == "orchard-connect") {
                val params = queryParams(uri.rawQuery)
                return PairingInput(cleanServerUrl(params["server"]), params["token"].orEmpty())
            }
            return PairingInput(cleanServerUrl(text), queryParams(uri.rawQuery)["token"].orEmpty())
        } catch (_: Exception) {
            val params = queryParams(text.removePrefix("?").removePrefix("#"))
            val serverUrl = cleanServerUrl(params["server"])
            if (serverUrl.isNotEmpty() || params["token"].orEmpty().isNotEmpty()) {
                return PairingInput(serverUrl, params["token"].orEmpty())
            }
            return PairingInput(cleanServerUrl(text), "")
        }
    }

    fun discoverServerUrl(savedUrl: String, savedHost: String): String {
        val host = serverHost(savedUrl).ifEmpty { savedHost }
        val fallback = cleanServerUrl(savedUrl)
        if (host.isEmpty()) return fallback

        return try {
            val connection = URL("http://$host:$DISCOVERY_PORT/connect-info").openConnection() as HttpURLConnection
            connection.connectTimeout = DISCOVERY_TIMEOUT_MS
            connection.readTimeout = DISCOVERY_TIMEOUT_MS
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.inputStream.use { stream ->
                val text = stream.bufferedReader().readText()
                Regex("\"serverUrl\"\\s*:\\s*\"([^\"]+)\"")
                    .find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { cleanServerUrl(it.replace("\\/", "/")) }
                    ?.ifEmpty { fallback } ?: fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun queryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val index = part.indexOf("=")
            if (index < 0) return@mapNotNull null
            val key = part.substring(0, index).urlDecode()
            val value = part.substring(index + 1).urlDecode()
            key to value
        }.toMap()
    }

    private fun String.urlDecode(): String {
        return try {
            URLDecoder.decode(this, Charsets.UTF_8.name())
        } catch (_: Exception) {
            this
        }
    }
}
