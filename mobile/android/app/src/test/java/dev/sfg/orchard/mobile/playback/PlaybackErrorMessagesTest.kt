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

import androidx.media3.common.util.UnstableApi
import java.io.IOException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@UnstableApi
class PlaybackErrorMessagesTest {
    @Test
    fun `finds a matching exception nested below Media3 wrappers`() {
        val expected = CdnFailure()
        val playbackError = IllegalStateException("Source error", IOException(expected))

        assertSame(expected, findCause(playbackError, CdnFailure::class.java))
    }

    @Test
    fun `returns null when the failure did not reach the CDN`() {
        val resolverError = IllegalStateException("No direct audio format was returned")

        assertNull(playbackHttpResponseCode(resolverError))
    }

    private class CdnFailure : IOException()
}
