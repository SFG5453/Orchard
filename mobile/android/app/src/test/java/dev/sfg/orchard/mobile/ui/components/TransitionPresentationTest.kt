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

package dev.sfg.orchard.mobile.ui.components

import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionPresentationTest {
    private val outgoing = Track("out", "Old title", "Old artist", durationMs = 240_000)
    private val incoming = Track("in", "New title", "New artist", durationMs = 180_000)

    private fun marker(
        handoff: Float = 0.7f,
        renderedDurationMs: Long = 0,
        rate: Double = 1.0,
    ) =
        TransitionMarker(
            trackId = outgoing.id,
            startMs = 200_000,
            endMs = 210_000,
            style = "dj_blend",
            incomingTrackId = incoming.id,
            incomingCueMs = 12_000,
            incomingPlaybackRate = rate,
            audibleHandoffProgress = handoff,
            renderedDurationMs = renderedDurationMs,
        )

    @Test
    fun `identity stays outgoing until incoming is strictly louder`() {
        val atCrossover =
            PlaybackSnapshot(
                currentTrack = outgoing,
                queue = listOf(outgoing, incoming),
                currentIndex = 0,
                positionMs = 207_000,
                durationMs = outgoing.durationMs,
            )
        val afterCrossover = atCrossover.copy(positionMs = 207_010)

        assertFalse(transitionPresentation(atCrossover, marker()).incomingDominant)
        assertTrue(transitionPresentation(afterCrossover, marker()).incomingDominant)
    }

    @Test
    fun `artwork metadata and progress move to incoming track together`() {
        val playback =
            PlaybackSnapshot(
                currentTrack = outgoing,
                queue = listOf(outgoing, incoming),
                currentIndex = 0,
                positionMs = 207_500,
                durationMs = outgoing.durationMs,
            )

        val presentation = transitionPresentation(playback, marker(rate = 1.04))

        assertEquals(incoming, presentation.playback.currentTrack)
        assertEquals(1, presentation.playback.currentIndex)
        assertEquals(19_800, presentation.playback.positionMs)
        assertEquals(incoming.durationMs, presentation.playback.durationMs)
    }

    @Test
    fun `rendered mix uses its zero based clock`() {
        val playback =
            PlaybackSnapshot(
                currentTrack = outgoing,
                queue = listOf(outgoing, incoming),
                currentIndex = 0,
                positionMs = 7_500,
                durationMs = 10_000,
            )

        val presentation =
            transitionPresentation(
                playback,
                marker(renderedDurationMs = 10_000),
            )

        assertEquals(0.75f, presentation.progress, 0.0001f)
        assertTrue(presentation.incomingDominant)
        assertEquals(19_500, presentation.playback.positionMs)
    }

    @Test
    fun `stale incoming marker does not hold the UI after the overlap`() {
        val playback =
            PlaybackSnapshot(
                currentTrack = incoming,
                queue = listOf(outgoing, incoming),
                currentIndex = 1,
                positionMs = 30_000,
                durationMs = incoming.durationMs,
            )

        assertEquals(0f, transitionProgress(playback, marker()), 0f)
        assertFalse(transitionPresentation(playback, marker()).incomingDominant)
    }
}
