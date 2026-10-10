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

package dev.sfg.orchard.mobile.playback.slop

import org.junit.Assert.*
import org.junit.Test

class SlopPolicyTest {
    @Test fun `window contains current item and only three upcoming positions`() {
        assertEquals(listOf(10, 11, 12, 13), SlopPolicy.windowIndices(2500, 10).toList())
        assertEquals(listOf(12, 13, 14, 15), SlopPolicy.windowIndices(2500, 12).toList())
        assertEquals(listOf(98, 99), SlopPolicy.windowIndices(100, 98).toList())
        assertTrue(SlopPolicy.windowIndices(0, 0).isEmpty())
        assertTrue(SlopPolicy.windowIndices(10, -1).isEmpty())
        assertTrue(SlopPolicy.windowIndices(10, 10).isEmpty())
    }

    @Test fun `skip passes consecutive flagged songs and permits unknown songs`() {
        val ids = listOf("current", "ai1", "ai2", "unknown", "human")
        val known = mapOf("current" to 0.99f, "ai1" to 0.95f, "ai2" to 0.9f, "human" to 0.1f)
        assertEquals(3, SlopPolicy.nextAllowed(ids, 0, known))
        assertEquals(4, SlopPolicy.nextAllowed(ids, 3, known))
        assertNull(SlopPolicy.nextAllowed(ids, 4, known))
        assertNull(SlopPolicy.nextAllowed(listOf("current", "ai1", "ai2"), 0, known))
        assertFalse(SlopPolicy.flagged("unknown", known))
        assertTrue(SlopPolicy.flagged("ai2", known))
    }
}
