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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionPreparerPlanTest {

    @Test
    fun `native plan measures duration on target grid and stretches outgoing deck`() {
        val beats = 16
        val outgoingBpm = 126.0
        val incomingBpm = 124.0
        val outgoingDuration = beats * 60 / outgoingBpm
        val plan = TransitionPlan(
            transitionStart = 220.0,
            transitionEnd = 220.0 + outgoingDuration,
            fadeSeconds = outgoingDuration,
            transitionStyle = TransitionStyle.DJ_BLEND,
            incomingCueTime = 12.0,
            transitionBeats = beats,
            bassSwap = true,
            outgoingBpm = outgoingBpm,
            incomingBpm = incomingBpm,
        )

        val selected = checkNotNull(
            selectedRenderPlan(
                plan = plan,
                outgoingSliceStart = 210.0,
                incomingSliceStart = 8.0,
            ),
        )
        assertEquals(beats * 60 / incomingBpm, selected.duration, 1e-9)
        assertEquals(incomingBpm, selected.targetBpm, 1e-9)
        assertEquals(incomingBpm / outgoingBpm, selected.outgoingTempoRatio, 1e-9)
        assertEquals(1.0, selected.incomingTempoRatio, 1e-9)
        assertEquals(
            "the native plan must consume the outgoing window selected by the planner",
            outgoingDuration,
            selected.duration * selected.outgoingTempoRatio,
            1e-9,
        )
    }
}
