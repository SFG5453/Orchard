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

import dev.sfg.orchard.mobile.playback.djMixGains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionHandoffTest {
    @Test
    fun `live DJ blend waits for its later bass crossover`() {
        val plan =
            TransitionPlan(
                transitionStyle = TransitionStyle.DJ_BLEND,
                handoffFraction = 0.5,
                bassSwapFraction = 0.6,
            )

        assertEquals(0.7f, audibleHandoffProgress(plan, rendered = false), 0f)

        val firstVisibleFrame = djMixGains(progress = 0.701, fadeSeconds = 7.5)
        assertTrue(firstVisibleFrame.incomingUpper > firstVisibleFrame.outgoingUpper)
        assertTrue(firstVisibleFrame.incomingBass > firstVisibleFrame.outgoingBass)
    }

    @Test
    fun `rendered DJ blend waits for whichever rendered band crosses last`() {
        val lateBass =
            TransitionPlan(
                transitionStyle = TransitionStyle.DJ_BLEND,
                handoffFraction = 0.5,
                bassSwapFraction = 0.68,
            )
        val earlyBass = lateBass.copy(bassSwapFraction = 0.4)

        assertEquals(0.68f, audibleHandoffProgress(lateBass, rendered = true), 0f)
        assertEquals(0.5f, audibleHandoffProgress(earlyBass, rendered = true), 0f)
    }

    @Test
    fun `ordinary and gapless transitions use their actual gain crossover`() {
        assertEquals(
            0.5f,
            audibleHandoffProgress(
                TransitionPlan(transitionStyle = TransitionStyle.EQUAL_POWER),
                rendered = false,
            ),
            0f,
        )
        assertEquals(
            0f,
            audibleHandoffProgress(
                TransitionPlan(transitionStyle = TransitionStyle.GAPLESS),
                rendered = false,
            ),
            0f,
        )
    }
}
