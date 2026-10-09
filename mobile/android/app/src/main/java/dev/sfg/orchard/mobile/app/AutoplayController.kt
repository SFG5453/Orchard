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

import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.AutoplayRecommendations
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Autoplay: once the queue is nearly out, ask YouTube Music what would come next after the last
 * queued track and append it. Only the local player is refilled, because a Connect device owns
 * its own queue and would fight us for it.
 */
internal class AutoplayController(
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val local: LocalPlaybackController,
    private val playback: StateFlow<PlaybackSnapshot>,
    private val targets: StateFlow<PlaybackTargetState>,
) {
    private val mutableLoading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()
    private val mutableError = MutableStateFlow("")
    val error: StateFlow<String> = mutableError.asStateFlow()

    /** Seed of the request in flight, so a burst of queue updates cannot fan out into duplicates. */
    private var seedInFlight = ""

    /** Seed that already came back with nothing usable; retrying it would fail the same way. */
    private var exhaustedSeed = ""

    /**
     * Whether refills are allowed, tracked apart from the persisted setting because DataStore
     * writes land asynchronously. Reading the setting would let the refill observer see a stale
     * `true` right after switching off, emptying the queue and refilling it from the same radio.
     */
    private val gate = MutableStateFlow(graph.settings.settings.value.autoplayEnabled)

    private data class Trigger(
        val seedId: String,
        val remaining: Int,
        val enabled: Boolean,
        val isLocal: Boolean,
    )

    fun setEnabled(enabled: Boolean) {
        gate.value = enabled
        graph.settings.updateSettings(graph.settings.settings.value.copy(autoplayEnabled = enabled))
        if (enabled) return
        // Turning it off should undo what it added, not leave the queue full of unasked-for music.
        mutableError.value = ""
        exhaustedSeed = ""
        if (targets.value.selected !is PlaybackTarget.LocalPhone) return
        val upcoming = playback.value.upcoming
        val kept = upcoming.filterNot(Track::autoplayGenerated)
        if (kept.size != upcoming.size) local.replaceUpcoming(kept)
    }

    fun observe() {
        // Persisted changes from anywhere else (the Settings screen, the first DataStore read)
        // still have to reach the gate.
        scope.launch {
            graph.settings.settings.map { it.autoplayEnabled }
                .distinctUntilChanged()
                .collect { gate.value = it }
        }
        scope.launch {
            combine(playback, gate, targets) { snapshot, enabled, target ->
                val upcoming = snapshot.upcoming
                Trigger(
                    // The tail of the queue is the seed: recommendations should follow the music the
                    // listener will actually reach, not the track playing several songs earlier.
                    seedId = upcoming.lastOrNull()?.id.orEmpty().ifBlank { snapshot.currentTrack?.id.orEmpty() },
                    remaining = upcoming.size,
                    enabled = enabled,
                    isLocal = target.selected is PlaybackTarget.LocalPhone,
                )
            }
                // Playback ticks every second; without this the refill check would run with it.
                .distinctUntilChanged()
                .collect(::refill)
        }
    }

    private fun refill(trigger: Trigger) {
        if (!trigger.enabled || !trigger.isLocal) return
        if (trigger.remaining > REFILL_THRESHOLD) return
        // Local files have no "up next" on YouTube; the queue ends when it ends.
        if (dev.sfg.orchard.mobile.local.isLocalTrackId(trigger.seedId)) return
        if (trigger.seedId.isBlank() || !graph.networkMonitor.isOnline.value) return
        if (trigger.seedId == seedInFlight || trigger.seedId == exhaustedSeed) return

        seedInFlight = trigger.seedId
        mutableLoading.value = true
        mutableError.value = ""
        scope.launch {
            runCatching { graph.catalog.upNext(trigger.seedId) }
                .onSuccess { candidates -> append(trigger.seedId, candidates) }
                .onFailure { mutableError.value = it.message ?: "Could not load Autoplay recommendations." }
            mutableLoading.value = false
            seedInFlight = ""
        }
    }

    private fun append(seedId: String, candidates: List<Track>) {
        // Filtered against the queue as it stands, because the listener may have queued or skipped
        // while the request was in the air. The append filters again on the player's own state,
        // which is authoritative; this pass only decides whether the seed is exhausted.
        val snapshot = playback.value
        val known = snapshot.queue + listOfNotNull(snapshot.currentTrack)
        val additions = AutoplayRecommendations
            .select(known, candidates, QUEUE_LIMIT)
            .map { it.copy(autoplayGenerated = true) }
        if (additions.isEmpty()) {
            exhaustedSeed = seedId
            mutableError.value = "No more recommendations were found."
            return
        }
        // Appended, never written back as a whole tail: the tail in this snapshot is already stale.
        local.appendUpcoming(additions, TOTAL_LIMIT)
    }

    private companion object {
        /** Refill once the queue is this short, so the fetch lands well before the music stops. */
        const val REFILL_THRESHOLD = 3
        const val QUEUE_LIMIT = 20
        const val TOTAL_LIMIT = 100
    }
}
