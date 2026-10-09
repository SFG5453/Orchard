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

import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Pauses playback after a delay or at the end of the current track. Bedtime, enforced. */
internal class SleepTimer(
    private val scope: CoroutineScope,
    private val playback: StateFlow<PlaybackSnapshot>,
    private val pause: () -> Unit,
) {
    private val mutableRemainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = mutableRemainingSeconds.asStateFlow()
    private val mutableEndOfTrack = MutableStateFlow(false)
    val endOfTrack: StateFlow<Boolean> = mutableEndOfTrack.asStateFlow()
    private var job: Job? = null

    fun start(minutes: Int) {
        if (minutes <= 0) return
        cancel()
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        mutableRemainingSeconds.value = minutes * 60L
        job = scope.launch {
            while (true) {
                val remaining = ((deadline - System.currentTimeMillis() + 999L) / 1_000L).coerceAtLeast(0L)
                mutableRemainingSeconds.value = remaining
                if (remaining == 0L) break
                delay(1_000L)
            }
            fire()
        }
    }

    fun startAtEndOfTrack() {
        val trackId = playback.value.currentTrack?.id ?: return
        cancel()
        mutableEndOfTrack.value = true
        job = scope.launch {
            playback.map { it.currentTrack?.id }
                .dropWhile { it == trackId }
                .first()
            fire()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        clear()
    }

    private fun fire() {
        if (playback.value.isPlaying) pause()
        clear()
    }

    private fun clear() {
        mutableRemainingSeconds.value = 0L
        mutableEndOfTrack.value = false
    }
}
