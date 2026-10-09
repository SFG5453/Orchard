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
import java.io.File

/**
 * Best Mix through the desktop worker's own Rust: `bestMixAnalyze` per song and `sort_lazy`
 * for the order, so a queue sorted here comes out as desktop sorts it.
 */
object BestMixSorter {
    private const val TAG = "OrchardBestMix"

    /** Desktop sorts the first 50 upcoming songs; the rest keep their places. */
    const val SNAPSHOT = 50

    /** Analyzes a downloaded song; stores and returns true when it worked. */
    fun analyze(store: BestMixFeatureStore, track: Track, file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val song = FileMediaDataSource(file).use { SongDecoder.decode(it) } ?: return false
        return song.use {
            val duration = (track.durationMs / 1000.0).takeIf { it >= 2 } ?: song.duration
            val result = JSONObject(MixNative.nativeBestMixAnalyze(song.handle, duration))
            if (result.has("error")) {
                Log.w(TAG, "Best Mix analysis of ${track.id} failed: ${result.optString("error")}")
                false
            } else store.store(track.id, duration, result)
        }
    }

    /**
     * Orders [queue] to follow [current], as desktop's `BestMixController::sort`. Null when no
     * queue song has analysis or the native sort refused.
     */
    fun sort(store: BestMixFeatureStore, queue: List<Track>, current: Track?): List<Track>? {
        if (queue.size <= 1 || !MixNative.available) return queue
        val snapshot = queue.take(SNAPSHOT)
        val summaries = snapshot.map { store.summary(it.id) }
        if (summaries.all { it.length() == 0 }) return null
        store.trim()
        val initial = current?.let { store.summary(it.id).optJSONObject("tail") } ?: JSONObject()
        val reply = JSONObject(MixNative.nativeBestMixSort(JSONArray(summaries).toString(), initial.toString()) { left, right ->
            val leftId = if (left == -1) current?.id else snapshot.getOrNull(left)?.id
            val rightId = snapshot.getOrNull(right)?.id
            val tail = leftId?.let { store.edge(it, tail = true) }
            val head = rightId?.let { store.edge(it, tail = false) }
            if (tail == null || head == null) JSONObject().put("error", "Feature cache entry is unavailable").toString()
            else JSONObject().put("left", tail).put("right", head).toString()
        })
        val order = reply.optJSONArray("order") ?: run {
            Log.w(TAG, "Best Mix could not order this queue: ${reply.optString("error")}")
            return null
        }
        val indices = List(order.length()) { order.getInt(it) }
        if (indices.size != snapshot.size || indices.toSet() != snapshot.indices.toSet()) return null
        return indices.map(snapshot::get) + queue.drop(SNAPSHOT)
    }
}
