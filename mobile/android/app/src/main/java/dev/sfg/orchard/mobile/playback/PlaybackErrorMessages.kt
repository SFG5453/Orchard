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

package dev.sfg.orchard.mobile.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/**
 * Turns a playback failure into something worth showing someone.
 *
 * The player wraps every resolver failure in a generic source error, so the useful
 * part is always somewhere down the cause chain. The chain is walked outermost first
 * so the most specific explanation wins over the retry and backoff notes stacked on
 * top of it.
 */
internal fun playbackErrorMessage(error: Throwable): String {
    var current: Throwable? = error
    while (current != null) {
        val msg = current.message.orEmpty()
        if (msg.startsWith(UNPLAYABLE_PREFIX)) {
            val reason = msg.removePrefix(UNPLAYABLE_PREFIX).trim()
            return if (reason.isNotBlank()) reason else "This track is unavailable."
        }
        if (msg.contains("inappropriate for some users", ignoreCase = true)) {
            return "This video may be inappropriate for some users."
        }
        if (msg.contains("confirm your age", ignoreCase = true)) {
            return "This track requires age verification on YouTube."
        }
        if (msg.contains("age-restricted", ignoreCase = true)) {
            return "This track is age-restricted on YouTube."
        }
        if (msg.contains("not a bot", ignoreCase = true)) {
            return "YouTube bot check: sign in to play this track."
        }
        if (msg.contains("refused this track", ignoreCase = true)) {
            return "YouTube refused this track."
        }
        if (
            current is java.net.UnknownHostException ||
                current is java.net.SocketTimeoutException
        ) {
            return "Network error: check your connection."
        }
        current = current.cause
    }
    val fallback = error.cause?.message ?: error.message
    return fallback?.takeIf { it.isNotBlank() } ?: "Playback failed."
}

/** Finds the CDN response code Media3 nests below its top-level playback exception. */
@UnstableApi
internal fun playbackHttpResponseCode(error: Throwable): Int? =
    findCause(error, HttpDataSource.InvalidResponseCodeException::class.java)?.responseCode

/** Cause chains are routinely two or three wrappers deep in Media3 source failures. */
internal fun <T : Throwable> findCause(error: Throwable, type: Class<T>): T? {
    var current: Throwable? = error
    while (current != null) {
        if (type.isInstance(current)) return type.cast(current)
        current = current.cause
    }
    return null
}

private const val UNPLAYABLE_PREFIX = "YouTube could not play this track: "
