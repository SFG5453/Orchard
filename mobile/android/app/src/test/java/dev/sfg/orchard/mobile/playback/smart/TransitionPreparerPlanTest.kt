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

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TransitionPreparerPlanTest {
    @Test fun `silent lead starts on the stretched outgoing source clock`() {
        val prepared = TransitionPreparer.Prepared(
            file = File("mix.wav"),
            startSeconds = 120.0,
            endSeconds = 128.0,
            leadInSeconds = 1.0,
            renderedDurationSeconds = 8.0,
            incomingResumeSeconds = 24.0,
            stretchRatio = 1.05,
        )

        assertEquals(118.95, prepared.playbackStartSeconds, 1e-9)
        assertEquals(9.0, prepared.outputDurationSeconds, 1e-9)
    }

    @Test fun `renderer consumes selected desktop fields without recomputing tempo or strategy`() {
        var rendered = 0
        for (case in desktopCases()) {
            val plan = mobilePlan(case.getJSONObject("input"))
            val selected = selectedRenderPlan(plan, 90.0, 1.0)
            val native = plan.nativePlan
            if (native == null) { assertNull(selected); continue }
            rendered++
            checkNotNull(selected)
            assertEquals(native.transitionStart - 90.0, selected.outgoingStart, 1e-9)
            assertEquals(native.incomingCueTime - 1.0, selected.incomingStart, 1e-9)
            assertEquals(native.overlapSeconds, selected.duration, 1e-9)
            assertEquals(native.targetBpm, selected.targetBpm, 1e-9)
            assertEquals(native.outgoingTempoRatio, selected.outgoingTempoRatio, 1e-9)
            assertEquals(native.incomingTempoRatio, selected.incomingTempoRatio, 1e-9)
            assertEquals(native.strategy, selected.strategy)
        }
        assertTrue(rendered > 0)
    }
}
