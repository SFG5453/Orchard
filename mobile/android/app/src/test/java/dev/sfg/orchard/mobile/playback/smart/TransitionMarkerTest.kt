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

import org.junit.Assert.*
import org.junit.Test

class TransitionMarkerTest {
    private fun plan(): TransitionPlan {
        val source = mobilePlan(desktopCases().first().getJSONObject("input"))
        val native = checkNotNull(source.nativePlan).copy(
            transitionStart = 200.0, transitionEnd = 210.0, overlapSeconds = 8.0,
            incomingCueTime = 12.0, incomingTempoRatio = 1.0,
            handoffFraction = 0.5, bassSwapFraction = 0.7,
        )
        return source.copy(transitionStart = 207.0, transitionEnd = 210.0,
            incomingCueTime = 17.0, nativePlan = native)
    }

    @Test fun `prepared marker takes all timing from the native plan`() {
        val marker = transitionMarkerFor(plan(), "out", "in", rendered = true)
        assertEquals(200_000L, marker.startMs)
        assertEquals(210_000L, marker.endMs)
        assertEquals(8_000L, marker.renderedDurationMs)
        assertEquals(12_000L, marker.incomingCueMs)
        assertEquals(0.7f, marker.audibleHandoffProgress, 0f)
    }

    @Test fun `unavailable or refused render presents the complete live fallback`() {
        val plan = plan()
        val fallback = transitionMarkerFor(plan, "out", "in", rendered = false)
        assertEquals(207_000L, fallback.startMs)
        assertEquals(0L, fallback.renderedDurationMs)
        assertEquals(17_000L, fallback.incomingCueMs)
        assertEquals(fallback, transitionMarkerFor(plan.copy(nativePlan = null), "out", "in", rendered = true))
    }
}
