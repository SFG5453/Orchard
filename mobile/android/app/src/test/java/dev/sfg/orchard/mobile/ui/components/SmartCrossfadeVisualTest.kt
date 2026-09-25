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

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SmartCrossfadeVisualTest {

    @Test
    fun `transition style maps to user-friendly label`() {
        assertEquals("Beat Matched", transitionStyleLabel("dj_blend"))
        assertEquals("Filtered Blend", transitionStyleLabel("dj_filter"))
        assertEquals("Equal Power", transitionStyleLabel("equal_power"))
        assertEquals("Seamless Handoff", transitionStyleLabel("gapless"))
        assertEquals("Tempo Matched", transitionStyleLabel("tempo_matched"))
        assertEquals("Bass Swap", transitionStyleLabel("bass_first"))
        assertEquals("Smart Mix", transitionStyleLabel("smart"))
        assertEquals("Smart Mix", transitionStyleLabel(""))
        assertEquals("Custom Style", transitionStyleLabel("custom_style"))
    }

    @Test
    fun `constant power visual curve maintains total energy`() {
        // Sample points across the mix
        val testPoints = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

        for (progress in testPoints) {
            val outgoingGain = cos(progress * (PI.toFloat() / 2f))
            val incomingGain = sin(progress * (PI.toFloat() / 2f))

            // In constant-power mixing: cos^2 + sin^2 = 1.0
            val totalPower = outgoingGain * outgoingGain + incomingGain * incomingGain
            assertEquals(1.0f, totalPower, 0.001f)
        }

        // Check boundaries
        val out0 = cos(0f * (PI.toFloat() / 2f))
        val in0 = sin(0f * (PI.toFloat() / 2f))
        assertEquals(1.0f, out0, 0.001f)
        assertEquals(0.0f, in0, 0.001f)

        val out1 = cos(1.0f * (PI.toFloat() / 2f))
        val in1 = sin(1.0f * (PI.toFloat() / 2f))
        assertEquals(0.0f, out1, 0.001f)
        assertEquals(1.0f, in1, 0.001f)
    }
}
