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

package dev.sfg.orchard.mobile.playback

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeMixCurveTest {

    @Test
    fun `the runway keeps its bass on the outgoing track`() {
        val start = djMixGains(progress = 0.0, fadeSeconds = 7.5)
        val runway = djMixGains(progress = 0.5, fadeSeconds = 7.5)

        assertEquals(1.0, start.outgoingBass, 1e-9)
        assertEquals(0.0, start.incomingBass, 1e-9)
        assertEquals(1.0, runway.outgoingBass, 1e-9)
        assertEquals(0.0, runway.incomingBass, 1e-9)
        assertTrue("the upper fade should still be moving", runway.outgoingUpper < 1.0)
        assertTrue("the incoming upper band should still rise", runway.incomingUpper > 0.0)
    }

    @Test
    fun `the bass changes hands smoothly at constant power`() {
        val middle = djMixGains(progress = 0.7, fadeSeconds = 7.5)
        val expected = 1.0 / sqrt(2.0)

        assertEquals(expected, middle.outgoingBass, 1e-9)
        assertEquals(expected, middle.incomingBass, 1e-9)
        assertEquals(
            1.0,
            middle.outgoingBass * middle.outgoingBass +
                middle.incomingBass * middle.incomingBass,
            1e-9,
        )
    }

    @Test
    fun `the incoming track owns every band when the blend finishes`() {
        val end = djMixGains(progress = 1.0, fadeSeconds = 7.5)

        assertEquals(0.0, end.outgoingUpper, 1e-9)
        assertEquals(0.0, end.outgoingBass, 1e-9)
        assertEquals(1.0, end.incomingUpper, 1e-9)
        assertEquals(1.0, end.incomingBass, 1e-9)
    }
}
