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

/** Picture area inside a frame, as fractions of the frame. */
internal data class ContentRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val Full = ContentRect(0f, 0f, 1f, 1f)
    }
}

/** Finds black bars baked into music videos; the same rules as desktop's letterbox.cpp. */
internal object VideoLetterbox {
    /** Frames are downscaled to this width before sampling. */
    const val SAMPLE_WIDTH = 192

    // Limited-range video black decodes near 16; compression noise stays under this.
    private const val BLACK_LEVEL = 32
    // Sides this close to the frame edge are treated as no bar at all.
    private const val SNAP = 0.03f
    // Bars never cover more than this; a smaller lit area means a dark scene.
    private const val MIN_CONTENT = 0.4f

    /** The lit area of an ARGB frame, or null when it is too dark to tell bars from the scene. */
    fun contentRect(pixels: IntArray, width: Int, height: Int): ContentRect? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        fun bright(x: Int, y: Int): Boolean {
            val c = pixels[y * width + x]
            val luma = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000
            return luma > BLACK_LEVEL
        }
        fun lit(count: Int, span: Int) = count > span / 50 + 1
        fun rowLit(y: Int) = lit((0 until width).count { bright(it, y) }, width)

        var top = 0
        while (top < height && !rowLit(top)) top++
        if (top == height) return null
        var bottom = height - 1
        while (bottom > top && !rowLit(bottom)) bottom--

        fun columnLit(x: Int) = lit((top..bottom).count { bright(x, it) }, bottom - top + 1)
        var left = 0
        while (left < width && !columnLit(left)) left++
        var right = width - 1
        while (right > left && !columnLit(right)) right--

        val rect = ContentRect(
            left.toFloat() / width,
            top.toFloat() / height,
            (right + 1).toFloat() / width,
            (bottom + 1).toFloat() / height,
        )
        return rect.takeIf { it.width >= MIN_CONTENT && it.height >= MIN_CONTENT }
    }

    /** Grows [current] to cover [sample], so a dark scene can never zoom further in. */
    fun accumulate(current: ContentRect?, sample: ContentRect?): ContentRect? {
        if (sample == null) return current
        val grown = if (current == null) sample else ContentRect(
            minOf(current.left, sample.left),
            minOf(current.top, sample.top),
            maxOf(current.right, sample.right),
            maxOf(current.bottom, sample.bottom),
        )
        return ContentRect(
            if (grown.left < SNAP) 0f else grown.left,
            if (grown.top < SNAP) 0f else grown.top,
            if (grown.right > 1 - SNAP) 1f else grown.right,
            if (grown.bottom > 1 - SNAP) 1f else grown.bottom,
        )
    }
}
