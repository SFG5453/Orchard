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

import dev.sfg.orchard.mobile.model.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewPipeStreamResolverTest {
    private val bitrates = listOf(50, 128, 160, 251)

    @Test
    fun `quality tiers select the intended NewPipe bitrate`() {
        assertEquals(50, selectedBitrate(AudioQuality.DATA_SAVER))
        assertEquals(128, selectedBitrate(AudioQuality.NORMAL))
        assertEquals(160, selectedBitrate(AudioQuality.HIGH))
        assertEquals(251, selectedBitrate(AudioQuality.MAX))
    }

    @Test
    fun `raw bitrates reported in bits per second are normalized`() {
        val raw = listOf(50_000, 128_000, 160_000, 251_000)

        val index = selectNewPipeStreamIndex(raw, AudioQuality.HIGH)

        assertEquals(160_000, raw[index!!])
        assertEquals(160, newPipeBitrateKbps(raw[index]))
    }

    @Test
    fun `tier falls back to the lowest stream when every bitrate exceeds its cap`() {
        val raw = listOf(251, 320)

        assertEquals(0, selectNewPipeStreamIndex(raw, AudioQuality.NORMAL))
    }

    @Test
    fun `empty stream list has no selection`() {
        assertNull(selectNewPipeStreamIndex(emptyList(), AudioQuality.MAX))
    }

    private fun selectedBitrate(quality: AudioQuality): Int {
        val index = selectNewPipeStreamIndex(bitrates, quality)
        return bitrates[index!!]
    }
}
