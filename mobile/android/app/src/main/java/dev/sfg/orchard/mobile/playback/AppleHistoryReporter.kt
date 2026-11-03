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

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.catalog.isAppleMusicId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Reports Apple Music songs played here so they reach the listener's Recently Played. */
internal class AppleHistoryReporter(private val graph: OrchardGraph, private val scope: CoroutineScope) {
    private var songId = ""
    private var persistentId = ""
    private var startMs = 0L
    private var positionMs = 0L
    private var durationMs = 0L
    private var shuffle = false
    private var previousRequest: Job? = null

    fun update(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId.orEmpty()
        val id = if (isAppleMusicId(mediaId)) mediaId.removePrefix(SONG_PREFIX) else ""
        // Gapless transitions swap the item without stopping, so close the old play here.
        if (id != songId) finish()
        if (id.isEmpty() || !id.all(Char::isDigit)) return
        val position = player.currentPosition.coerceAtLeast(0)
        if (songId.isEmpty()) {
            if (!player.isPlaying || position <= 0 || !graph.settings.settings.value.sendAppleMusicHistory ||
                !graph.networkMonitor.isOnline.value || !graph.appleMusic.signedIn()) return
            songId = id
            persistentId = UUID.randomUUID().toString()
            startMs = position
            positionMs = position
            durationMs = duration(player)
            shuffle = player.shuffleModeEnabled
            send("history.start", end = false, natural = false)
            return
        }
        positionMs = maxOf(positionMs, position)
        durationMs = duration(player).takeIf { it > 0 } ?: durationMs
    }

    fun finish() {
        if (songId.isEmpty()) return
        val natural = durationMs > 0 && positionMs >= durationMs - NATURAL_END_MS
        send("history.end", end = true, natural = natural)
        songId = ""
        positionMs = 0L
        durationMs = 0L
    }

    private fun duration(player: Player) = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L

    private fun send(method: String, end: Boolean, natural: Boolean) {
        val payload = JSONObject()
            .put("id", songId)
            .put("persistentId", persistentId)
            .put("durationMs", durationMs)
            .put("startPositionMs", startMs)
            .put("shuffle", shuffle)
        if (end) {
            payload.put("endPositionMs", if (natural) durationMs else positionMs)
                .put("reason", if (natural) "natural" else "skipped")
        }
        val prior = previousRequest
        previousRequest = scope.launch {
            prior?.join()
            runCatching { graph.appleMusic.reportPlay(method, payload) }
                .onFailure { Log.w(TAG, "Apple Music history request failed", it) }
        }
    }

    private companion object {
        const val TAG = "AppleHistoryReporter"
        const val SONG_PREFIX = "am.songs."
        const val NATURAL_END_MS = 3_000L
    }
}
