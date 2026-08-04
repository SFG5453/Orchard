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

package dev.sfg.orchard.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVolumeMonitorTest {

    @Test
    fun volumeFractionCalculation() {
        var recordedVolume = 0
        val controller = SystemVolumeController(
            volume = 5,
            minVolume = 0,
            maxVolume = 10,
            setVolume = { recordedVolume = it },
        )

        assertEquals(0.5f, controller.fraction, 0.001f)
        assertFalse(controller.isMuted)
    }

    @Test
    fun muteAndMaxActions() {
        var recordedVolume = -1
        val controller = SystemVolumeController(
            volume = 7,
            minVolume = 0,
            maxVolume = 15,
            setVolume = { recordedVolume = it },
        )

        controller.mute()
        assertEquals(0, recordedVolume)

        controller.max()
        assertEquals(15, recordedVolume)
    }

    @Test
    fun stepUpAndStepDownClamping() {
        var recordedVolume = -1
        val controller = SystemVolumeController(
            volume = 15,
            minVolume = 0,
            maxVolume = 15,
            setVolume = { recordedVolume = it },
        )

        controller.stepUp(1)
        assertEquals(15, recordedVolume)

        val controllerLow = SystemVolumeController(
            volume = 0,
            minVolume = 0,
            maxVolume = 15,
            setVolume = { recordedVolume = it },
        )

        controllerLow.stepDown(1)
        assertEquals(0, recordedVolume)
        assertTrue(controllerLow.isMuted)
    }
}
