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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.support

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal class SupportHttpException(val status: Int, val code: String, message: String) : Exception(message)

/** Form fields plus at most one file; enough for a bug report. */
internal class MultipartForm {
    val boundary = "orchard-${UUID.randomUUID()}"
    private val out = ByteArrayOutputStream()

    fun field(name: String, value: String) = apply {
        head("Content-Disposition: form-data; name=\"$name\"")
        out.write(value.toByteArray())
        out.write(CRLF)
    }

    fun file(name: String, filename: String, type: String, bytes: ByteArray) = apply {
        head("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: $type")
        out.write(bytes)
        out.write(CRLF)
    }

    fun build(): ByteArray {
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    private fun head(headers: String) {
        out.write("--$boundary\r\n$headers\r\n\r\n".toByteArray())
    }

    private companion object {
        val CRLF = "\r\n".toByteArray()
    }
}

/** Blocking request against the account service; call it off the main thread. */
internal fun supportRequest(
    serviceUrl: String,
    method: String,
    path: String,
    token: String,
    body: ByteArray? = null,
    contentType: String = "application/json",
): JSONObject {
    val connection = (URL("$serviceUrl$path").openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        // Uploads with a screenshot on a slow phone network take a while.
        readTimeout = 45_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            setRequestProperty("Content-Type", contentType)
            setFixedLengthStreamingMode(body.size)
            doOutput = true
        }
    }
    return try {
        if (body != null) connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrDefault(JSONObject())
        if (status !in 200..299) throw SupportHttpException(
            status, json.optString("error"), json.optString("error_description", "Account service returned HTTP $status"),
        )
        json
    } finally {
        connection.disconnect()
    }
}
