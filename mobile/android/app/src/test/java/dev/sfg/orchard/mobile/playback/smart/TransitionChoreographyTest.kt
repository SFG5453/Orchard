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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionChoreographyTest {

    private fun validChoreography(
        outgoing: OutgoingChoreography = OutgoingChoreography(100.0, 108.0, 1.0),
        incoming: IncomingChoreography = IncomingChoreography(0.0, 4.0, 8.0, 1.0),
        duration: Double = 8.0,
        curves: AutomationCurves = AutomationCurves(
            outgoingGain = listOf(AutomationPoint(0.0, 1.0), AutomationPoint(1.0, 0.0)),
            incomingGain = listOf(AutomationPoint(0.0, 0.0), AutomationPoint(1.0, 1.0)),
            outgoingLowPass = listOf(AutomationPoint(0.0, 20000.0), AutomationPoint(1.0, 700.0)),
            outgoingBass = listOf(
                AutomationPoint(0.0, 1.0),
                AutomationPoint(0.45, 1.0),
                AutomationPoint(0.55, 0.0),
                AutomationPoint(1.0, 0.0),
            ),
            incomingBass = listOf(
                AutomationPoint(0.0, 0.0),
                AutomationPoint(0.45, 0.0),
                AutomationPoint(0.55, 1.0),
                AutomationPoint(1.0, 1.0),
            ),
        ),
    ): TransitionChoreography = TransitionChoreography(
        schemaVersion = CHOREOGRAPHY_SCHEMA_VERSION,
        strategy = ChoreographyStrategy.STAGED_BLEND,
        outgoing = outgoing,
        incoming = incoming,
        duration = duration,
        dominancePoint = 0.55,
        curves = curves,
        bassSwapPoint = 0.5,
        confidence = 0.95,
    )

    @Test
    fun `valid choreography passes validation`() {
        val choreo = validChoreography()
        val result = choreo.validate()
        assertTrue("Expected valid but got: ${result.errors}", result.isValid)
    }

    @Test
    fun `validation rejects non-finite timing`() {
        val invalid = validChoreography(
            outgoing = OutgoingChoreography(Double.NaN, 108.0, 1.0)
        )
        val result = invalid.validate()
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("outgoing start") })
    }

    @Test
    fun `validation rejects unsorted or out of range points`() {
        val unsorted = validChoreography(
            curves = AutomationCurves(
                outgoingGain = listOf(
                    AutomationPoint(0.5, 1.0),
                    AutomationPoint(0.2, 0.5),
                )
            )
        )
        val resUnsorted = unsorted.validate()
        assertFalse(resUnsorted.isValid)
        assertTrue(resUnsorted.errors.any { it.contains("not sorted") })

        val outOfRange = validChoreography(
            curves = AutomationCurves(
                incomingGain = listOf(
                    AutomationPoint(-0.1, 0.0),
                    AutomationPoint(1.2, 1.0),
                )
            )
        )
        val resOutOfRange = outOfRange.validate()
        assertFalse(resOutOfRange.isValid)
        assertTrue(resOutOfRange.errors.any { it.contains("out of range") })
    }

    @Test
    fun `validation rejects arrival before cue`() {
        val invalid = validChoreography(
            incoming = IncomingChoreography(cue = 4.0, arrival = 2.0, resume = 12.0, tempoRatio = 1.0)
        )
        val result = invalid.validate()
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("arrival") && it.contains("before cue") })
    }

    @Test
    fun `validation rejects resume before cue`() {
        val invalid = validChoreography(
            incoming = IncomingChoreography(cue = 8.0, arrival = 10.0, resume = 4.0, tempoRatio = 1.0)
        )
        val result = invalid.validate()
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("resume") && it.contains("before cue") })
    }

    @Test
    fun `validation rejects duration inconsistent with source consumption`() {
        val inconsistent = validChoreography(
            outgoing = OutgoingChoreography(start = 100.0, end = 110.0, tempoRatio = 1.0),
            duration = 8.0,
        )
        val result = inconsistent.validate()
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("inconsistent with outgoing consumption") })
    }

    @Test
    fun `validation rejects bass ownership that leaves both decks active outside short swap ramp`() {
        val dualBass = validChoreography(
            curves = AutomationCurves(
                outgoingBass = listOf(AutomationPoint(0.0, 1.0), AutomationPoint(1.0, 1.0)),
                incomingBass = listOf(AutomationPoint(0.0, 1.0), AutomationPoint(1.0, 1.0)),
            )
        )
        val result = dualBass.validate()
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Bass ownership leaves both decks active simultaneously") })
    }

    @Test
    fun `curve evaluation produces expected interpolated values`() {
        val linear = listOf(
            AutomationPoint(0.0, 0.0, CurveInterpolation.LINEAR),
            AutomationPoint(1.0, 10.0),
        )
        assertEquals(0.0, evaluateAutomationCurve(linear, 0.0), 1e-6)
        assertEquals(5.0, evaluateAutomationCurve(linear, 0.5), 1e-6)
        assertEquals(10.0, evaluateAutomationCurve(linear, 1.0), 1e-6)

        val smooth = listOf(
            AutomationPoint(0.0, 0.0, CurveInterpolation.SMOOTH_STEP),
            AutomationPoint(1.0, 1.0),
        )
        assertEquals(0.5, evaluateAutomationCurve(smooth, 0.5), 1e-6)
        assertEquals(0.15625, evaluateAutomationCurve(smooth, 0.25), 1e-4)
    }
}
