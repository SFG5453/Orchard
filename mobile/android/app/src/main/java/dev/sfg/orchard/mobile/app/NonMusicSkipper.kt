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

package dev.sfg.orchard.mobile.app

import android.util.Log
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.local.isLocalTrackId
import dev.sfg.orchard.mobile.model.NonMusicSegment
import dev.sfg.orchard.mobile.model.NonMusicSkipMode
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import dev.sfg.orchard.mobile.playback.PlaybackSource
import dev.sfg.orchard.mobile.social.PartyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Skips the non-music parts (talking intros, skits, applause) that SponsorBlock volunteers marked,
 * the same way desktop does, through the provider's `sponsorblock.segments`.
 *
 * A skip is a plain seek, so synced lyrics need no correction: they follow the position on the
 * same clock the segments were measured against. That only holds while the segments really belong
 * to the stream being played, which is why they are looked up by the stream's own video and
 * length, and why anything on another timeline (music video, Qobuz, a rendered mix, a remote
 * device) is left alone.
 */
internal class NonMusicSkipper(
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val local: LocalPlaybackController,
    private val playback: StateFlow<PlaybackSnapshot>,
    private val targets: StateFlow<PlaybackTargetState>,
    private val party: StateFlow<PartyState>,
) {
    private data class Segments(val trackId: String, val spans: List<NonMusicSegment>)

    private val mode = graph.settings.settings.map { it.nonMusicSkip }.distinctUntilChanged()
    private val segments = MutableStateFlow<Segments?>(null)

    /** Ids already auto-skipped on this play, so seeking back into one on purpose is respected. */
    private val skipped = mutableSetOf<String>()
    private var skippedTrackId = ""

    /** The span under the playhead, only while skipping makes sense on this player's own clock. */
    private val activeSpan: StateFlow<NonMusicSegment?> =
        combine(playback, segments, targets, party) { snapshot, found, target, partyState ->
            val track = snapshot.currentTrack
            when {
                track == null || found == null || found.trackId != track.id -> null
                target.selected !is PlaybackTarget.LocalPhone || partyState.isActive -> null
                snapshot.playingVideo || snapshot.renderedMixPositionMs != null -> null
                track.isQobuz || graph.activeTrackIsQobuz.value -> null
                else -> found.spans.firstOrNull {
                    // The last half second is not worth interrupting for.
                    snapshot.positionMs >= it.startMs && snapshot.positionMs < it.endMs - END_GRACE_MS
                }
            }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /** The span under the playhead while the setting asks for a button; null otherwise. */
    val offered: StateFlow<NonMusicSegment?> = combine(activeSpan, mode) { span, choice ->
        span.takeIf { choice == NonMusicSkipMode.BUTTON }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun skip() {
        val span = offered.value ?: return
        seekPast(span)
    }

    private fun seekPast(span: NonMusicSegment) {
        val duration = playback.value.durationMs
        local.seek(if (duration > 0) minOf(span.endMs, duration) else span.endMs)
    }

    fun observe() {
        scope.launch {
            combine(
                playback.map { it.currentTrack?.id.orEmpty() }.distinctUntilChanged(),
                graph.streams.sources,
                mode,
            ) { trackId, sources, choice -> Triple(trackId, sources[trackId], choice) }
                .distinctUntilChanged()
                // Latest wins: a skipped song's slow lookup must not hold up the next one's.
                .collectLatest { (trackId, source, choice) -> load(trackId, source, choice) }
        }
        scope.launch {
            // Playing is part of the trigger so that resuming inside a span skips it too.
            combine(activeSpan, mode, playback.map { it.isPlaying }.distinctUntilChanged()) { span, choice, playing ->
                span.takeIf { choice == NonMusicSkipMode.AUTO && playing }
            }.collect { span -> if (span != null) autoSkip(span) }
        }
    }

    private suspend fun load(trackId: String, source: PlaybackSource?, choice: NonMusicSkipMode) {
        if (segments.value?.trackId != trackId || choice == NonMusicSkipMode.OFF) segments.value = null
        // Nothing to look up yet (the stream is still resolving) or nothing wanted.
        if (choice == NonMusicSkipMode.OFF || trackId.isBlank() || source == null) return
        if (isLocalTrackId(trackId) || segments.value?.trackId == trackId) return
        val spans = runCatching {
            val reply = graph.youtube.invoke(
                "sponsorblock.segments",
                JSONObject().put("videoId", source.videoId).put("durationSeconds", source.durationSeconds),
            ).optJSONArray("segments")
            buildList {
                for (index in 0 until (reply?.length() ?: 0)) {
                    val item = reply?.optJSONObject(index) ?: continue
                    val start = (item.optDouble("startTime") * 1000).toLong()
                    val end = (item.optDouble("endTime") * 1000).toLong()
                    if (end > start) add(NonMusicSegment(item.optString("id"), start, end))
                }
            }
        }.onFailure {
            // A missing Skip button is not worth a warning toast.
            Log.d(TAG, "No SponsorBlock segments for $trackId", it)
        }.getOrDefault(emptyList())
        segments.value = Segments(trackId, spans)
    }

    private fun autoSkip(span: NonMusicSegment) {
        val snapshot = playback.value
        val trackId = snapshot.currentTrack?.id.orEmpty()
        // A restart (repeat one, previous) earns the intro its skip again.
        if (trackId != skippedTrackId || snapshot.positionMs < RESTART_MS) {
            skipped.clear()
            skippedTrackId = trackId
        }
        // Once per span: skipping the talking, not the listener's right to rewind the talking.
        if (skipped.add(span.id)) seekPast(span)
    }

    private companion object {
        const val TAG = "NonMusicSkipper"
        const val END_GRACE_MS = 500L
        const val RESTART_MS = 1_000L
    }
}
