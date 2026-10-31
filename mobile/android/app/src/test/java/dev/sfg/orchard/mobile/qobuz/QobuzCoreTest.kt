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

package dev.sfg.orchard.mobile.qobuz

import org.junit.Assert.assertEquals
import org.junit.Test

class QobuzCoreTest {
    @Test
    fun `formatPlaybackQualityLabel formats Hi-Res and Lossless tiers`() {
        assertEquals(
            "Qobuz Hi-Res · 24-bit / 96 kHz",
            formatPlaybackQualityLabel(playbackSource = "qobuz", hires = true, bitDepth = 24, sampleRate = 96000),
        )
        assertEquals(
            "Qobuz Lossless · 16-bit / 44.1 kHz",
            formatPlaybackQualityLabel(playbackSource = "qobuz", hires = false, bitDepth = 16, sampleRate = 44100),
        )
        assertEquals(
            "",
            formatPlaybackQualityLabel(playbackSource = "youtube", hires = false, bitDepth = null, sampleRate = null),
        )
    }
}
