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

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.sfg.orchard.mobile.model.CatalogJson
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.model.Track
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class RestoredPlayback(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val contextTitle: String = "",
    val playWhenReady: Boolean = false,
)

/** Versioned JSON boundary kept Android-free so restoration rules are unit-testable. */
internal object PlaybackStateCodec {
    fun decode(root: JSONObject): RestoredPlayback {
        val queue = CatalogJson.tracks(root.optJSONArray("queue"))
        return RestoredPlayback(
            queue = queue,
            currentIndex = root.optInt("currentIndex", -1).coerceIn(-1, queue.lastIndex),
            positionMs = root.optLong("positionMs").coerceAtLeast(0),
            shuffle = root.optBoolean("shuffle"),
            repeatMode = runCatching { RepeatMode.valueOf(root.optString("repeatMode")) }
                .getOrDefault(RepeatMode.OFF),
            contextTitle = root.optString("contextTitle"),
            // Never resume audible playback solely because Android recreated
            // the process; the user or a media controller explicitly resumes.
            playWhenReady = false,
        )
    }

    fun encode(state: RestoredPlayback): JSONObject = JSONObject()
        .put("version", 2)
        .put("queue", CatalogJson.tracks(state.queue))
        .put("currentIndex", state.currentIndex)
        .put("positionMs", state.positionMs.coerceAtLeast(0))
        .put("shuffle", state.shuffle)
        .put("repeatMode", state.repeatMode.name)
        .put("contextTitle", state.contextTitle)
}

/**
 * Atomically persists only durable playback identity and user intent.
 * Expiring provider URLs and transient failures never cross process restarts.
 */
class PlaybackStateStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "playback-state.json"))

    fun load(): RestoredPlayback {
        if (!file.baseFile.exists()) return RestoredPlayback()
        return try {
            val payload = file.openRead().bufferedReader().use { it.readText() }
            PlaybackStateCodec.decode(JSONObject(payload))
        } catch (error: Exception) {
            Log.w(TAG, "Ignoring unreadable playback state", error)
            RestoredPlayback()
        }
    }

    @Synchronized
    fun save(state: RestoredPlayback) {
        val root = PlaybackStateCodec.encode(state)
        var output: FileOutputStream? = file.startWrite()
        try {
            output?.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            output = null
        } catch (error: Exception) {
            output?.let(file::failWrite)
            Log.e(TAG, "Could not persist playback state", error)
        }
    }

    companion object {
        private const val TAG = "PlaybackStateStore"
    }
}
