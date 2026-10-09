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

package dev.sfg.orchard.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLetterboxTest {
    private val black = 0xff000000.toInt()
    private val grey = 0xff808080.toInt()

    /** A [width] x [height] black frame lit only inside the given pixel box. */
    private fun frame(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int) =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x in left until right && y in top until bottom) grey else black
        }

    @Test
    fun `finds bars baked above and below a widescreen picture`() {
        val rect = VideoLetterbox.contentRect(frame(160, 90, 0, 12, 160, 78), 160, 90)!!
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(12f / 90, rect.top, 0.001f)
        assertEquals(1f, rect.right, 0.001f)
        assertEquals(78f / 90, rect.bottom, 0.001f)
    }

    @Test
    fun `finds pillarbox bars around a 4 by 3 picture`() {
        val rect = VideoLetterbox.contentRect(frame(160, 90, 20, 0, 140, 90), 160, 90)!!
        assertEquals(20f / 160, rect.left, 0.001f)
        assertEquals(140f / 160, rect.right, 0.001f)
    }

    @Test
    fun `a dark scene is not mistaken for bars`() {
        assertNull(VideoLetterbox.contentRect(frame(160, 90, 70, 40, 90, 50), 160, 90))
        assertNull(VideoLetterbox.contentRect(IntArray(160 * 90) { black }, 160, 90))
    }

    @Test
    fun `accumulating only ever widens and snaps near edges`() {
        val first = VideoLetterbox.accumulate(null, ContentRect(0.01f, 0.2f, 0.99f, 0.8f))!!
        assertEquals(ContentRect(0f, 0.2f, 1f, 0.8f), first)
        val wider = VideoLetterbox.accumulate(first, ContentRect(0.1f, 0.1f, 0.9f, 0.85f))
        assertEquals(ContentRect(0f, 0.1f, 1f, 0.85f), wider)
        assertEquals(wider, VideoLetterbox.accumulate(wider, null))
    }
}
