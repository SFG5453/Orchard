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

/** Validates and normalizes captured web-session cookies. The provider signs requests. */
object YouTubeSessionAuth {
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

    /**
     * The delegated (brand account) id from a page's `DATASYNC_ID`, which reads
     * `delegatedId||userId`. A personal account reads `userId||` and has no delegation, so it
     * maps to blank. Same rule as desktop's AuthManager::delegatedSessionIdFromPageAuth.
     */
    fun normalizeDataSyncId(value: String?): String {
        val decoded = value.orEmpty().trim().decodePercentEscapes()
        val separator = decoded.indexOf("||")
        if (separator <= 0 || decoded.substring(separator + 2).isBlank()) return ""
        return decoded.substring(0, separator)
    }

    fun delegatedId(dataSyncId: String?, delegatedSessionId: String?): String =
        delegatedSessionId.orEmpty().trim().takeIf(String::isNotEmpty)
            ?: normalizeDataSyncId(dataSyncId)

    fun sameAccount(first: YouTubeSession, second: YouTubeSession): Boolean =
        first.dataSyncId == second.dataSyncId &&
            first.accountIndex == second.accountIndex &&
            loginCookieValue(first.cookie) == loginCookieValue(second.cookie)

    fun selectedDifferentAccount(
        initial: YouTubeSession?,
        baseline: YouTubeSession?,
        candidate: YouTubeSession,
    ): Boolean = baseline != null && !sameAccount(baseline, candidate) &&
        (initial == null || !sameAccount(initial, candidate))

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
