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

package dev.sfg.orchard.mobile.qobuz

import android.content.Context
import dev.sfg.orchard.mobile.model.StreamDetail

/** Remembers what each song streamed from Qobuz as, so a cache-served resume still labels it. */
class QobuzTierMemory(context: Context) {
    private val prefs = context.getSharedPreferences("qobuz_stream_details", Context.MODE_PRIVATE)

    /** Null when the song has not streamed from Qobuz. */
    fun detail(videoId: String, variant: String): StreamDetail? {
        val parts = prefs.getString(key(videoId, variant), null)?.split(',') ?: return null
        if (parts.size != 3) return null
        return StreamDetail(parts[0] == "1", parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0, "FLAC")
    }

    fun record(videoId: String, variant: String, detail: StreamDetail) {
        if (prefs.all.size >= MAX_ENTRIES) prefs.edit().clear().apply()
        prefs.edit().putString(key(videoId, variant), "${if (detail.hiRes) 1 else 0},${detail.bitDepth},${detail.sampleRate}").apply()
    }

    fun clear(videoId: String, variant: String) {
        prefs.edit().remove(key(videoId, variant)).apply()
    }

    private fun key(videoId: String, variant: String) = "$variant|$videoId"

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
