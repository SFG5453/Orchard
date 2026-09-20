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

package dev.sfg.orchard.mobile.catalog

import dev.sfg.orchard.mobile.model.MUSIC_VIDEO_TYPE_ATV
import dev.sfg.orchard.mobile.model.MUSIC_VIDEO_TYPE_UGC
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVersionResolverTest {
    @Test
    fun `same title and artist cannot replace a track with a radically different duration`() {
        val playlistTrack = Track(
            id = "QZZCSmPnIG0",
            title = "Mania / Hoodtrap",
            artist = "okksu",
            durationMs = 77_000,
            musicVideoType = MUSIC_VIDEO_TYPE_UGC,
        )
        val wrongAudio = Track(
            id = "2MTBSJkyEIY",
            title = "Mania / Hoodtrap",
            artist = "okksu",
            durationMs = 170_000,
            musicVideoType = MUSIC_VIDEO_TYPE_ATV,
        )

        assertEquals(0, wrongAudio.matchScore(playlistTrack))
        assertTrue(wrongAudio.copy(durationMs = 90_000).matchScore(playlistTrack) > 0)
    }
}
