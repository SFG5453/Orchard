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

class WsolaPlannerTest {
    @Test fun `native adapter matches desktop without mobile arrangement offsets`() {
        var nativeCount = 0
        var refusedCount = 0
        for (case in desktopCases()) {
            val input = case.getJSONObject("input")
            val expected = case.getJSONObject("native")
            val result = planWsolaTransition(mobileAnalysis(input.getJSONObject("analysis")),
                mobileAnalysis(input.getJSONObject("nextAnalysis")),
                maxOf(input.getDouble("duration"), input.getJSONObject("currentTrack").getDouble("durationSeconds")),
                input.getJSONObject("nextTrack").getDouble("durationSeconds"))
            val name = case.getString("name")
            if (!expected.getBoolean("ok")) {
                assertEquals(name, WsolaPlanResult.Refused(expected.getString("reason")), result)
                refusedCount++
                continue
            }
            nativeCount++
            assertTrue(name, result is WsolaPlanResult.Planned)
            result as WsolaPlanResult.Planned
            assertEquals(name, expected.getDouble("transitionStart"), result.transitionStart, 1e-9)
            assertEquals(name, expected.getDouble("transitionEnd"), result.transitionEnd, 1e-9)
            assertEquals(name, expected.getDouble("incomingCueTime"), result.incomingCueTime, 1e-9)
            assertEquals(name, expected.getDouble("incomingDropTime"), result.incomingDropTime, 1e-9)
            assertEquals(name, expected.getDouble("incomingResumeTime"), result.incomingResumeTime, 1e-9)
            assertEquals(name, expected.getDouble("overlapSeconds"), result.overlapSeconds, 1e-9)
            assertEquals(name, expected.getDouble("targetBpm"), result.targetBpm, 1e-9)
            assertEquals(name, expected.getDouble("outgoingTempoRatio"), result.outgoingTempoRatio, 1e-9)
            assertEquals(name, expected.getDouble("incomingTempoRatio"), result.incomingTempoRatio, 1e-9)
            assertEquals(name, expected.getInt("beats"), result.beats)
            assertEquals(name, expected.getString("strategy"), result.strategy)
            assertEquals(name, expected.getJSONObject("choreography").choreography(), result.choreography)
        }
        assertTrue("fixtures must exercise native planning", nativeCount > 0)
        assertTrue("fixtures must exercise refusal", refusedCount > 0)
    }
}
