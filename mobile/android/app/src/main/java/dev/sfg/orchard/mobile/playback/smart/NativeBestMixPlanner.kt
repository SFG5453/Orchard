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

package dev.sfg.orchard.mobile.playback.smart

import android.util.Log
import dev.sfg.orchard.mobile.model.Track
import org.json.JSONArray
import org.json.JSONObject

/** Desktop Best Mix's pair score and three-finalist queue search, ported to native C++. */
internal object NativeBestMixPlanner {
    private const val TAG = "OrchardNativeBestMix"

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("orchard_resampler") }
            .onFailure { Log.w(TAG, "Native pair scorer unavailable", it) }
            .isSuccess
    }

    private external fun nativeInvoke(request: String): String

    fun score(analysis: JSONObject, nextAnalysis: JSONObject): JSONObject? {
        if (!available) return null
        return runCatching {
            JSONObject(nativeInvoke(JSONObject()
                .put("analysis", analysis)
                .put("nextAnalysis", nextAnalysis)
                .toString()))
        }.onFailure { Log.w(TAG, "Native pair scoring failed", it) }.getOrNull()
    }

    fun sort(
        tracks: List<Track>,
        featuresMap: Map<String, TrackFeatures.Features>,
        initialFeatures: TrackFeatures.Features?,
    ): List<Track>? {
        if (!available) return null
        return runCatching {
            val started = System.nanoTime()
            val analyses = JSONArray()
            tracks.forEach { track ->
                analyses.put(featuresMap[track.id]?.let(TrackFeatures::toJson) ?: JSONObject.NULL)
            }
            val initial = initialFeatures?.let(TrackFeatures::toJson) ?: JSONObject.NULL
            val response = JSONArray(nativeInvoke(JSONObject()
                .put("analyses", analyses)
                .put("initial", initial)
                .toString()))
            require(response.length() == tracks.size)
            val indices = List(response.length()) { response.getInt(it) }
            require(indices.toSet().size == tracks.size && indices.all { it in tracks.indices })
            indices.map(tracks::get).also {
                Log.d(TAG, "Sorted ${tracks.size} tracks with native desktop pair scores in " +
                    "${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }.onFailure { Log.w(TAG, "Native Best Mix ordering failed", it) }.getOrNull()
    }
}
