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
import androidx.media3.common.Player
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.youtube.providerJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom

/** Reports local YouTube playback through the shared provider's authenticated stats endpoint. */
internal class YouTubeHistoryReporter(private val graph: OrchardGraph, private val scope: CoroutineScope) {
    private var trackId = ""
    private var cpn = ""
    private var tracking: PlaybackTracking? = null
    private var positionMs = 0L
    private var reportedMs = 0L
    private var previousRequest: Job? = null
    private var lookupTrackId = ""
    private val random = SecureRandom()

    fun update(player: Player) {
        val item = player.currentMediaItem
        val currentId = item?.mediaId.orEmpty()
        val local = item?.let(MediaItemMapper::toTrack)?.isLocal == true
        val eligible = player.isPlaying && !local && !graph.activeTrackIsQobuz.value &&
            graph.settings.settings.value.sendYouTubeHistory && graph.networkMonitor.isOnline.value &&
            graph.auth.session() != null
        if (currentId == trackId) positionMs = player.currentPosition.coerceAtLeast(0)
        if (currentId != trackId || !eligible) finish()
        if (!eligible || currentId.isBlank()) return

        val currentPosition = player.currentPosition.coerceAtLeast(0)
        if (cpn.isEmpty()) {
            val source = graph.streams.trackingFor(currentId)
            if (source == null) {
                if (lookupTrackId != currentId) {
                    lookupTrackId = currentId
                    val track = item?.let(MediaItemMapper::toTrack) ?: return
                    scope.launch {
                        runCatching { graph.streams.loadHistoryTracking(track) }
                            .onFailure { Log.w(TAG, "YouTube history tracking lookup failed", it) }
                        withContext(Dispatchers.Main.immediate) { update(player) }
                    }
                }
                return
            }
            tracking = source
            trackId = currentId
            cpn = buildString(16) { repeat(16) { append(CPN_ALPHABET[random.nextInt(CPN_ALPHABET.length)]) } }
            positionMs = currentPosition
            reportedMs = currentPosition
            send("history.start", currentPosition, final = false)
            return
        }
        positionMs = currentPosition
        if (currentPosition >= reportedMs + REPORT_INTERVAL_MS || currentPosition < reportedMs) {
            reportedMs = currentPosition
            send("history.update", currentPosition, final = false)
        }
    }

    fun finish() {
        if (cpn.isNotEmpty()) send("history.update", positionMs, final = true)
        trackId = ""
        cpn = ""
        tracking = null
        positionMs = 0L
        reportedMs = 0L
        lookupTrackId = ""
    }

    private fun send(method: String, watchTimeMs: Long, final: Boolean) {
        val source = tracking ?: return
        val session = graph.auth.session() ?: return
        val payload = JSONObject()
            .put("session", session.providerJson())
            .put("tracking", JSONObject()
                .put("playbackUrl", source.playbackUrl)
                .put("watchtimeUrl", source.watchtimeUrl)
                .put("itag", source.itag))
            .put("cpn", cpn)
            .put("watchTime", watchTimeMs / 1_000.0)
            .put("final", final)
        val prior = previousRequest
        previousRequest = scope.launch {
            prior?.join()
            runCatching { graph.youtube.invoke(method, payload) }
                .onFailure { Log.w(TAG, "YouTube history request failed", it) }
        }
    }

    private companion object {
        const val TAG = "YouTubeHistoryReporter"
        const val REPORT_INTERVAL_MS = 30_000L
        const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}
