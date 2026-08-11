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

package dev.sfg.orchard.mobile.auth

import java.security.MessageDigest

/** Builds the signed web-session authorization used by native InnerTube requests. */
object YouTubeSessionAuth {
    const val MUSIC_ORIGIN = "https://music.youtube.com"

    fun loginCookieValue(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        val cookies = cookieHeader.split(';').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            part.substring(0, separator).trim() to part.substring(separator + 1).trim()
        }.toMap()
        return LOGIN_COOKIE_NAMES.firstNotNullOfOrNull { name ->
            cookies[name]?.takeIf(String::isNotBlank)
        }
    }

    fun authorization(
        cookieHeader: String,
        origin: String = MUSIC_ORIGIN,
        epochSeconds: Long = System.currentTimeMillis() / 1_000,
    ): String? {
        val cookies = parseCookies(cookieHeader)
        val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: cookies["APISID"]
        val signedCookies = listOfNotNull(
            sapisid?.let { "SAPISIDHASH" to it },
            cookies["__Secure-1PAPISID"]?.let { "SAPISID1PHASH" to it },
            cookies["__Secure-3PAPISID"]?.let { "SAPISID3PHASH" to it },
        )
        if (signedCookies.isEmpty()) return null
        return signedCookies.joinToString(" ") { (scheme, value) ->
            val source = "$epochSeconds $value $origin"
            val digest = MessageDigest.getInstance("SHA-1").digest(source.toByteArray(Charsets.UTF_8))
            val hash = digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            "$scheme ${epochSeconds}_$hash"
        }
    }

    private fun parseCookies(cookieHeader: String): Map<String, String> =
        cookieHeader.split(';').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            part.substring(0, separator).trim() to part.substring(separator + 1).trim()
        }.toMap()

    fun normalizeDataSyncId(value: String?): String {
        val decoded = value.orEmpty().trim().decodePercentEscapes()
        if (decoded.isBlank() || decoded.equals("null", ignoreCase = true)) return ""
        if (!decoded.contains("||")) return decoded
        return if (decoded.endsWith("||")) decoded.substringBefore("||") else decoded.substringAfter("||")
    }

    private fun String.decodePercentEscapes(): String {
        if ('%' !in this) return this
        val output = StringBuilder(length)
        var index = 0
        while (index < length) {
            if (this[index] == '%' && index + 2 < length) {
                val high = Character.digit(this[index + 1], 16)
                val low = Character.digit(this[index + 2], 16)
                if (high >= 0 && low >= 0) {
                    output.append(((high shl 4) + low).toChar())
                    index += 3
                    continue
                }
            }
            output.append(this[index++])
        }
        return output.toString()
    }

    private val LOGIN_COOKIE_NAMES = listOf(
        "SAPISID",
        "__Secure-3PAPISID",
        "__Secure-1PAPISID",
        "APISID",
    )
}
