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

import dev.sfg.orchard.mobile.model.LyricLine

/**
 * Turns user-supplied lyrics into the lines the player draws. Understands LRC (several stamps per
 * line, an `[offset:]` tag), SRT and plain text. Pure Kotlin so unit tests need no device.
 */
object LocalLyricsParser {
    private val lrcStamp = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
    private val lrcOffset = Regex("""^\s*\[offset:\s*(-?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val wordStamp = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val srtArrow = Regex("""(\d+):(\d+):(\d+)[.,](\d+)\s*-->\s*(\d+):(\d+):(\d+)[.,](\d+)""")

    fun parse(input: String): List<LyricLine> {
        val text = input.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()
        if (srtArrow.containsMatchIn(text)) return srt(text)
        val synced = lrc(text)
        return synced.ifEmpty { plain(text) }
    }

    private fun plain(text: String): List<LyricLine> =
        text.lineSequence().map(String::trim).filter(String::isNotEmpty).map { LyricLine(it) }.toList()

    private fun lrc(text: String): List<LyricLine> {
        var offsetMs = 0L
        val lines = mutableListOf<LyricLine>()
        for (row in text.lineSequence()) {
            lrcOffset.find(row)?.let {
                // The spec says a positive offset shows lyrics earlier.
                offsetMs = -(it.groupValues[1].toLongOrNull() ?: 0L)
                continue
            }
            val starts = mutableListOf<Long>()
            var cursor = 0
            while (true) {
                val match = lrcStamp.find(row, cursor)?.takeIf { it.range.first == cursor } ?: break
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].replace(':', '.').toDouble()
                starts += minutes * 60_000 + (seconds * 1000).toLong()
                cursor = match.range.last + 1
            }
            if (starts.isEmpty()) continue
            val words = row.substring(cursor).replace(wordStamp, "").trim()
            // Stamped blanks are instrumental gaps; they keep their time but draw as nothing.
            starts.forEach { lines += LyricLine(words, (it + offsetMs).coerceAtLeast(0)) }
        }
        val sorted = lines.sortedBy { it.startMs }
        return sorted.mapIndexed { index, line -> line.copy(endMs = sorted.getOrNull(index + 1)?.startMs) }
    }

    private fun srt(text: String): List<LyricLine> {
        val rows = text.lines()
        val lines = mutableListOf<LyricLine>()
        var index = 0
        while (index < rows.size) {
            val match = srtArrow.find(rows[index])
            index++
            if (match == null) continue
            val body = mutableListOf<String>()
            while (index < rows.size && rows[index].isNotBlank()) body += rows[index++].trim()
            if (body.isEmpty()) continue
            val g = match.groupValues
            lines += LyricLine(body.joinToString(" "), stamp(g[1], g[2], g[3], g[4]), stamp(g[5], g[6], g[7], g[8]))
        }
        return lines
    }

    private fun stamp(h: String, m: String, s: String, fraction: String): Long {
        // "5" means half a second and "500" too: the fraction is a decimal, not a count.
        val millis = (("0.$fraction").toDouble() * 1000).toLong()
        return h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + millis
    }
}
