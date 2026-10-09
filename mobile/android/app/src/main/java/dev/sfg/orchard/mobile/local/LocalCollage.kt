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

package dev.sfg.orchard.mobile.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.File
import java.security.MessageDigest

/**
 * Cover for a playlist nobody drew one for: the first four distinct covers in a 2x2 grid. One cover
 * fills the square, and fewer than four repeat from the start so no cell is left empty.
 */
object LocalCollage {
    private const val SIDE = 640

    /** Distinct, existing image paths in order, capped at four. */
    fun sources(coverPaths: List<String>): List<String> =
        coverPaths.filter { it.isNotBlank() && File(it).isFile }.distinct().take(4)

    /** Fingerprint of the sources, so a changed playlist gets a new file name and no stale cache. */
    fun key(sources: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        sources.forEach { path ->
            digest.update(path.toByteArray())
            digest.update(File(path).lastModified().toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(10)
    }

    /** Writes the collage as a JPEG; false when there was nothing to stitch. */
    fun write(coverPaths: List<String>, out: File, side: Int = SIDE): Boolean {
        val tiles = sources(coverPaths).mapNotNull { decode(it, side) }
        if (tiles.isEmpty()) return false
        val canvasBitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(0x18, 0x1b, 0x1e))
        if (tiles.size == 1) {
            draw(canvas, tiles[0], 0, 0, side)
        } else {
            val half = side / 2
            for (cell in 0 until 4) {
                draw(canvas, tiles[if (cell < tiles.size) cell else cell % tiles.size], (cell % 2) * half, (cell / 2) * half, half)
            }
        }
        out.parentFile?.mkdirs()
        val saved = out.outputStream().use { canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tiles.forEach { it.recycle() }
        canvasBitmap.recycle()
        return saved
    }

    private fun decode(path: String, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= side && bounds.outHeight / (sample * 2) >= side) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Center-crops to a square, so a wide cover is not squashed and the band does not look like a funhouse mirror. */
    private fun draw(canvas: Canvas, bitmap: Bitmap, x: Int, y: Int, size: Int) {
        val edge = minOf(bitmap.width, bitmap.height)
        val source = Rect((bitmap.width - edge) / 2, (bitmap.height - edge) / 2, (bitmap.width + edge) / 2, (bitmap.height + edge) / 2)
        canvas.drawBitmap(bitmap, source, Rect(x, y, x + size, y + size), null)
    }
}
