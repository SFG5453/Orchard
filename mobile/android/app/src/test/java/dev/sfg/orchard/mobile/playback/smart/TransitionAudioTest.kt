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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionAudioTest {
    @Test fun `wav lead is silent and rendered samples begin after it`() {
        val rendered = TransitionRenderer.Rendered(
            left = floatArrayOf(0.5f),
            right = floatArrayOf(-0.5f),
            stretchRatio = 1.0,
            incomingStretchRatio = 1.0,
            outgoingStart = 0.0,
            incomingStart = 0.0,
            outgoingResume = 1.0,
            incomingResume = 1.0,
            beats = 4,
            strategy = "beatmatched_crossfade",
        )
        val target = File.createTempFile("orchard-transition", ".wav")
        try {
            assertNotNull(TransitionAudio.writeWav(
                rendered,
                target,
                leadingSilenceSeconds = 1.0 / TransitionRenderer.SAMPLE_RATE,
            ))
            val bytes = target.readBytes()
            val dataBytes = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int

            assertEquals(8, dataBytes)
            assertTrue(bytes.sliceArray(44 until 48).all { it == 0.toByte() })
            assertTrue(bytes.sliceArray(48 until 52).any { it != 0.toByte() })
        } finally {
            target.delete()
        }
    }
}
