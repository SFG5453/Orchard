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

package dev.sfg.orchard.mobile.scrobble

import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import kotlin.math.min

internal data class ScrobbleTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal sealed interface ScrobbleProgressEvent {
    data class NowPlaying(val track: ScrobbleTrack) : ScrobbleProgressEvent
    data class Completed(val track: ScrobbleTrack, val startedAtEpochSeconds: Long) : ScrobbleProgressEvent
}

/**
 * Measures audible forward progress rather than trusting the seek position. A listener cannot
 * earn a scrobble by seeking over the threshold, and time spent paused never counts.
 */
internal class ScrobbleProgress {
    private data class Active(
        val key: String,
        var track: ScrobbleTrack,
        val startedAtEpochSeconds: Long,
        var lastPositionMs: Long,
        var lastReportedAtMs: Long,
        var playedMs: Long = 0,
        var completed: Boolean = false,
    )

    private var active: Active? = null

    fun update(snapshot: PlaybackSnapshot, nowMs: Long = System.currentTimeMillis()): List<ScrobbleProgressEvent> {
        val track = snapshot.currentTrack?.toScrobbleTrack(snapshot.durationMs) ?: run {
            active = null
            return emptyList()
        }
        if (!snapshot.isPlaying) {
            active?.let {
                it.lastPositionMs = snapshot.positionMs.coerceAtLeast(0)
                it.lastReportedAtMs = nowMs
            }
            return emptyList()
        }

        val positionMs = snapshot.positionMs.coerceAtLeast(0)
        val key = listOf(track.id, track.artist, track.title).joinToString(":")
        val current = active
        val restarted = current?.key == key && positionMs < 2_000 && current.lastPositionMs > 5_000
        if (current == null || current.key != key || restarted) {
            active = Active(
                key = key,
                track = track,
                startedAtEpochSeconds = (nowMs / 1_000).coerceAtLeast(1),
                lastPositionMs = positionMs,
                lastReportedAtMs = nowMs,
            )
            return listOf(ScrobbleProgressEvent.NowPlaying(track))
        }

        current.track = track
        val positionDelta = positionMs - current.lastPositionMs
        val elapsed = (nowMs - current.lastReportedAtMs).coerceAtLeast(0)
        if (positionDelta > 0 && positionDelta <= elapsed + SEEK_TOLERANCE_MS) {
            current.playedMs += min(positionDelta, elapsed)
        }
        current.lastPositionMs = positionMs
        current.lastReportedAtMs = nowMs

        if (!current.completed && shouldScrobble(track.durationMs, current.playedMs)) {
            current.completed = true
            return listOf(ScrobbleProgressEvent.Completed(track, current.startedAtEpochSeconds))
        }
        return emptyList()
    }

    fun reset() {
        active = null
    }
}

internal fun shouldScrobble(durationMs: Long, playedMs: Long): Boolean =
    durationMs > MINIMUM_TRACK_MS && playedMs >= min(durationMs / 2, MAXIMUM_WAIT_MS)

private fun Track.toScrobbleTrack(durationFallbackMs: Long): ScrobbleTrack? {
    val cleanTitle = title.cleanScrobbleText()
    val cleanArtist = artist.cleanScrobbleText()
    if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null
    return ScrobbleTrack(
        id = id,
        title = cleanTitle,
        artist = cleanArtist,
        album = album.cleanScrobbleText(),
        durationMs = maxOf(durationMs, durationFallbackMs).coerceAtLeast(0),
    )
}

internal fun String.cleanScrobbleText(maxLength: Int = 500): String =
    trim().replace(Regex("\\s+"), " ").take(maxLength)

private const val MINIMUM_TRACK_MS = 30_000L
private const val MAXIMUM_WAIT_MS = 4 * 60_000L
private const val SEEK_TOLERANCE_MS = 2_000L
